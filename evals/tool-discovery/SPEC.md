---
id: evals-tool-discovery
type: module-design
status: draft
title: evals/tool-discovery — evaluation harness for on-demand tool discovery
parent: agentweave-architecture
---

# evals/tool-discovery

## Responsibility

This module measures tool discovery against an **all-tools baseline** and binds every result to
the exact commit and dataset revisions. It covers retrieval quality (offline) and LLM tool
selection, the call-correctness proxy, discovery calls, context tokens, latency and cost. A cost
guard stops runs before they exceed the budget. How-to and full metric tables are in
`README.md`.

## Boundary

- The module is eval-only. It is an `application` (`io.agentweave.evals.discovery.EvalMain`) with
  publishing disabled. Nothing depends on it.
- It depends on `agentweave-core`, `com.anthropic:anthropic-java:3.5.0`,
  `com.openai:openai-java:4.100.0`, `com.knuddels:jtokkit:1.1.0` and Jackson 3. The SDKs never reach
  a published module, so the "ships no LLM clients" non-goal holds.
- It depends on `agentweave-discovery` and `agentweave-discovery-embeddings` (bge-small through
  `agentweave-model-bge-small-en-v15`) to evaluate them. Discovery retrievers and arms are
  registered through `RetrieverFactory`/`ArmFactory` (ServiceLoader); the CLI has no
  discovery-specific code except the `calibrate` command. The discovery modules make no model
  calls; only this module's own clients do.
- Data is downloaded by `scripts/fetch_*.py` into `build/data/` and never committed. ToolRet's HF
  cards have no license, so it is used locally only. MCPToolBench++ is MIT.

## Public API (package `io.agentweave.evals.discovery`)

- `data`:
  - `EvalTool(id, dataset, ToolSpec, rawDocument)` and
    `EvalTask(id, dataset, query, instruction, goldRelevance, goldCalls, alternativeTools)`.
  - `EvalCatalog`: the required gold tools plus seeded distractors. Its provider-safe names are
    reversible.
  - `McpToolBenchLoader` and `ToolRetLoader`, with `ToolRetSchemas` converting ToolRet documents
    to JSON Schema, and `TaskSampler`.
- `retrieval`: `Retriever { name(); index(catalog); retrieve(task, catalog, k) }`, plus
  `RandomRetriever`, `OracleRetriever` and the `RetrieverFactory` SPI.
  - Discovery-backed: `CatalogBridge` (eval catalog → `CapabilityCatalog`), `CatalogRetriever`
    (`catalog-bm25`, `catalog-dense`, `catalog-hybrid`, registered by `DiscoveryRetrieverFactory`;
    `<name>:key=value:…` names a tuning variant such as `catalog-hybrid:fusion=rrf:rrf-k=60`),
    `VectorOnlyIndex` and `CachingEmbedder`. `run-retrieval --split dev|test` scores one half of
    the tasks by the same stable hash as `calibrate`, over catalogs holding every task's gold tools.
- `calibration`: `Calibration` (signals, levels, metrics, policy grid search); the
  `run.CalibrateRun` command writes `calibration.json`.
- `llm`:
  - `ModelClient { provider(); model(); send(ModelRequest) }`, with `Turn`
    (User / Assistant(providerState) / ToolResults), `ToolCall`, `ToolResult`, `Usage`.
  - Provider-specific parts: `ModelRequest.rawTools` (raw tool JSON) and `ToolResult.raw` (a raw
    `tool_result` block or input item); `OpenAiModelClient.TOOL_SEARCH_CALL` names parsed
    client-executed `tool_search_call`s.
  - Implementations: `AnthropicModelClient` (Messages API), `OpenAiModelClient` (Responses API),
    `AnthropicTokenCounter`, `TokenEstimator` (jtokkit), `ScriptedModelClient` and
    `EstimatingModelClient`.
- `arms`:
  - `Arm { name(); run(task, catalog, client) }` → `ArmResult`.
  - `ToolLoop`: a select/respond loop with a max-turns limit and discovery counting.
  - Built-in arms `AllToolsArm` and `TopKInjectedArm`, plus the `ArmFactory` SPI.
  - Discovery arms `GenericSearchArm`, `NativeAnthropicArm`, `NativeOpenAiArm`, registered by
    `DiscoveryArmFactory` as `generic-search`, `native-anthropic`, `native-openai`.
- `metrics`: `RetrievalMetrics`, `ArgumentMatcher`, `CallScorer` and `Stats`.
- `cost`: `PriceTable`, `CostGuard`, `SpendLedger` and `GuardedModelClient`.

## Decisions

- **Retrieval metrics** follow trec_eval semantics as used by ToolRet:
  - P@k divides by k;
  - nDCG uses linear gain;
  - Completeness@k means recall@k = 1;
  - MRR is computed at the retrieval depth.
- **Selection** is judged on the first real call. Dataset `similar_tools` count as correct;
  "strict" excludes them.
- **Call correctness** is a deterministic AST match: gold keys must be equal after normalization,
  required keys must be present, and extra keys must be declared properties. It is a labeled proxy
  for task completion, because tools are never executed. Arg-keys correctness is the upper bound.
- **Confidence intervals**:
  - Wilson for single-trial proportions;
  - percentile bootstrap over tasks otherwise (2,000 resamples, fixed seed);
  - a paired bootstrap for arm differences.
- **Catalogs**:
  - Required gold tools come first, sorted. Distractors are taken from a pool shuffled with
    `Random(seed)`, so catalogs with the same seed nest.
  - Content duplicates of included tools are skipped.
  - Names are sanitized to `^[a-zA-Z0-9_-]{1,64}$` with case-insensitive dedupe suffixes, gold
    first.
  - The presentation order is a second seeded shuffle.
- **Run order** is N → trial → task → model → arm, so a budget stop leaves paired data.
- **Cost guard**:
  - Checks happen before each call, using worst-case cost: jtokkit estimate × safety factor at the
    highest input rate, plus the full output cap.
  - Spend is charged from provider usage and persisted in a ledger across runs.
  - Budget 0 (the default) refuses paid calls. Unpriced models are refused unless explicitly
    allowed with a call limit.
  - The harness ships no prices.
- **Model defaults** are documented, not fixed: `claude-sonnet-5-5` and `gpt-6.1-sol`.
  - Temperature is not sent by default, because current Claude models reject it.
  - Tools go to OpenAI as non-strict functions, so schemas pass through unchanged.

- **Discovery retrievers** run the product's own `catalog.search`:
  - Sources are MCP servers (from the tool's `server` metadata) or, without one, the dataset.
    Tools are named by their provider-safe eval-catalog name, so `<source>.<name>` maps back to
    eval ids one to one.
  - `catalog-dense` is a `CapabilityIndex` wrapper: a `HybridIndex` with the embedder indexes, and
    hits are re-ranked by cosine, below-floor hits dropped. Keyword signals stay on the hits, so
    `Confidence` uses the same inputs as hybrid.
  - Retrieval and calibration use fallback `NONE` (pure ranking); arms default to `PROGRESSIVE`.
  - Embeddings are cached on disk per model, keyed by SHA-256 of model, kind and text. Appends are
    single writes under an exclusive file lock; a truncated tail is cut off on load.
- **Calibration** never applies anything. It re-derives levels from recorded signals
  (`Calibration.level` mirrors `Confidence.Policy.assess`, checked by a test), splits tasks into
  dev/test by a stable hash, and maximizes P(hit@1 | HIGH) + P(LOW | miss@5) on dev subject to a
  minimum HIGH share and a maximum LOW rate among top-5 hits. Default and suggested policies are
  reported on both splits.
- **Discovery arms**:
  - `generic-search` offers only the three `DiscoveryTools` meta-tools, answered through a
    `DiscoverySession`; `call_capability` (or a direct call of a capability name) is the
    selection. Parameters: `inlineSchemas`, `fallback`, `load`.
  - `native-anthropic` sends the catalog as `defer_loading` tools plus the custom search tool and
    answers searches with `tool_reference` blocks; `native-openai` sends the client `tool_search`
    tool and answers with `tool_search_output` (optionally also declaring deferred functions or
    namespaces). Both use `AnthropicPayloads`/`OpenAiPayloads` verbatim as raw JSON through the
    SDKs' additional body properties and Jackson mapping.
  - A native arm given a model of the other provider makes no call and records an error.
  - The cost guard checks every turn. Raw tools count conservatively: deferred tools by name and
    description, others in full; raw answers count the definitions they load.

## Invariants

- Unit tests run offline without keys. SDK wiring is tested against a localhost server.
- API keys are read from the environment only and never logged, written or echoed.
- `ANTHROPIC_BASE_URL` and `OPENAI_BASE_URL` (the SDKs' own variable names) point the clients at
  another endpoint, such as a LiteLLM proxy. The report header records each endpoint as scheme,
  host, port and path (never user info or query), because a proxy can change what reaches the
  model: through a proxy that rewrites requests, provider-native features such as deferred tool
  loading may be dropped silently, so native arms are only meaningful where deferral is shown to
  reduce input tokens.
- Every report header carries the git commit plus a dirty flag, the dataset revisions and hashes,
  the settings and the CI method.
