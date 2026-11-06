# Discovery evaluation

This page reports what has been **measured** about tool discovery, and lists what has not been
measured yet. Every number below comes from the harness in `evals/tool-discovery`, run on the
stated commit and dataset revisions. Reproduce it with the commands at the end.

There are three parts: [model in the loop](#model-in-the-loop) (what Claude Sonnet 5.5 and GPT-5.4
do with discovery compared with sending every tool), [how the defaults were tuned](#tuning)
(fusion, cards per search, a reranker that wasn't shipped, confidence), and
[retrieval](#retrieval) (whether the right tool is among the few discovery would show a model).

!!! warning "What the model-in-the-loop run does and doesn't show"
    One trial of 50 MCPToolBench++ tasks per cell, two models, through a LiteLLM proxy. With the
    tuned defaults, no selection difference between discovery and all-tools was detected at any
    catalog size; intervals are wide (±10–20 points at N = 1,000). Token and cost savings are large
    and certain. The provider-native tool-search arms were **not** measured (see below).

## Model in the loop

### Setup

| | |
|---|---|
| Code | Built at commit `7d1a6149d4cb8e7a0c5dfde5dde7a6e8770233b3` (clean), run 2026-11-06: convex fusion (α = 0.8), at least 10 cards per meta-tool search. The report header says `ec2eaf4`, the project rename that landed during the run; the harness then read git at the end of a run (fixed in `bf2d813`). The rename changed no behaviour |
| Models | Claude Sonnet 5.5 (Messages API) and GPT-5.4 (Responses API), provider-default settings, no temperature sent, max 4,096 output tokens, up to 6 turns |
| Endpoint | A local LiteLLM proxy (`http://localhost:4000`) in front of a corporate model gateway, for both providers. Recorded in the report header |
| Tasks | The first 50 MCPToolBench++ tasks in the harness's seeded order (seed 42), 1 trial |
| Catalogs | N = 50, 200 and 1,000 tools: the tasks' gold tools, then MCPToolBench++'s other tools and ToolRet distractors (`--distractors both`), one catalog per N shared by all tasks |
| Arms | **all-tools** (every tool's full schema in every request); **top-k** (the 5 tools `CapabilityCatalog.search` ranks highest for the task text, injected in full); **generic search** (only the `search_capabilities` / `describe_capabilities` / `call_capability` meta-tools, progressive fallback) |
| Metrics | Selection = the first real tool call is a gold tool or a dataset-accepted alternative. Call correct = tool and arguments match the dataset label exactly (a proxy for task completion; tools are never executed). Input tokens and cost are per task, from provider usage, at public list prices (Sonnet 5.5 $2 / $10 and GPT-5.4 $2.50 / $15 per million input / output tokens, GPT cached input $0.25). Brackets are 95% intervals (Wilson for proportions, bootstrap otherwise) |
| Spend | 1,413 calls, $31.46 at list price (report `llm-20261106-180540-918-7d1a6149d4-1`) |

### Results

| Model | Arm | N | Selection | Before tuning ¹ | Call correct | Input tokens / task | Cost / task |
|---|---|---|---|---|---|---|---|
| Sonnet 5.5 | all-tools | 50 | 98.0% [89.5, 99.6] | — | 56.0% | 15,526 | $0.0321 |
|  | top-k | 50 | 98.0% [89.5, 99.6] | 96.0% | 64.0% | 2,383 | $0.0059 |
|  | generic search | 50 | 94.0% [83.8, 97.9] | 96.0% | 56.0% | 4,242 | $0.0111 |
|  | all-tools | 200 | 94.0% [83.8, 97.9] | — | 52.0% | 41,907 | $0.0849 |
|  | top-k | 200 | 90.0% [78.6, 95.7] | 88.0% | 50.0% | 1,848 | $0.0049 |
|  | generic search | 200 | 98.0% [89.5, 99.6] | 94.0% | 54.0% | 4,116 | $0.0108 |
|  | all-tools | 1,000 | 80.0% [67.0, 88.8] | — | 56.0% | 186,332 | $0.3737 |
|  | top-k | 1,000 | 75.5% [61.9, 85.4] of 49 ² | 72.0% | 53.1% | 1,566 | $0.0043 |
|  | generic search | 1,000 | 80.0% [67.0, 88.8] | 72.0% | 48.0% | 4,107 | $0.0110 |
| GPT-5.4 | all-tools | 50 | 98.0% [89.5, 99.6] | — | 52.0% | 6,892 | $0.0066 |
|  | top-k | 50 | 98.0% [89.5, 99.6] | 98.0% | 52.0% | 1,077 | $0.0032 |
|  | generic search | 50 | 94.0% [83.8, 97.9] | 92.0% | 50.0% | 2,216 | $0.0068 |
|  | all-tools | 200 | 100.0% [72.2, 100.0] of 10 ² | — | 50.0% | 10,812 | $0.0131 |
|  | top-k | 200 | 94.0% [83.8, 97.9] | 86.0% | 52.0% | 779 | $0.0023 |
|  | generic search | 200 | 92.0% [81.2, 96.8] | 90.0% | 50.0% | 2,128 | $0.0065 |
|  | all-tools | 1,000 | 68.4% [52.5, 80.9] of 38 ² | — | 50.0% | 61,288 | $0.0523 |
|  | top-k | 1,000 | 74.0% [60.4, 84.1] | 70.0% | 48.0% | 598 | $0.0020 |
|  | generic search | 1,000 | 68.0% [54.2, 79.2] | 66.0% | 40.0% | 1,973 | $0.0061 |

¹ Selection in the same tasks before tuning (commit `021a2f3`: RRF fusion, 5 cards per search).

² Some all-tools requests were rejected outright: "Invalid 'tools': array too long. Expected an
array with maximum length 128" (7 at N = 200, which stopped that cell after 10 tasks under the
three-consecutive-errors rule, and 12 of 50 at N = 1,000); one Sonnet top-k task hit a proxy
rate limit. The rows show the tasks that ran. The proxy evidently routes to backends with
different tool limits.

Paired differences against all-tools on the same tasks (points for selection and call
correctness, tokens per task):

| Model | N | top-k: Δ selection; Δ input tokens | generic search: Δ selection; Δ call correct; Δ input tokens |
|---|---|---|---|
| Sonnet 5.5 | 50 | +0.0 [+0.0, +0.0] pts; -13,143 | -4.0 [-10.0, +0.0] pts; +0.0 [-8.0, +8.0] pts; -11,283 |
|  | 200 | -4.0 [-12.0, +4.0] pts; -40,059 | +4.0 [+0.0, +10.0] pts; +2.0 [-6.0, +10.0] pts; -37,791 |
|  | 1,000 | -4.1 [-16.3, +8.2] pts; -184,766 | +0.0 [-6.0, +6.0] pts; -8.0 [-16.0, -2.0] pts; -182,224 |
| GPT-5.4 | 50 | +0.0 [+0.0, +0.0] pts; -5,816 | -4.0 [-10.0, +0.0] pts; -2.0 [-6.0, +0.0] pts; -4,676 |
|  | 200 | -20.0 [-50.0, +0.0] pts; -10,121 | -20.0 [-50.0, +0.0] pts; +0.0 [+0.0, +0.0] pts; -8,744 |
|  | 1,000 | +7.9 [-5.3, +21.1] pts; -60,689 | +2.6 [-13.2, +18.4] pts; -10.5 [-26.3, +2.6] pts; -59,315 |

Paired differences from tuning, same tasks and arms, before (`021a2f3`) → after (`7d1a614`):

| Model | Arm | N = 50 | N = 200 | N = 1,000 |
|---|---|---|---|---|
| Sonnet 5.5 | generic search | −2.0 [−6, 0] | +4.0 [0, +10] | **+8.0 [+2, +16]** |
| | top-k | +2.0 [0, +6] | +2.0 [−4, +8] | +2.0 [0, +6.1] |
| GPT-5.4 | generic search | +2.0 [0, +6] | +2.0 [0, +6] | +2.0 [−8, +12] |
| | top-k | 0.0 [−6, +6] | **+8.0 [+2, +16]** | +4.0 [0, +10] |

### What the numbers say

- **Tokens and cost fall sharply, and more as the catalog grows.** At N = 1,000, Sonnet 5.5
  reads 186k input tokens per task with every tool and 1.6k (top-k) or 4.1k (generic search) with
  discovery: $0.37 against $0.004–$0.011 per task. GPT-5.4 caches the repeated tool block
  automatically, so its saving is smaller: $0.052 against $0.002–$0.006.
- **No selection difference against all-tools was detected, but none is ruled out.** With the
  tuned defaults, Sonnet's generic search matches all-tools at N = 1,000 (80.0% each, interval
  −6 to +6) and is slightly ahead at N = 200. GPT-5.4's intervals are wider in both directions.
- **Tuning helped where it was aimed.** Generic search for Sonnet at N = 1,000 went from 72% to
  80% (+8 points [+2, +16]); the 10-card experiment suggests most of that comes from the card
  minimum, though the two changes weren't measured apart here. GPT-5.4's top-k, which shows no
  cards, gained +8 points [+2, +16] at N = 200 from the fusion change alone. No cell got significantly worse.
- **Exact arguments are harder through the meta-tools.** Sonnet's call correctness with generic
  search at N = 1,000 is 8 points below all-tools [−16, −2]: it picks the right tool as often, but
  fills arguments after a describe step and matches the dataset label less often. The exact-match
  proxy penalizes paraphrased free text, so this is an upper bound on the real gap.
- **Losses at large N are mostly retrieval and choice, not missing cards.** At N = 1,000, hybrid
  top-5 recall is 0.72 (MCPToolBench++, all tasks), and GPT-5.4's misses persisted with 10 cards in
  view.
- **Sending every tool stops working on some deployments.** Nineteen GPT-5.4 all-tools requests
  were rejected for exceeding a 128-tool limit; discovery never sends more than a handful.
- **Discovery costs a round trip.** Generic search averages about 2 discovery calls per task.
  Latency isn't compared, because rate-limit retries through the proxy inflated it unevenly.

### Found and fixed along the way

- **Search filters (commit `eb377cf`).** The first smoke run (`48b74f8`) showed GPT-5.4 making no
  tool call on 7 of 10 generic-search tasks. It had set the meta-tool's optional filters —
  `sources: ["all"]`, `protocols` that left out in-process tools, `read_only` on tools without
  annotations — and every such search came back empty. The meta-tool now offers models only
  `query` and `limit`; the same 10 tasks then gave 10 of 10.
- **Card minimum (commit `0dae6b5`).** GPT-5.4 asks for 5 results itself; the meta-tool now
  returns at least 10.
- **Eval harness:** the plain `catalog-hybrid` retriever once fell back to RRF instead of the
  product default, and reports read git at the end of a run. Both are fixed; the affected runs
  are identified below.

### Not measured

- **Provider-native tool search** (Anthropic `defer_loading` + `tool_reference`, OpenAI
  `tool_search`). Through this proxy, 40 tools marked deferred cost as many input tokens as 40
  loaded tools for both providers, so deferral was not reaching the model and those arms would
  have measured all-tools under another name. They need direct provider endpoints.
- More trials, more tasks, smaller models, multi-tool tasks, and an LLM judge for free-text
  arguments (paraphrases fail the exact call-correct match, which is why that column sits near
  50% for every arm).

## Tuning

Each default was changed only after measurement, and offline choices were made on a dev split
of the tasks and judged once on the held-out test split (`run-retrieval --split dev|test`, the
same stable hash as `calibrate`; catalogs still hold every task's gold tools).

### Fusion: convex, α = 0.8

Sixteen variants (weighted RRF, convex blends, embedding full descriptions, dense-only) were
compared on the dev split over MCPToolBench++ and five ToolRet subsets at N = 1,000 and 10,000.
Convex α = 0.8 was fixed as the single candidate before the test split ran. Against the previous
default (RRF, k = 60) on held-out tasks (macro over datasets, paired, 95% intervals):

| | N = 1,000 | N = 10,000 |
|---|---|---|
| R@5 | +0.4 pts [−0.8, +1.5] | **+2.4 [+1.1, +4.0]** |
| nDCG@10 | **+1.5 [+0.4, +2.7]** | **+2.1 [+1.2, +3.0]** |

The R@5 gain at N = 1,000 is not significant, which missed the rule set before looking; no
interval showed a loss, and it was adopted on that evidence. Reports:
`retrieval-20261106-075021-045` (dev), `retrieval-20261106-075916-285` (test).

### Cards per search: at least 10

In the loop at N = 1,000 (50 tasks), 10 cards instead of 5 lifted Sonnet 5.5 by +10 points
[+2, +20] (5 wins, 0 losses) for about 540 more input tokens per task; 20 cards added nothing,
and N = 200 didn't change. GPT-5.4 set `limit: 5` itself on most searches, so the default became
a minimum a model may raise but not lower; with it, GPT-5.4 changed by +2 points [−6, +12].
These runs ranked with RRF (the harness bug above), the same for every arm. Reports:
`llm-20261106-081530-833`, `llm-20261106-085453-784`.

### Reranker: measured, not shipped

An embedded cross-encoder (ms-marco-MiniLM-L6-v2, int8, on the same ONNX Runtime and tokenizer)
re-scored the top 50 hybrid hits. Alone it lost to convex hybrid everywhere (dev R@5 −2.7 and
−3.8 points at N = 1,000 and 10,000), as ToolRet found for rerankers in tool retrieval. The
pre-registered blend (0.3 reranker, 0.7 hybrid) gained on the held-out split — R@5 +1.1 [+0.2,
+2.1] at 1,000 and +1.0 [−0.2, +2.3] at 10,000; nDCG@10 +1.3 and +0.9 — but added about 60 ms
median (about 100 ms p95) per search, where search itself takes 1–13 ms, and would need a 23 MB
model artifact. It was not shipped; the experiment stays in the harness (`catalog-hybrid:rerank=…`).

### Confidence preset

The bge preset (0.6, 0.6, 0.90, 0.65, 0.05) was checked on the convex ranking
(`calibrate-20261106-092548-390`, 2,409 held-out queries over N = 1,000 and all tools):

| preset | right when HIGH | misses flagged LOW | hits flagged LOW |
|---|---|---|---|
| **current (shipped)** | **83.2%** | 41.3% | **19.5%** |
| previous (1.0, 0.9, 0.80, 0.65) | 78.1% | 43.1% | 20.6% |
| the convex run's own suggestion | 83.2% | 39.9% | 19.4% |

**Most misses are still not flagged** (about 59%): the fallback reduces silent omission; it
doesn't eliminate it.

## Retrieval

### Setup

| | |
|---|---|
| Code | Commit `7d1a6149d4cb8e7a0c5dfde5dde7a6e8770233b3` (clean at start; run ids `retrieval-20261106-180538-888` and `-183153-474`) |
| MCPToolBench++ | [mcp-tool-bench/MCPToolBenchPP](https://github.com/mcp-tool-bench/MCPToolBenchPP) at `2bd35e58ed5ebe79bbb12df0f9919a34ed2cb037`: 1,509 tasks over 87 real MCP tools (MIT) |
| ToolRet | [mangopy/ToolRet-Queries](https://huggingface.co/datasets/mangopy/ToolRet-Queries) `b8c76ad3`, [ToolRet-Tools](https://huggingface.co/datasets/mangopy/ToolRet-Tools) `e06c38c7`: 44,453 tools. The data cards carry no license tag, so the data is downloaded locally and never redistributed |
| Catalogs | All gold tools for the tasks, plus seeded ToolRet distractors up to N. "all" means the whole deduplicated corpus (about 44.4k); 87 means MCPToolBench++ against its own tools |
| Retrievers | Real `CapabilityCatalog.search` calls, fallback off: BM25 keyword-only; dense, which ranks by vector similarity only (not a product mode); hybrid with bge-small-en-v1.5-q and the default convex fusion; hybrid with the previous RRF fusion, for reference |
| Metrics | Recall@k, nDCG@10, MRR, and Completeness@10 (all gold tools in the top 10), as in ToolRet's `pytrec_eval` usage. Brackets are 95% bootstrap intervals over tasks |
| Note | These tables score **all** tasks, including the dev half used to choose the fusion; the held-out comparison above is the evidence for that choice |

### What the numbers say

- **The right tool is usually in the top 5 for small, realistic catalogs, but not always.** On
  MCPToolBench++'s 87 MCP tools, hybrid search puts the gold tool in the top 5 for 86.5% of tasks
  and ranks it first for 68.5%.
- **Hybrid beats keyword-only and dense-only** on MCPToolBench++ R@5 at every size, and convex
  fusion is at or above the previous RRF on R@5 at every size there; on the ToolRet subsets the
  ordering varies by subset; see the tables.
- **Quality drops as the catalog grows.** On MCPToolBench++, hybrid R@5 falls from 0.865 (87
  tools) to 0.720 (1,000), 0.604 (10,000) and 0.523 (about 44k). With the previous RRF fusion,
  the gold tool was in the top 50 for 93% of tasks at N = 1,000 (R@5 0.700), so the loss is
  ranking more than candidate generation.
  Larger fleets need tighter filters (sources, protocols, scopes) and the fallback.
- **Some query styles are hard for every retriever.** ToolLens queries describe goals that need
  several tools; completeness stays low at every size, which matches ToolRet's finding that tool
  retrieval is hard.

### Results by dataset

#### mcptoolbench

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 87 | BM25 (keyword-only) | 0.616 | 0.779 [0.758, 0.799] | 0.832 [0.814, 0.850] | 0.727 | 0.695 | 83.2% |
| 87 | dense (bge-small) | 0.610 | 0.839 [0.820, 0.858] | 0.911 [0.897, 0.925] | 0.756 | 0.712 | 91.1% |
| 87 | **hybrid, convex 0.8 (default)** | 0.685 | 0.865 [0.848, 0.881] | 0.915 [0.901, 0.928] | 0.798 | 0.765 | 91.5% |
| 87 | hybrid, RRF k = 60 (previous default) | 0.685 | 0.863 [0.846, 0.880] | 0.898 [0.882, 0.913] | 0.795 | 0.767 | 89.8% |
| 87 | random | 0.009 | 0.058 [0.046, 0.070] | 0.119 [0.103, 0.136] | 0.052 | 0.056 | 11.9% |
| 87 | oracle | 1.000 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 1,000 | BM25 (keyword-only) | 0.495 | 0.648 [0.625, 0.673] | 0.700 [0.678, 0.722] | 0.601 | 0.573 | 70.0% |
| 1,000 | dense (bge-small) | 0.503 | 0.693 [0.671, 0.716] | 0.781 [0.759, 0.801] | 0.633 | 0.595 | 78.1% |
| 1,000 | **hybrid, convex 0.8 (default)** | 0.569 | 0.720 [0.698, 0.743] | 0.772 [0.751, 0.792] | 0.666 | 0.642 | 77.2% |
| 1,000 | hybrid, RRF k = 60 (previous default) | 0.564 | 0.700 [0.676, 0.724] | 0.756 [0.734, 0.778] | 0.655 | 0.631 | 75.6% |
| 1,000 | random | 0.001 | 0.003 [0.001, 0.007] | 0.011 [0.007, 0.017] | 0.004 | 0.005 | 1.1% |
| 1,000 | oracle | 1.000 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.455 | 0.556 [0.532, 0.581] | 0.601 [0.577, 0.625] | 0.524 | 0.504 | 60.1% |
| 10,000 | dense (bge-small) | 0.433 | 0.569 [0.543, 0.594] | 0.636 [0.610, 0.658] | 0.526 | 0.499 | 63.6% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.477 | 0.604 [0.579, 0.628] | 0.663 [0.640, 0.687] | 0.563 | 0.538 | 66.3% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.470 | 0.600 [0.576, 0.624] | 0.640 [0.616, 0.664] | 0.553 | 0.532 | 64.0% |
| 10,000 | random | 0.001 | 0.001 [0.000, 0.002] | 0.001 [0.000, 0.003] | 0.001 | 0.001 | 0.1% |
| 10,000 | oracle | 1.000 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,466 | BM25 (keyword-only) | 0.398 | 0.500 [0.474, 0.525] | 0.536 [0.512, 0.561] | 0.466 | 0.448 | 53.6% |
| 44,466 | dense (bge-small) | 0.377 | 0.481 [0.455, 0.507] | 0.542 [0.516, 0.567] | 0.451 | 0.430 | 54.2% |
| 44,466 | **hybrid, convex 0.8 (default)** | 0.435 | 0.523 [0.498, 0.547] | 0.570 [0.544, 0.594] | 0.498 | 0.482 | 57.0% |
| 44,466 | hybrid, RRF k = 60 (previous default) | 0.426 | 0.521 [0.496, 0.545] | 0.573 [0.547, 0.597] | 0.494 | 0.475 | 57.3% |
| 44,466 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.000 | 0.0% |
| 44,466 | oracle | 1.000 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

#### toolret:toolbench

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 10,000 | BM25 (keyword-only) | 0.218 | 0.513 [0.490, 0.536] | 0.602 [0.579, 0.625] | 0.510 | 0.592 | 41.4% |
| 10,000 | dense (bge-small) | 0.202 | 0.467 [0.445, 0.490] | 0.554 [0.532, 0.577] | 0.466 | 0.542 | 36.6% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.234 | 0.539 [0.516, 0.562] | 0.621 [0.599, 0.643] | 0.534 | 0.612 | 43.5% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.226 | 0.527 [0.505, 0.550] | 0.625 [0.603, 0.647] | 0.529 | 0.600 | 43.5% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.001] | 0.002 [0.000, 0.003] | 0.001 | 0.002 | 0.0% |
| 10,000 | oracle | 0.457 | 0.999 [0.998, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,392 | BM25 (keyword-only) | 0.113 | 0.348 [0.327, 0.368] | 0.454 [0.431, 0.476] | 0.340 | 0.390 | 25.9% |
| 44,392 | dense (bge-small) | 0.106 | 0.317 [0.297, 0.338] | 0.412 [0.390, 0.436] | 0.308 | 0.354 | 23.0% |
| 44,392 | **hybrid, convex 0.8 (default)** | 0.119 | 0.382 [0.361, 0.405] | 0.477 [0.453, 0.500] | 0.360 | 0.407 | 28.7% |
| 44,392 | hybrid, RRF k = 60 (previous default) | 0.117 | 0.379 [0.358, 0.401] | 0.474 [0.451, 0.497] | 0.357 | 0.403 | 28.3% |
| 44,392 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.001] | 0.000 | 0.000 | 0.0% |
| 44,392 | oracle | 0.457 | 0.999 [0.998, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

#### toolret:toollens

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.142 | 0.263 [0.229, 0.295] | 0.325 [0.289, 0.359] | 0.289 | 0.432 | 9.6% |
| 1,000 | dense (bge-small) | 0.139 | 0.270 [0.238, 0.301] | 0.371 [0.334, 0.406] | 0.306 | 0.433 | 13.4% |
| 1,000 | **hybrid, convex 0.8 (default)** | 0.168 | 0.311 [0.278, 0.342] | 0.390 [0.353, 0.425] | 0.346 | 0.506 | 12.1% |
| 1,000 | hybrid, RRF k = 60 (previous default) | 0.161 | 0.300 [0.267, 0.334] | 0.376 [0.340, 0.410] | 0.333 | 0.489 | 11.5% |
| 1,000 | random | 0.000 | 0.010 [0.003, 0.019] | 0.013 [0.005, 0.023] | 0.007 | 0.012 | 0.3% |
| 1,000 | oracle | 0.430 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.071 | 0.137 [0.113, 0.162] | 0.169 [0.142, 0.197] | 0.149 | 0.239 | 3.5% |
| 10,000 | dense (bge-small) | 0.069 | 0.141 [0.115, 0.167] | 0.179 [0.151, 0.209] | 0.152 | 0.237 | 4.5% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.076 | 0.163 [0.137, 0.191] | 0.206 [0.177, 0.235] | 0.175 | 0.274 | 5.1% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.083 | 0.163 [0.136, 0.191] | 0.205 [0.176, 0.234] | 0.177 | 0.271 | 4.8% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.001 | 0.0% |
| 10,000 | oracle | 0.430 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,393 | BM25 (keyword-only) | 0.020 | 0.085 [0.065, 0.107] | 0.109 [0.087, 0.134] | 0.081 | 0.119 | 2.9% |
| 44,393 | dense (bge-small) | 0.033 | 0.087 [0.066, 0.110] | 0.107 [0.084, 0.131] | 0.086 | 0.131 | 2.9% |
| 44,393 | **hybrid, convex 0.8 (default)** | 0.032 | 0.102 [0.080, 0.125] | 0.129 [0.104, 0.154] | 0.095 | 0.141 | 3.8% |
| 44,393 | hybrid, RRF k = 60 (previous default) | 0.032 | 0.098 [0.075, 0.121] | 0.130 [0.105, 0.156] | 0.098 | 0.146 | 3.5% |
| 44,393 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.000 | 0.0% |
| 44,393 | oracle | 0.430 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

#### toolret:metatool

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.347 | 0.448 [0.380, 0.515] | 0.535 [0.468, 0.600] | 0.437 | 0.424 | 50.5% |
| 1,000 | dense (bge-small) | 0.415 | 0.593 [0.525, 0.655] | 0.672 [0.605, 0.735] | 0.544 | 0.524 | 64.5% |
| 1,000 | **hybrid, convex 0.8 (default)** | 0.427 | 0.657 [0.590, 0.720] | 0.698 [0.632, 0.757] | 0.575 | 0.556 | 67.0% |
| 1,000 | hybrid, RRF k = 60 (previous default) | 0.372 | 0.600 [0.532, 0.663] | 0.660 [0.595, 0.723] | 0.528 | 0.506 | 63.5% |
| 1,000 | random | 0.000 | 0.005 [0.000, 0.015] | 0.005 [0.000, 0.015] | 0.002 | 0.004 | 0.5% |
| 1,000 | oracle | 0.948 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.212 | 0.345 [0.282, 0.412] | 0.375 [0.310, 0.443] | 0.296 | 0.280 | 35.5% |
| 10,000 | dense (bge-small) | 0.297 | 0.420 [0.355, 0.487] | 0.463 [0.395, 0.530] | 0.380 | 0.371 | 44.5% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.297 | 0.422 [0.355, 0.487] | 0.497 [0.427, 0.565] | 0.391 | 0.372 | 47.0% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.260 | 0.378 [0.310, 0.440] | 0.422 [0.355, 0.487] | 0.340 | 0.333 | 40.0% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.001 | 0.0% |
| 10,000 | oracle | 0.948 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,392 | BM25 (keyword-only) | 0.165 | 0.233 [0.175, 0.292] | 0.282 [0.223, 0.345] | 0.213 | 0.200 | 27.5% |
| 44,392 | dense (bge-small) | 0.235 | 0.320 [0.258, 0.385] | 0.362 [0.297, 0.430] | 0.293 | 0.280 | 35.0% |
| 44,392 | **hybrid, convex 0.8 (default)** | 0.210 | 0.307 [0.245, 0.372] | 0.345 [0.278, 0.408] | 0.277 | 0.266 | 33.5% |
| 44,392 | hybrid, RRF k = 60 (previous default) | 0.180 | 0.287 [0.223, 0.350] | 0.343 [0.275, 0.405] | 0.253 | 0.235 | 33.5% |
| 44,392 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.000 | 0.0% |
| 44,392 | oracle | 0.948 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

#### toolret:apibank

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.333 | 0.533 [0.455, 0.611] | 0.619 [0.544, 0.686] | 0.514 | 0.586 | 41.6% |
| 1,000 | dense (bge-small) | 0.417 | 0.570 [0.498, 0.642] | 0.631 [0.559, 0.700] | 0.569 | 0.691 | 41.6% |
| 1,000 | **hybrid, convex 0.8 (default)** | 0.426 | 0.587 [0.509, 0.660] | 0.658 [0.587, 0.724] | 0.593 | 0.713 | 43.6% |
| 1,000 | hybrid, RRF k = 60 (previous default) | 0.414 | 0.578 [0.499, 0.650] | 0.619 [0.544, 0.686] | 0.566 | 0.676 | 40.6% |
| 1,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.004 | 0.0% |
| 1,000 | oracle | 0.710 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.258 | 0.403 [0.318, 0.487] | 0.441 [0.356, 0.524] | 0.375 | 0.458 | 31.7% |
| 10,000 | dense (bge-small) | 0.235 | 0.407 [0.326, 0.486] | 0.471 [0.390, 0.549] | 0.378 | 0.447 | 30.7% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.265 | 0.459 [0.377, 0.539] | 0.522 [0.443, 0.599] | 0.426 | 0.503 | 35.6% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.270 | 0.433 [0.352, 0.515] | 0.511 [0.430, 0.587] | 0.416 | 0.497 | 34.7% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.001 | 0.0% |
| 10,000 | oracle | 0.710 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,392 | BM25 (keyword-only) | 0.210 | 0.273 [0.201, 0.348] | 0.342 [0.263, 0.424] | 0.290 | 0.360 | 23.8% |
| 44,392 | dense (bge-small) | 0.160 | 0.280 [0.205, 0.358] | 0.359 [0.282, 0.438] | 0.272 | 0.324 | 23.8% |
| 44,392 | **hybrid, convex 0.8 (default)** | 0.188 | 0.318 [0.238, 0.398] | 0.402 [0.325, 0.481] | 0.319 | 0.381 | 26.7% |
| 44,392 | hybrid, RRF k = 60 (previous default) | 0.220 | 0.303 [0.225, 0.383] | 0.385 [0.304, 0.468] | 0.322 | 0.401 | 26.7% |
| 44,392 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.000 | 0.0% |
| 44,392 | oracle | 0.710 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

#### toolret:apigen

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.692 | 0.910 [0.894, 0.926] | 0.944 [0.931, 0.957] | 0.872 | 0.874 | 92.0% |
| 1,000 | dense (bge-small) | 0.672 | 0.887 [0.871, 0.903] | 0.925 [0.912, 0.939] | 0.847 | 0.862 | 87.6% |
| 1,000 | **hybrid, convex 0.8 (default)** | 0.713 | 0.922 [0.908, 0.936] | 0.951 [0.940, 0.962] | 0.887 | 0.898 | 91.7% |
| 1,000 | hybrid, RRF k = 60 (previous default) | 0.704 | 0.922 [0.908, 0.936] | 0.954 [0.942, 0.965] | 0.884 | 0.891 | 92.5% |
| 1,000 | random | 0.000 | 0.001 [0.000, 0.002] | 0.005 [0.002, 0.009] | 0.002 | 0.004 | 0.3% |
| 1,000 | oracle | 0.850 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.517 | 0.759 [0.735, 0.784] | 0.816 [0.794, 0.838] | 0.700 | 0.707 | 75.3% |
| 10,000 | dense (bge-small) | 0.473 | 0.702 [0.677, 0.727] | 0.777 [0.755, 0.799] | 0.647 | 0.651 | 69.5% |
| 10,000 | **hybrid, convex 0.8 (default)** | 0.539 | 0.777 [0.754, 0.801] | 0.836 [0.816, 0.856] | 0.720 | 0.727 | 76.8% |
| 10,000 | hybrid, RRF k = 60 (previous default) | 0.500 | 0.777 [0.756, 0.801] | 0.843 [0.824, 0.864] | 0.708 | 0.705 | 77.7% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.000 | 0.0% |
| 10,000 | oracle | 0.850 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,392 | BM25 (keyword-only) | 0.366 | 0.621 [0.595, 0.650] | 0.704 [0.680, 0.731] | 0.557 | 0.552 | 62.9% |
| 44,392 | dense (bge-small) | 0.320 | 0.554 [0.527, 0.582] | 0.635 [0.608, 0.662] | 0.488 | 0.480 | 56.4% |
| 44,392 | **hybrid, convex 0.8 (default)** | 0.374 | 0.630 [0.604, 0.658] | 0.717 [0.693, 0.742] | 0.561 | 0.552 | 64.2% |
| 44,392 | hybrid, RRF k = 60 (previous default) | 0.349 | 0.629 [0.603, 0.657] | 0.726 [0.701, 0.752] | 0.555 | 0.540 | 65.1% |
| 44,392 | random | 0.001 | 0.001 [0.000, 0.002] | 0.001 [0.000, 0.002] | 0.001 | 0.001 | 0.0% |
| 44,392 | oracle | 0.850 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |

## Reproduce

```bash
python3 evals/tool-discovery/scripts/fetch_mcptoolbench.py
python3 evals/tool-discovery/scripts/fetch_toolret.py
./gradlew :evals:tool-discovery:run --args="run-retrieval \
  --datasets mcptoolbench,toolret:toolbench,toolret:toollens,toolret:metatool,toolret:apibank,toolret:apigen \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid:fusion=rrf,random,oracle \
  --k 1,3,5,10 --catalog-sizes 1000,10000,all"
./gradlew :evals:tool-discovery:run --args="run-retrieval --datasets mcptoolbench --distractors own \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid:fusion=rrf,random,oracle \
  --k 1,3,5,10 --catalog-sizes all"
```

Add `--split dev` or `--split test` to score one half of the tasks, and retriever variants such as
`catalog-hybrid:fusion=convex:alpha=0.7` to compare tunings side by side. Reports go to
`evals/tool-discovery/build/eval-results/<run-id>/`, as `report.md`, `summary.json` and
`calls.jsonl`. Each report header records the commit, the dataset revisions and hashes, the
settings, and the interval method.

The model-in-the-loop run (needs keys; about $30 at list price):

```bash
source ~/.protoweft-eval.env   # ANTHROPIC_/OPENAI_ API keys and, for a proxy, *_BASE_URL
cd evals/tool-discovery && cp prices.example.json prices.json   # fill in list prices
../../gradlew installDist
JAVA_OPTS=-Xmx8g build/install/tool-discovery-eval/bin/tool-discovery-eval run-llm \
  --budget-usd 100 --run-cap-usd 80 --max-retries 8 --prices prices.json \
  --arm all-tools --arm top-k:k=5,retriever=catalog-hybrid --arm generic-search \
  --models anthropic:<model> --models openai:<model> \
  --tasks 50 --catalog-sizes 50,200,1000 --distractors both
```
