# Tool-discovery evaluation harness

Measures AgentWeave's tool-discovery layer against an **all-tools baseline** (every tool's full
schema in every request). It covers retrieval quality, wrong-tool selection, a task-completion
proxy, discovery calls, context tokens, latency and cost. Results are bound to the exact git commit
and dataset revisions. This module is eval-only and never published. The LLM SDKs it uses never
reach an AgentWeave library.

Status: the harness, datasets, metrics, cost guard, both model clients, the two baseline arms
(all-tools, top-k injected), the discovery-backed retrievers (`catalog-bm25`, `catalog-dense`,
`catalog-hybrid`, `catalog-hybrid-minilm`), confidence calibration (`calibrate`) and the three
discovery arms (`generic-search`, `native-anthropic`, `native-openai`) are done. The LLM arms have
only been run against scripted and canned-HTTP clients; no paid run has been made.

## What is measured

**Offline arm** (`run-retrieval`, no LLM, no keys). Datasets × retrievers × catalog sizes. Metrics
per query follow trec_eval/pytrec_eval semantics, as in ToolRet's `toolret/eval.py`:

| Metric | Definition |
|---|---|
| Recall@k | relevant ∩ top-k / relevant (grade ≥ 1 is relevant) |
| Precision@k | relevant ∩ top-k / **k** (k even if fewer were retrieved) |
| nDCG@k | Σ gain/log2(rank+1) with **linear** gain = grade (trec_eval), over the ideal DCG from all positive labels |
| MRR | 1 / rank of the first relevant tool in the retrieved list (depth `--depth`, default 100), else 0 |
| Completeness@k | 1 if **all** gold tools are in the top k (ToolRet's "Comprehensiveness"), else 0 |

**LLM arms** (`run-llm`). Arms × models × catalog sizes N × trials, on MCPToolBench++ tasks:

| Metric | Definition |
|---|---|
| Selection accuracy | The first real (non-discovery) call targets a gold tool or a dataset-accepted alternative (MCPToolBench++ `similar_tools`, as in its own check). *Strict* = gold only. No call counts as wrong. |
| Wrong-tool rate | Non-gold calls (including hallucinated names) / all real calls, pooled over tasks |
| No-call rate | Tasks with no real tool call |
| **Call correctness** (proxy) | Every gold call is matched by a distinct call to the same gold tool whose arguments pass the AST match below. This is the **labeled task-completion proxy**: tools are never executed. |
| Arg-keys correctness | The same match, ignoring values. This is an upper bound. |
| Discovery calls / task | Calls the arm answered as discovery: `search_capabilities`/`describe_capabilities` (generic), the search tool (native Anthropic) or `tool_search_call` (native OpenAI). Always 0 for all-tools and top-k. |
| Input / output / cached tokens | From the provider's `usage` (the source of truth). Input includes cache reads and writes. |
| Tool payload (est.) | jtokkit `o200k_base` estimate of the tool definitions sent, summed over requests. This is close for OpenAI and only approximate for Claude. |
| Exact input | Anthropic `count_tokens` with `--count-tokens`. It is free but rate-limited. |
| Latency | Summed API wall-clock time |
| Cost | From usage × **your** prices (see below). Shown as "–" when a model is unpriced. |

**AST argument match.** The predicted arguments P match the gold arguments G for a tool with
schema S when all three hold:
1. Every non-null G key is in P with an equal value. A null in G means absent or null.
2. Every name in `S.required` is present.
3. Every key in P that is not in G is a declared property of S (unless S declares no properties or
   allows `additionalProperties`).

Value equality is checked recursively:
- Numbers compare numerically, including numeric strings: `5 = 5.0 = "5"`.
- Booleans compare equal to `"true"`/`"false"` strings.
- Strings compare after NFKC, trim, whitespace collapse and lower-casing.
- Lists compare element-wise, in order.
- Objects follow rule 1: gold keys must match and extra keys are allowed.

Paraphrased free text fails this match. MCPToolBench++ itself uses an LLM judge for that case, so
this proxy is a strict lower bound.

**Confidence intervals** are 95%:
- Proportions with one trial per task use the Wilson score interval.
- Everything else uses a percentile bootstrap over tasks (2,000 resamples, fixed seed). Repeated
  trials are averaged per task first.
- Arm comparisons ("Δ vs all-tools") use a **paired** bootstrap over the same tasks.

A Δ interval that contains 0 means *no difference was detected*. It is not evidence of equal
accuracy. Never report "unchanged accuracy" from such a result.

## Getting the data

Datasets download into `build/data/` (git-ignored through `build/`). **Never commit them.**

```bash
cd evals/tool-discovery
python3 scripts/fetch_mcptoolbench.py   # stdlib only; pinned commit 2bd35e5
python3 scripts/fetch_toolret.py        # parquet via pyarrow if installed, else the HF /rows API
python3 scripts/fetch_toolret.py --subsets all   # all 35 query subsets
```

The two datasets have different licenses:

- **MCPToolBench++** (github.com/mcp-tool-bench/MCPToolBenchPP) is MIT-licensed. The test fixtures
  under `src/test/resources/fixtures/mcptoolbench` are small attributed excerpts.
- **ToolRet** (arXiv 2503.01763; HF `mangopy/ToolRet-Queries`, `mangopy/ToolRet-Tools`) has
  Apache-2.0 code, but its Hugging Face data cards carry **no license tag**. Download it locally at
  evaluation time only, and never commit or redistribute it. The ToolRet fixtures in the tests are
  hand-written, not copied.

Each fetch writes a `manifest.json` with the upstream revision and per-file hashes. Every report
header repeats the revision and a SHA-256 of the files actually read.

**Data facts**, verified when the harness was written:
- **MCPToolBench++** has six data files (browser 187, file_system 241, finance 90, map 500, pay
  310, search 181), for 1,509 tasks over 87 tools.
  - Rows are `{uuid, category, call_type, tools[{name, description, input_schema}], mcp_tools_dict, query, function_call_label[{name, mcp_server, input, similar_tools, …}]}`.
  - Two map tasks have two gold calls.
  - `*_demo.json` and the `*forqwen*` duplicate are skipped.
- **ToolRet** has 44,453 tools (web 37,292, code 3,794, customized 3,367) and 35 query subsets.
  - Labels are `[{id, relevance}]`, all binary in the subsets checked.
  - Tool documents come in about ten formats (for example `parameters` as JSON Schema with
    `type: dict`, `"str, optional"` maps, OpenAPI-style lists, `required_parameters` lists, or
    signatures like `(image: str)`).
  - `ToolRetSchemas` converts all of them to JSON Schema. All 44,453 converted schemas validate as
    Draft 2020-12, and property names are made provider-safe.

## Running

The examples run from the repository root. `run` uses `evals/tool-discovery` as its working
directory.

```bash
# Offline retrieval (no keys, no cost)
./gradlew :evals:tool-discovery:run --args="run-retrieval --datasets mcptoolbench,toolret:toolbench,toolret:toollens \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid-minilm,random,oracle \
  --k 1,3,5,10 --catalog-sizes 1000,10000,all"

# Confidence calibration (offline; writes calibration.json, applies nothing)
./gradlew :evals:tool-discovery:run --args="calibrate --datasets mcptoolbench,toolret:toolbench,toolret:toollens \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid-minilm --catalog-sizes 1000,all"

# LLM plan without keys or spend: token estimates and worst-case cost per cell
./gradlew :evals:tool-discovery:run --args="run-llm --dry-run --arm all-tools --arm top-k:k=5,retriever=catalog-hybrid \
  --arm generic-search --tasks 50 --catalog-sizes 50,200,1000 --prices prices.json"

# Paid run (keys from the environment only; never printed)
export ANTHROPIC_API_KEY=… OPENAI_API_KEY=…
cp prices.example.json prices.json   # fill in from the providers' pricing pages
./gradlew :evals:tool-discovery:run --args="run-llm --budget-usd 100 --run-cap-usd 25 --prices prices.json \
  --arm all-tools --arm top-k:k=5,retriever=catalog-hybrid --arm generic-search --arm native-anthropic \
  --models anthropic:<model> --tasks 50 --catalog-sizes 50,200,1000"
```

For large ToolRet catalogs, give the JVM room (`JAVA_OPTS=-Xmx8g` with the installed distribution,
`./gradlew :evals:tool-discovery:installDist`, then `build/install/tool-discovery-eval/bin/tool-discovery-eval`).

### Discovery retrievers

Each `catalog-*` retriever ingests the eval catalog into a real AgentWeave `CapabilityCatalog` and
ranks with `catalog.search`, so ranking, the vector floor and `Confidence` are the product's:

| Retriever | Ranking |
|---|---|
| `catalog-bm25` | Keyword-only catalog (field-weighted BM25, no embedder) |
| `catalog-dense` | Cosine only, with `bge-small-en-v1.5` (quantized, offline). A small `CapabilityIndex` wrapper (`VectorOnlyIndex`) lets a `HybridIndex` with the embedder index the documents, then re-ranks its hits by cosine and drops hits below the vector floor |
| `catalog-hybrid` | The default hybrid catalog (BM25 + cosine, RRF k = 60) with `bge-small-en-v1.5` |
| `catalog-hybrid-minilm` | The same with `all-MiniLM-L6-v2` |

- **Catalog construction** (`CatalogBridge`): one fixed `ToolSource` per MCP server (MCPToolBench++
  tools carry their server) or, for tools without a server, per dataset (`toolret-web`,
  `toolret-code`, `toolret-customized`). Inside a source each tool is named by its provider-safe
  eval-catalog name, so capability names `<source>.<name>` map back to eval tool ids one to one.
  The source name is indexed (BM25 field weight 1), as it would be in a deployment.
- **Retrieval runs use fallback `none`**, so the ranking is never broadened; arms use the product
  default (`progressive`). Queries are the task query; ToolRet instructions are not used unless
  `--retriever-opt instruction=true`.
- **Options** (`--retriever-opt key=value`, or in an arm spec): `fallback=none|progressive|all_schemas`,
  `min-cosine=<x>` (vector floor, default 0.30), `instruction=true`,
  `embedding-cache=<dir>|none`.
- **Embedding cache**: document and query vectors are cached on disk under
  `build/embedding-cache/<model>.vec`, keyed by SHA-256 of model + kind + text. Repeated runs over
  the 44k ToolRet tools re-embed nothing; a truncated tail from an interrupted run is cut off.
  Delete the directory to start over.

### Confidence calibration

`calibrate` searches every task (datasets × catalog sizes, built as in `run-retrieval`) with each
catalog retriever and records the product's confidence signals (top-hit coverage, top-1/top-2
margin, keyword/vector agreement, top cosine) and whether a gold tool was ranked first or in the
top 5. Tasks are split into dev and test by a stable hash of the task id (`--dev-fraction 0.5`).

- The report gives, per dataset, retriever and N, how often each level occurs under the default
  policy and its hit@1/hit@5 rates.
- On the dev split, a grid search over `Confidence.Policy` thresholds maximizes P(hit@1 | HIGH) +
  P(LOW | miss@5), subject to HIGH on at least `--min-high-share` (0.10) of queries and LOW on at
  most `--max-low-false-alarm` (0.20) of top-5 hits. Keyword-only confidence reads coverage and
  margin; hybrid reads coverage, cosine and agreement, so the unused thresholds stay at their
  defaults.
- Levels are re-derived from the recorded signals (`Calibration.level` mirrors
  `Confidence.Policy.assess`; a test checks it against the catalog's own levels).
- Default and suggested policies are reported on dev and on the held-out test split.
  `calibration.json` holds the suggestion. **Nothing is applied**: change `Confidence.Policy` in
  your catalog configuration yourself, and only if the test split agrees.

### Discovery arms

All three use `ToolLoop` (up to `--max-turns`), never execute tools, and record the selected tool,
arguments, discovery calls, turns, provider usage and latency in the existing columns. Their
extras log every search (query, results, confidence, stage) and `goldFound`. Common options:
`retriever=<catalog-*>` (default `catalog-hybrid`), `fallback=progressive|all_schemas|none`
(default `progressive`), plus the retriever options above.

| Arm spec | What the model gets | Selection |
|---|---|---|
| `generic-search[:inlineSchemas=0\|3,load=true]` | Only the three `DiscoveryTools` meta-tools. Search and describe are answered by the catalog through a `DiscoverySession` (the product's own text). `inlineSchemas=n` appends the top n schemas to each search result; `load=true` also makes described schemas directly callable | `call_capability(name, arguments)`, or a direct call of a capability name |
| `native-anthropic` | `AnthropicPayloads`: every catalog tool with `defer_loading: true` plus the non-deferred `search_capabilities` tool. A search is answered with a `tool_result` of `tool_reference` blocks | A call of any loaded tool |
| `native-openai[:deferred=none\|functions\|namespaces]` | `OpenAiPayloads`: the client-executed `tool_search` tool. Each `tool_search_call` is answered with a `tool_search_output` holding the hits' function definitions. `deferred` also declares the catalog as `defer_loading` functions (optionally per-source namespaces) | A function call |

- Native arms send raw JSON: `ModelRequest.rawTools` replaces the SDK `tools` array through the
  SDKs' additional body properties, and `ToolResult.raw` carries the `tool_result` block or input
  item, converted with the SDK's own Jackson mapping. The OpenAI client parses
  `tool_search_call` items and replays them.
- A native arm on a model of the other provider makes no call and records an error ("needs a
  anthropic model"); run each native arm with `--models` of its provider.
- Tool names differ from the baselines: discovery arms expose `<source>.<tool>` (generic) or
  `<source>__<tool>` (native), so the model sees the server name; all-tools and top-k expose the
  bare tool name.
- Anthropic: deferred tools carry no `cache_control`; with `--anthropic-cache-tools` the
  breakpoint goes on the last non-deferred tool. At most 10,000 deferred tools are accepted, so
  `native-anthropic` reports an error at larger N.

### Cost guard

- Before **every** call, the guard computes a worst case: estimated input tokens × `--estimate-safety`
  (1.3) at the highest input rate, plus `--max-output-tokens` at the output rate.
  - Multi-turn arms are checked before each turn, with the whole conversation so far.
  - Raw tools count conservatively: a deferred tool counts its name and description (OpenAI shows
    those up front; Anthropic shows nothing, so this over-estimates there), others their full
    JSON. A `tool_reference` or `tool_search_output` answer counts the definitions it loads.
    The "Tool payload (est.)" column uses the same rule, so for `native-anthropic` it is an upper
    bound; provider `usage` is what was actually billed.
- It refuses the call when any of these holds:
  - the global budget would be exceeded. Spend accumulates across runs in `.eval-spend.json`
    (git-ignored, `--ledger`);
  - this run's `--run-cap-usd` would be exceeded;
  - `--budget-usd` is 0, which is the default, so paid calls need an explicit budget.
- The run then stops cleanly:
  - `calls.jsonl` already holds every finished task;
  - the report says **"Stopped early — partial results"**.
- Within each N the run loops task → model → arm, so a stop leaves every arm with the same tasks.
- Prices are never invented.
  - Without a price for a model, paid calls are refused.
  - To run without prices, pass `--allow-unpriced --max-calls N`. The report then shows tokens
    only.
- After 3 consecutive call errors (`--max-consecutive-errors`), a (model, arm, N) cell is aborted.
  For example, the all-tools arm at an N that exceeds a provider limit. The report's notes say so.

## Reading the report

Each run writes `build/eval-results/<run-id>/`:
- `calls.jsonl`: one line per task × arm × model × N × trial. It holds the selected calls and
  arguments, mismatch reasons, tokens, latency, cost and the arm's extras (for example the
  retrieved ids and `goldInjected` for top-k).
- `summary.json`: every metric as `{mean, low, high, n, method}`, plus the deltas against
  all-tools, spend, prices and notes.
- `report.md`: the header holds the commit (with a **dirty** warning), dataset revisions and hashes,
  settings and the CI method. Then come the quality, tokens/cost and paired-Δ tables.

## Extension points

- `retrieval.Retriever`: `name()`, `index(catalog)`, and `retrieve(task, catalog, k)`, which
  returns catalog tool ids, best first. Register it through `retrieval.RetrieverFactory` in
  `META-INF/services/…RetrieverFactory`; `DiscoveryRetrieverFactory` registers the `catalog-*`
  retrievers this way, so `--retrievers catalog-bm25` and `--arm top-k:retriever=catalog-bm25`
  work without CLI changes. Options come from `--retriever-opt key=value`.
- `arms.Arm`: `run(task, catalog, client)`. Register it through `arms.ArmFactory` (ServiceLoader)
  as a kind; `DiscoveryArmFactory` registers `generic-search`, `native-anthropic` and
  `native-openai`.
  - Multi-turn arms use `arms.ToolLoop`. For each tool call, the arm's handler either *selects* it
    (the model's choice) or *responds* (for example with search results), optionally adding tool
    schemas for the next turn and marking the call as discovery.
  - The loop counts discovery calls, sums usage and stops at `--max-turns`. See
    `ArmsTest.toolLoopSupportsSearchThenCall`.
- `llm.ModelClient`: one provider-neutral request (system, `Turn`s, `ToolSpec`s) → one reply
  (text, tool calls, usage, latency).
  - Clients replay their own `providerState` (Anthropic `Message`, OpenAI output items), so
    thinking and reasoning items survive across turns.
  - Provider-specific request parts go in `ModelRequest.rawTools` (sent verbatim as the `tools`
    array, after the neutral tools) and `ToolResult.raw` (a raw `tool_result` block or input item).
    Only the client of that provider understands them.
- Catalog names are provider-safe (`^[a-zA-Z0-9_-]{1,64}$`) and reversible:
  `catalog.providerName(id)` and `catalog.toolIdForName(name)`. Gold tools are named first, so they
  never get a dedupe suffix.

## Offline results

The published offline results are in [`docs/discovery/evaluation.md`](../../docs/discovery/evaluation.md).
They were measured at clean commits `fdbf3d9` (N = 1,000, 10,000 and all) and `611d295`
(MCPToolBench++ against its own 87 tools), together with the calibration run that set the hybrid
confidence presets. Model-in-the-loop results (`run-llm`) haven't been produced yet.

## Caveats

- Call correctness is a proxy. Tools are never executed, and paraphrased free-text arguments fail
  the deterministic match.
- Distractors come from other datasets, so some may be near-duplicates of a gold tool's function
  (exact content duplicates are removed). This affects all arms equally.
- Results hold only for the models, settings, N and data in the report header. Frontier and
  mid-tier models differ, and provider tokenizers differ (jtokkit is exact only for OpenAI).
- Without `--anthropic-cache-tools`, Anthropic requests are not prompt-cached while OpenAI's are
  cached automatically. Check the `cached` column before comparing costs across providers.
- Retrieval at `--catalog-sizes all` drops exact content duplicates of included tools, unlike
  ToolRet, so numbers are close to but not identical with the paper's.
- The catalog retrievers rank the product's card text (name, summary = first sentence, tags,
  parameter names and descriptions; BM25 also reads the full description), not ToolRet's raw
  documents, and use no ToolRet instructions by default. Compare them with each other, not with
  the paper's embedding models.
- `catalog-hybrid` and `catalog-dense` rank the 44k-tool corpus with an in-memory index and one
  query embedding per query; a first run embeds the corpus (minutes on a laptop CPU) and fills the
  embedding cache.

## Tests

Run `./gradlew :evals:tool-discovery:build`. The tests are fully offline and need no keys. The SDK
clients are exercised against a canned localhost server, and the arms against scripted fake
clients. The native arms are tested through the real SDK clients against canned replies, asserting
the exact request JSON (`defer_loading`, the client `tool_search` tool) and the answers sent back
(`tool_reference` blocks, `tool_search_output` items). Retriever tests use the real offline
embedders on the fixtures.
