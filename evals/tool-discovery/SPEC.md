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
- It does not depend on `agentweave-discovery` yet. Discovery retrievers and arms plug in through
  `RetrieverFactory`/`ArmFactory` (ServiceLoader), or through a later dependency edge.
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
- `llm`:
  - `ModelClient { provider(); model(); send(ModelRequest) }`, with `Turn`
    (User / Assistant(providerState) / ToolResults), `ToolCall`, `ToolResult`, `Usage`.
  - Implementations: `AnthropicModelClient` (Messages API), `OpenAiModelClient` (Responses API),
    `AnthropicTokenCounter`, `TokenEstimator` (jtokkit), `ScriptedModelClient` and
    `EstimatingModelClient`.
- `arms`:
  - `Arm { name(); run(task, catalog, client) }` → `ArmResult`.
  - `ToolLoop`: a select/respond loop with a max-turns limit and discovery counting.
  - Built-in arms `AllToolsArm` and `TopKInjectedArm`, plus the `ArmFactory` SPI.
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

## Invariants

- Unit tests run offline without keys. SDK wiring is tested against a localhost server.
- API keys are read from the environment only and never logged, written or echoed.
- Every report header carries the git commit plus a dirty flag, the dataset revisions and hashes,
  the settings and the CI method.
