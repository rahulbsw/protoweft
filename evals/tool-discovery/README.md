# Tool-discovery evaluation harness

Measures AgentWeave's tool-discovery layer against an **all-tools baseline** (every tool's full
schema in every request). It covers retrieval quality, wrong-tool selection, a task-completion
proxy, discovery calls, context tokens, latency and cost. Results are bound to the exact git commit
and dataset revisions. This module is eval-only and never published. The LLM SDKs it uses never
reach an AgentWeave library.

Status: the harness, datasets, metrics, cost guard, both model clients and two arms (all-tools,
top-k injected) are done. Concrete retrievers (BM25, dense, hybrid), the generic search-tool arm
and the native provider arms plug in through the extension points below.

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
| Discovery calls / task | Calls the arm answered as discovery (for example a search tool). Always 0 for the two built-in arms. |
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
# Offline retrieval (built-in retrievers random/oracle; real ones arrive with the discovery module)
./gradlew :evals:tool-discovery:run --args="run-retrieval --datasets mcptoolbench,toolret:toolbench,toolret:toollens \
  --retrievers random,oracle --k 1,3,5,10 --catalog-sizes 200,1000,all --max-queries 500"

# LLM plan without keys or spend: token estimates and worst-case cost per cell
./gradlew :evals:tool-discovery:run --args="run-llm --dry-run --arm all-tools --arm top-k:k=5,retriever=oracle \
  --tasks 50 --catalog-sizes 50,200,1000 --prices prices.json"

# Paid run (keys from the environment only; never printed)
export ANTHROPIC_API_KEY=… OPENAI_API_KEY=…
cp prices.example.json prices.json   # fill in from the providers' pricing pages
./gradlew :evals:tool-discovery:run --args="run-llm --budget-usd 100 --run-cap-usd 25 --prices prices.json \
  --arm all-tools --arm top-k:k=5,retriever=oracle --tasks 50 --catalog-sizes 50,200,1000"
```

Key `run-llm` options (run the `help` command for all of them):
- `--models provider:model,…`: the default is `anthropic:claude-sonnet-5-5,openai:gpt-6.1-sol`,
  one mid-tier model per provider.
  - These come from the providers' model pages at the time of writing. Sonnet 5.5 is "speed and
    intelligence", GPT-6.1 Sol is "balance intelligence and cost", and both are $2/$10 per Mtok on
    those pages.
  - Treat them as defaults only. Pass the current ids with `--models`.
- `--temperature`: the design calls for temperature 0. Current Claude models return a 400 for
  `temperature`, and anthropic-java 3.5.0 no longer models it.
  - The default is therefore `none` (the parameter is not sent).
  - Pass `--temperature 0` only for models that accept it. It is sent raw to Anthropic.
  - The report header records the effective settings.
- `--anthropic-effort`, `--anthropic-thinking adaptive|between_tools|disabled`,
  `--openai-reasoning-effort`: unset means the provider default. They change cost and latency.
- `--anthropic-cache-tools`: marks the tool block `cache_control: ephemeral`. OpenAI caches long
  prefixes automatically and Anthropic only when asked, so set this when comparing cost across
  providers.
- `--catalog-scope shared|per-task`:
  - `shared` (default) builds one catalog per N, holding every sampled task's gold tools plus
    distractors. N must cover the gold tools.
  - `per-task` gives each task its own N-tool catalog.
- `--distractors toolret|own|both`: the default is `toolret` when it has been fetched.
- `--tasks` is a stratified sample per category, and `--trials` repeats each task.
- `--max-turns` is the tool-loop limit for multi-turn arms. `--max-output-tokens` is also the cost
  guard's worst-case output.

### Cost guard

- Before **every** call, the guard computes a worst case: estimated input tokens × `--estimate-safety`
  (1.3) at the highest input rate, plus `--max-output-tokens` at the output rate.
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

## Extension points (for the discovery integration)

- `retrieval.Retriever`: `name()`, `index(catalog)`, and `retrieve(task, catalog, k)`, which
  returns catalog tool ids, best first. Register it through `retrieval.RetrieverFactory` in
  `META-INF/services/…RetrieverFactory`. `--retrievers bm25` and `--arm top-k:retriever=bm25` then
  work without CLI changes. Options come from `--retriever-opt key=value`.
- `arms.Arm`: `run(task, catalog, client)`. Register it through `arms.ArmFactory` (ServiceLoader)
  as a kind, for example `--arm search-tool:k=5`.
  - Multi-turn arms use `arms.ToolLoop`. For each tool call, the arm's handler either *selects* it
    (the model's choice) or *responds* (for example with search results), optionally adding tool
    schemas for the next turn and marking the call as discovery.
  - The loop counts discovery calls, sums usage and stops at `--max-turns`. See
    `ArmsTest.toolLoopSupportsSearchThenCall`.
- `llm.ModelClient`: one provider-neutral request (system, `Turn`s, `ToolSpec`s) → one reply
  (text, tool calls, usage, latency).
  - Clients replay their own `providerState` (Anthropic `Message`, OpenAI output items), so
    thinking and reasoning items survive across turns.
  - Native arms that need provider-specific request parts (deferred tools, `tool_search`) can add a
    client type next to `AnthropicModelClient`/`OpenAiModelClient`.
- Catalog names are provider-safe (`^[a-zA-Z0-9_-]{1,64}$`) and reversible:
  `catalog.providerName(id)` and `catalog.toolIdForName(name)`. Gold tools are named first, so they
  never get a dedupe suffix.

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

## Tests

Run `./gradlew :evals:tool-discovery:build`. The tests are fully offline and need no keys. The SDK
clients are exercised against a canned localhost server, and the arms against scripted fake
clients.
