---
id: evals-tool-discovery
type: module-design
status: draft
title: evals/tool-discovery — evaluation harness for on-demand tool discovery
parent: protoweft-architecture
---

# evals/tool-discovery

## Responsibility

This module measures tool discovery against an **all-tools baseline** and binds every result to
the exact commit and dataset revisions. It covers retrieval quality (offline) and LLM tool
selection, the call-correctness proxy, discovery calls, context tokens, latency and cost. A cost
guard stops runs before they exceed the budget. How-to and full metric tables are in
`README.md`.

## Boundary

- The module is eval-only. It is an `application` (`io.github.rahulbsw.protoweft.evals.discovery.EvalMain`) with
  publishing disabled. Nothing depends on it.
- It depends on `protoweft-core`, `com.anthropic:anthropic-java:3.5.0`,
  `com.openai:openai-java:4.100.0`, `com.knuddels:jtokkit:1.1.0` and Jackson 3. The SDKs never reach
  a published module, so the "ships no LLM clients" non-goal holds.
- It depends on `protoweft-discovery` and `protoweft-discovery-embeddings` (bge-small through
  `protoweft-model-bge-small-en-v15`) to evaluate them. Discovery retrievers and arms are
  registered through `RetrieverFactory`/`ArmFactory` (ServiceLoader); the CLI has no
  discovery-specific code except the `calibrate` command. The discovery modules make no model
  calls; only this module's own clients do.
- Data is downloaded by `scripts/fetch_*.py` into `build/data/` and never committed. ToolRet's HF
  cards have no license, so it is used locally only. MCPToolBench++ is MIT.

## Public API (package `io.github.rahulbsw.protoweft.evals.discovery`)

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
    `VectorOnlyIndex` and `CachingEmbedder`.
  - Reranker experiment (not a product feature): `rerank.CrossEncoderReranker` and its own
    `rerank.OnnxModel` (a copy of the embeddings module's internal session wrapper, so the product
    gains no API for an unshipped feature), `RerankingIndex`, the `rerank=…` retriever options,
    `scripts/fetch_reranker.py` (pinned revisions and SHA-256) and `scripts/paired_bootstrap.py`.
    The plain `catalog-hybrid` uses the product's default fusion (`FusionOptionsTest`): it once
    fell back to RRF, which invalidated a calibration run and labelled two in-loop runs wrongly. `run-retrieval --split dev|test` scores one half of
    the tasks by the same stable hash as `calibrate`, over catalogs holding every task's gold tools.
- `calibration`: `Calibration` (signals, levels, metrics, policy grid search); the
  `run.CalibrateRun` command writes `calibration.json`.
- `llm`:
  - `ModelClient { provider(); model(); send(ModelRequest) }`, with `Turn`
    (User / Assistant(providerState) / ToolResults), `ToolCall`, `ToolResult`, `Usage`.
  - `TokenBreakdown(system, definitions, results, history)` with `total()`/`plus()`; produced by
    `TokenEstimator.breakdown(ModelRequest)` (and `request()` is its `total()`). Accumulated
    cumulatively across a loop's turns, it reports where input tokens go; residual against provider
    `usage.inputTokens` is `ArmResult.reconciliationResidual()`. `ToolLoop.RequestTokens` holds one
    `(realInput, estimate)` per model call so the report reconciles **per request** as well
    (`ArmResult.definitionTokensPerCall()` stays flat; `reconciliationResidualPerCall()` isolates
    provider framing/tokenizer overhead from cumulative re-send effects).
  - `shaping`: `ResultPolicy` (`raw`, `truncate=<n>`, `project`, `byref`) shapes each mocked result;
    `HistoryPolicy` (`full`, `clear-stale`) shapes the conversation re-sent each turn. Both parse
    from arm options `result=`/`history=` and are the study's factorial axes.
  - Provider-specific parts: `ModelRequest.rawTools` (raw tool JSON) and `ToolResult.raw` (a raw
    `tool_result` block or input item); `OpenAiModelClient.TOOL_SEARCH_CALL` names parsed
    client-executed `tool_search_call`s.
  - Implementations: `AnthropicModelClient` (Messages API), `OpenAiModelClient` (Responses API),
    `AnthropicTokenCounter`, `TokenEstimator` (jtokkit), `ScriptedModelClient` and
    `EstimatingModelClient`.
- `arms`:
  - `Arm { name(); run(task, catalog, client) }` → `ArmResult` (carries the cumulative
    `TokenBreakdown` and the per-request `RequestTokens`; arm names gain a `[result,history]` suffix
    when shaping is non-default).
  - `ToolLoop`: a select/respond loop with a max-turns limit and discovery counting, plus an
    opt-in `MockMode(provider, maxRealCalls)` for multi-turn mocked execution.
  - `MultiTurn(provider, maxTurns)`: the run-wide multi-turn context (opt-in `--multiturn`),
    applied by the provider-neutral baseline arms.
  - Built-in arms `AllToolsArm` and `TopKInjectedArm`, plus the `ArmFactory` SPI.
- `fixtures`: `FixtureStore` (JSONL record/replay keyed on tool + `NormalizedArgs` of the
  arguments), `ResultFixture`, `SizeDistribution`, `SyntheticPayload` and `MockResultProvider`
  (fixture replay on hit; a distribution-sized synthetic JSON payload on miss).
  - Discovery arms `GenericSearchArm`, `NativeAnthropicArm`, `NativeOpenAiArm`, registered by
    `DiscoveryArmFactory` as `generic-search`, `native-anthropic`, `native-openai`. The `ArmFactory`
    SPI takes the `MultiTurn` context, so `generic-search` also runs multi-turn with shaping (native
    arms ignore it).
- `metrics`: `RetrievalMetrics`, `ArgumentMatcher`, `CallScorer` (selection/call-correctness plus
  state-based `goldToolsCovered`/`requiredFactsPresent`/`taskSuccess` and `reFetches`), `LlmJudge`
  (an optional model-based grader of the free-text final answer, wired as `--judge-model`) and
  `Stats`.
- `cost`: `PriceTable`, `CostGuard`, `SpendLedger` and `GuardedModelClient`.
- `EvalTask.requiredFacts` (loaded from a `required_facts` row field) drives state-based success.
- `study/tasks.json` (8-call data-dependent tasks) and `scripts/build_study_fixtures.py`
  (heavy-tailed fixtures from real repo files) are the committed milestone-4 study inputs; the
  recorded fixtures land in gitignored `build/`.

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
- **Mocked execution (opt-in `--multiturn`).** By default a selected call ends the loop and tools
  are never executed. With `--multiturn`, a selected call is instead answered with a mocked result
  (`MockResultProvider`: a recorded `ResultFixture` replayed on a tool + normalized-argument match,
  or on a miss a synthetic JSON payload sized from the fixtures' observed `SizeDistribution`, so a
  miss is not a token-free stub) and the loop continues until the task's gold-call count (its
  reference trajectory) is reached, the model stops, or `maxTurns`. Each mocked result is shaped by
  the arm's `ResultPolicy`, and the conversation re-sent each turn by its `HistoryPolicy` (the
  factorial axes); the token accounting reflects the shaped request. After the trajectory bound a
  final synthesis turn runs with `ModelRequest.forbidTools` (`tool_choice: none`) so the model emits
  the text answer that `taskSuccess`/`LlmJudge` score; tools stay in the payload because providers
  reject an empty tool set after tool_use/tool_result history. Eliciting that answer is unreliable
  through a proxy/Bedrock stack, so completion metrics are weak with mocked results — the measured
  result is the token split, not the completion rate.
- **State-based success.** `taskSuccess` = every gold tool was called (`goldToolsCovered`) and the
  final answer contains every `required_facts` string (vacuously true when none declared). It does
  not depend on exact arguments, so it does not sit near 50% the way the call-correct proxy does.
  `reFetches` counts real calls to an already-called tool — the cost a result policy pays when the
  model re-asks for what was trimmed.
- **Native deferral is unmeasurable through a LiteLLM proxy (confirmed).** Against the corporate
  LiteLLM gateway, 40 `defer_loading:true` tools cost *more* input than 40 loaded (6,256 vs 5,896),
  and the hosted `tool_search_tool` 400s, because LiteLLM normalizes Anthropic Messages to OpenAI
  chat-completions and drops both. The native arms need a direct Anthropic/OpenAI endpoint; this
  reproduces the evaluation page's caveat with direct evidence. `ToolSpec` has no output schema,
  so synthetic payloads are well-formed JSON sized to a target, not validated against an output
  contract. The default single-turn behaviour and its published numbers are unchanged. Still
  deferred: the 30–100 multi-step task corpus and real-server recording that make the results/history
  split non-trivial (MCPToolBench++ is mostly single-call, so maxRealCalls is 1 and no result is
  resent); state-based success and an LLM judge; the shaping/history arms; and the paid baseline run.
  Discovery and native arms stay single-turn until that baseline-run milestone.
- **Token accounting is segmented.** `TokenBreakdown` splits each request's jtokkit estimate into
  system/definitions/results/history and is summed cumulatively over the loop, so a result of R
  tokens at turn t is counted on every later turn (the R×(T−t) compounding). Provider `usage` stays
  the source of truth; the reconciliation residual (provider minus estimate) is reported per task.
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
