# Discovery evaluation

This page reports what has been **measured** about tool discovery, and lists what has not been
measured yet. Every number below comes from the harness in `evals/tool-discovery`, run on the
stated commit and dataset revisions. Reproduce it with the commands at the end.

!!! danger "Not yet measured: effects on the model"
    These results measure **retrieval**: whether the right tool is among the few that discovery
    would show a model. They don't measure what a model then does. Tool-selection accuracy,
    wrong-tool rate, call correctness, discovery calls per task, and context tokens against the
    all-tools baseline need the model-in-the-loop arms (`run-llm`), which need API keys and
    haven't been run yet. **Nothing here shows that discovery leaves accuracy unchanged.**

## Setup

| | |
|---|---|
| Commits | `fdbf3d927a364b8854b8b2cf635c9c8aa5033f00` (N = 1,000, 10,000 and all), `611d2952ed470e5c6704a19574b78381238a7264` (MCPToolBench++ against its own 87 tools). Both are clean working trees, and both have the same discovery code |
| MCPToolBench++ | [mcp-tool-bench/MCPToolBenchPP](https://github.com/mcp-tool-bench/MCPToolBenchPP) at `2bd35e58ed5ebe79bbb12df0f9919a34ed2cb037`: 1,509 tasks over 87 real MCP tools (MIT) |
| ToolRet | [mangopy/ToolRet-Queries](https://huggingface.co/datasets/mangopy/ToolRet-Queries) `b8c76ad3`, [ToolRet-Tools](https://huggingface.co/datasets/mangopy/ToolRet-Tools) `e06c38c7`: 44,453 tools. The data cards carry no license tag, so the data is downloaded locally and never redistributed |
| Catalogs | All gold tools for the tasks, plus seeded ToolRet distractors up to N. "all" means the whole deduplicated corpus (about 44.4k) |
| Retrievers | Real `CapabilityCatalog.search` calls, fallback off: BM25 keyword-only; dense, which ranks by vector similarity only (not a product mode); hybrid with bge-small-en-v1.5-q (the default with the offline embedder); hybrid with all-MiniLM-L6-v2-q |
| Metrics | Recall@k, nDCG@10, MRR, and Completeness@10 (all gold tools in the top 10), as in ToolRet's `pytrec_eval` usage. Brackets are 95% bootstrap intervals over tasks |
| Run time | About 17 minutes on an Apple M4 Pro, CPU only, with embeddings cached |

## What the numbers say

- **The right tool is usually in the top 5 for small, realistic catalogs, but not always.** On
  MCPToolBench++'s 87 MCP tools, hybrid search puts the gold tool in the top 5 for 86.3% of tasks
  and ranks it first for 68.5%.
- **Hybrid beats keyword-only.** On MCPToolBench++ at every catalog size measured, hybrid's R@5
  interval lies above or overlaps BM25's, and R@1 and MRR are higher at every size. On the ToolRet
  subsets the ordering varies by subset; see the tables.
- **Quality drops as the catalog grows.** On MCPToolBench++, hybrid R@5 falls from 0.863 (87
  tools) to 0.700 (1,000), 0.600 (10,000) and 0.521 (about 44k). Larger fleets need tighter filters
  (sources, protocols, scopes) and the fallback.
- **Some query styles are hard for every retriever.** ToolLens queries describe goals that need
  several tools. Completeness@10 is at most 13.4% at N = 1,000 and in single digits from
  N = 10,000, which matches ToolRet's finding that tool retrieval is hard.

## Confidence and fallback

The fallback broadens a search when confidence is LOW. Calibration measured, on a held-out test
split (3,743 queries over catalogs of 1,000 and all tools), how often each retriever's
**shipped default** judges LOW when the gold tool is missing from the top 5, and how often it
broadens needlessly:

| retriever | test queries | HIGH share | P(hit@1 \| HIGH) | P(LOW \| miss@5) | P(LOW \| hit@5) |
|---|---|---|---|---|---|
| keyword-only (BM25) | 3743 | 12.8% | 76.8% | 57.8% | 35.7% |
| hybrid, bge-small (default with the offline embedder) | 3743 | 11.6% | 84.8% | 45.0% | 18.1% |
| hybrid, MiniLM | 3743 | 9.6% | 86.6% | 40.9% | 19.3% |

- The hybrid presets (`Confidence.Policy.BGE_SMALL_EN_V15` and `ALL_MINILM_L6_V2`) come from this
  calibration. With the earlier provisional thresholds, bge hybrid flagged only 1.4% of misses
  as LOW, so the fallback would almost never have fired.
- The keyword-only default was kept rather than tuned. The tuned thresholds would cut false
  alarms, but they would also cut the share of misses caught from 57.8% to 17.9%.
- **More than half of the misses are still not flagged** in hybrid mode. The fallback reduces
  silent omission; it doesn't eliminate it.
- Report: `calibrate-20261105-113046-132-fdbf3d927a-dirty-1`, at commit `fdbf3d9`. The working
  tree was dirty only in Markdown files, and no source changes were present.

## Retrieval results


#### mcptoolbench

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 87 | BM25 (keyword-only) | 0.616 | 0.779 [0.758, 0.799] | 0.832 [0.814, 0.850] | 0.727 | 0.695 | 83.2% |
| 87 | dense (bge-small) | 0.610 | 0.839 [0.820, 0.858] | 0.911 [0.897, 0.925] | 0.756 | 0.712 | 91.1% |
| 87 | hybrid (bge-small, default) | 0.685 | 0.863 [0.846, 0.880] | 0.898 [0.882, 0.913] | 0.795 | 0.767 | 89.8% |
| 87 | hybrid (MiniLM) | 0.628 | 0.838 [0.819, 0.857] | 0.867 [0.850, 0.885] | 0.754 | 0.719 | 86.7% |
| 87 | random | 0.009 | 0.058 [0.046, 0.070] | 0.119 [0.103, 0.136] | 0.052 | 0.056 | 11.9% |
| 87 | oracle | 1.000 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 1,000 | BM25 (keyword-only) | 0.495 | 0.648 [0.625, 0.673] | 0.700 [0.678, 0.722] | 0.601 | 0.573 | 70.0% |
| 1,000 | dense (bge-small) | 0.503 | 0.693 [0.671, 0.716] | 0.781 [0.759, 0.801] | 0.633 | 0.595 | 78.1% |
| 1,000 | hybrid (bge-small, default) | 0.564 | 0.700 [0.676, 0.724] | 0.756 [0.734, 0.778] | 0.655 | 0.631 | 75.6% |
| 1,000 | hybrid (MiniLM) | 0.496 | 0.687 [0.663, 0.711] | 0.751 [0.729, 0.772] | 0.620 | 0.584 | 75.1% |
| 10,000 | BM25 (keyword-only) | 0.455 | 0.556 [0.532, 0.581] | 0.601 [0.577, 0.625] | 0.524 | 0.504 | 60.1% |
| 10,000 | dense (bge-small) | 0.433 | 0.569 [0.543, 0.594] | 0.636 [0.610, 0.658] | 0.526 | 0.499 | 63.6% |
| 10,000 | hybrid (bge-small, default) | 0.470 | 0.600 [0.576, 0.624] | 0.640 [0.616, 0.664] | 0.553 | 0.532 | 64.0% |
| 10,000 | hybrid (MiniLM) | 0.402 | 0.549 [0.525, 0.575] | 0.608 [0.583, 0.632] | 0.498 | 0.470 | 60.8% |
| 44,466 | BM25 (keyword-only) | 0.398 | 0.500 [0.474, 0.525] | 0.536 [0.512, 0.561] | 0.466 | 0.448 | 53.6% |
| 44,466 | dense (bge-small) | 0.377 | 0.481 [0.455, 0.507] | 0.542 [0.516, 0.567] | 0.451 | 0.430 | 54.2% |
| 44,466 | hybrid (bge-small, default) | 0.426 | 0.521 [0.496, 0.545] | 0.573 [0.547, 0.597] | 0.494 | 0.475 | 57.3% |
| 44,466 | hybrid (MiniLM) | 0.356 | 0.470 [0.445, 0.494] | 0.529 [0.505, 0.554] | 0.434 | 0.411 | 52.9% |

#### toolret:toolbench

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 10,000 | BM25 (keyword-only) | 0.218 | 0.513 [0.490, 0.536] | 0.602 [0.579, 0.625] | 0.510 | 0.592 | 41.4% |
| 10,000 | dense (bge-small) | 0.202 | 0.467 [0.445, 0.490] | 0.554 [0.532, 0.577] | 0.466 | 0.542 | 36.6% |
| 10,000 | hybrid (bge-small, default) | 0.226 | 0.527 [0.505, 0.550] | 0.625 [0.603, 0.647] | 0.529 | 0.600 | 43.5% |
| 10,000 | hybrid (MiniLM) | 0.210 | 0.511 [0.488, 0.533] | 0.606 [0.584, 0.629] | 0.504 | 0.571 | 42.2% |
| 10,000 | random | 0.000 | 0.000 [0.000, 0.001] | 0.002 [0.000, 0.003] | 0.001 | 0.002 | 0.0% |
| 10,000 | oracle | 0.457 | 0.999 [0.998, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 44,392 | BM25 (keyword-only) | 0.113 | 0.348 [0.327, 0.368] | 0.454 [0.431, 0.476] | 0.340 | 0.390 | 25.9% |
| 44,392 | dense (bge-small) | 0.106 | 0.317 [0.297, 0.338] | 0.412 [0.390, 0.436] | 0.308 | 0.354 | 23.0% |
| 44,392 | hybrid (bge-small, default) | 0.117 | 0.379 [0.358, 0.401] | 0.474 [0.451, 0.497] | 0.357 | 0.403 | 28.3% |
| 44,392 | hybrid (MiniLM) | 0.107 | 0.348 [0.327, 0.369] | 0.454 [0.432, 0.477] | 0.335 | 0.379 | 26.5% |

#### toolret:toollens

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.142 | 0.263 [0.229, 0.295] | 0.325 [0.289, 0.359] | 0.289 | 0.432 | 9.6% |
| 1,000 | dense (bge-small) | 0.139 | 0.270 [0.238, 0.301] | 0.371 [0.334, 0.406] | 0.306 | 0.433 | 13.4% |
| 1,000 | hybrid (bge-small, default) | 0.161 | 0.300 [0.267, 0.334] | 0.376 [0.340, 0.410] | 0.333 | 0.489 | 11.5% |
| 1,000 | hybrid (MiniLM) | 0.131 | 0.274 [0.241, 0.307] | 0.351 [0.315, 0.385] | 0.297 | 0.422 | 10.2% |
| 1,000 | random | 0.000 | 0.010 [0.003, 0.019] | 0.013 [0.005, 0.023] | 0.007 | 0.012 | 0.3% |
| 1,000 | oracle | 0.430 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.071 | 0.137 [0.113, 0.162] | 0.169 [0.142, 0.197] | 0.149 | 0.239 | 3.5% |
| 10,000 | dense (bge-small) | 0.069 | 0.141 [0.115, 0.167] | 0.179 [0.151, 0.209] | 0.152 | 0.237 | 4.5% |
| 10,000 | hybrid (bge-small, default) | 0.083 | 0.163 [0.136, 0.191] | 0.205 [0.176, 0.234] | 0.177 | 0.271 | 4.8% |
| 10,000 | hybrid (MiniLM) | 0.049 | 0.132 [0.107, 0.157] | 0.167 [0.140, 0.194] | 0.131 | 0.205 | 3.8% |
| 44,393 | BM25 (keyword-only) | 0.020 | 0.085 [0.065, 0.107] | 0.109 [0.087, 0.134] | 0.081 | 0.119 | 2.9% |
| 44,393 | dense (bge-small) | 0.033 | 0.087 [0.066, 0.110] | 0.107 [0.084, 0.131] | 0.086 | 0.131 | 2.9% |
| 44,393 | hybrid (bge-small, default) | 0.032 | 0.098 [0.075, 0.121] | 0.130 [0.105, 0.156] | 0.098 | 0.146 | 3.5% |
| 44,393 | hybrid (MiniLM) | 0.016 | 0.062 [0.044, 0.080] | 0.098 [0.076, 0.122] | 0.066 | 0.102 | 2.2% |

#### toolret:metatool

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.347 | 0.448 [0.380, 0.515] | 0.535 [0.468, 0.600] | 0.437 | 0.424 | 50.5% |
| 1,000 | dense (bge-small) | 0.415 | 0.593 [0.525, 0.655] | 0.672 [0.605, 0.735] | 0.544 | 0.524 | 64.5% |
| 1,000 | hybrid (bge-small, default) | 0.372 | 0.600 [0.532, 0.663] | 0.660 [0.595, 0.723] | 0.528 | 0.506 | 63.5% |
| 1,000 | hybrid (MiniLM) | 0.380 | 0.590 [0.522, 0.657] | 0.680 [0.615, 0.740] | 0.531 | 0.499 | 65.5% |
| 1,000 | random | 0.000 | 0.005 [0.000, 0.015] | 0.005 [0.000, 0.015] | 0.002 | 0.004 | 0.5% |
| 1,000 | oracle | 0.948 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.212 | 0.345 [0.282, 0.412] | 0.375 [0.310, 0.443] | 0.296 | 0.280 | 35.5% |
| 10,000 | dense (bge-small) | 0.297 | 0.420 [0.355, 0.487] | 0.463 [0.395, 0.530] | 0.380 | 0.371 | 44.5% |
| 10,000 | hybrid (bge-small, default) | 0.260 | 0.378 [0.310, 0.440] | 0.422 [0.355, 0.487] | 0.340 | 0.333 | 40.0% |
| 10,000 | hybrid (MiniLM) | 0.270 | 0.388 [0.320, 0.453] | 0.443 [0.372, 0.507] | 0.358 | 0.347 | 42.0% |
| 44,392 | BM25 (keyword-only) | 0.165 | 0.233 [0.175, 0.292] | 0.282 [0.223, 0.345] | 0.213 | 0.200 | 27.5% |
| 44,392 | dense (bge-small) | 0.235 | 0.320 [0.258, 0.385] | 0.362 [0.297, 0.430] | 0.293 | 0.280 | 35.0% |
| 44,392 | hybrid (bge-small, default) | 0.180 | 0.287 [0.223, 0.350] | 0.343 [0.275, 0.405] | 0.253 | 0.235 | 33.5% |
| 44,392 | hybrid (MiniLM) | 0.172 | 0.292 [0.232, 0.357] | 0.362 [0.297, 0.427] | 0.257 | 0.236 | 35.0% |

#### toolret:apibank

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.333 | 0.533 [0.455, 0.611] | 0.619 [0.544, 0.686] | 0.514 | 0.586 | 41.6% |
| 1,000 | dense (bge-small) | 0.417 | 0.570 [0.498, 0.642] | 0.631 [0.559, 0.700] | 0.569 | 0.691 | 41.6% |
| 1,000 | hybrid (bge-small, default) | 0.414 | 0.578 [0.499, 0.650] | 0.619 [0.544, 0.686] | 0.566 | 0.676 | 40.6% |
| 1,000 | hybrid (MiniLM) | 0.386 | 0.587 [0.509, 0.659] | 0.629 [0.555, 0.696] | 0.560 | 0.655 | 41.6% |
| 1,000 | random | 0.000 | 0.000 [0.000, 0.000] | 0.000 [0.000, 0.000] | 0.000 | 0.004 | 0.0% |
| 1,000 | oracle | 0.710 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.258 | 0.403 [0.318, 0.487] | 0.441 [0.356, 0.524] | 0.375 | 0.458 | 31.7% |
| 10,000 | dense (bge-small) | 0.235 | 0.407 [0.326, 0.486] | 0.471 [0.390, 0.549] | 0.378 | 0.447 | 30.7% |
| 10,000 | hybrid (bge-small, default) | 0.270 | 0.433 [0.352, 0.515] | 0.511 [0.430, 0.587] | 0.416 | 0.497 | 34.7% |
| 10,000 | hybrid (MiniLM) | 0.230 | 0.446 [0.365, 0.528] | 0.501 [0.425, 0.579] | 0.392 | 0.457 | 32.7% |
| 44,392 | BM25 (keyword-only) | 0.210 | 0.273 [0.201, 0.348] | 0.342 [0.263, 0.424] | 0.290 | 0.360 | 23.8% |
| 44,392 | dense (bge-small) | 0.160 | 0.280 [0.205, 0.358] | 0.359 [0.282, 0.438] | 0.272 | 0.324 | 23.8% |
| 44,392 | hybrid (bge-small, default) | 0.220 | 0.303 [0.225, 0.383] | 0.385 [0.304, 0.468] | 0.322 | 0.401 | 26.7% |
| 44,392 | hybrid (MiniLM) | 0.192 | 0.293 [0.218, 0.367] | 0.361 [0.277, 0.445] | 0.292 | 0.358 | 24.8% |

#### toolret:apigen

| N | retriever | R@1 | R@5 | R@10 | nDCG@10 | MRR | C@10 |
|---|---|---|---|---|---|---|---|
| 1,000 | BM25 (keyword-only) | 0.692 | 0.910 [0.894, 0.926] | 0.944 [0.931, 0.957] | 0.872 | 0.874 | 92.0% |
| 1,000 | dense (bge-small) | 0.672 | 0.887 [0.871, 0.903] | 0.925 [0.912, 0.939] | 0.847 | 0.862 | 87.6% |
| 1,000 | hybrid (bge-small, default) | 0.704 | 0.922 [0.908, 0.936] | 0.954 [0.942, 0.965] | 0.884 | 0.891 | 92.5% |
| 1,000 | hybrid (MiniLM) | 0.693 | 0.926 [0.912, 0.940] | 0.955 [0.943, 0.966] | 0.881 | 0.882 | 93.2% |
| 1,000 | random | 0.000 | 0.001 [0.000, 0.002] | 0.005 [0.002, 0.009] | 0.002 | 0.004 | 0.3% |
| 1,000 | oracle | 0.850 | 1.000 [1.000, 1.000] | 1.000 [1.000, 1.000] | 1.000 | 1.000 | 100.0% |
| 10,000 | BM25 (keyword-only) | 0.517 | 0.759 [0.735, 0.784] | 0.816 [0.794, 0.838] | 0.700 | 0.707 | 75.3% |
| 10,000 | dense (bge-small) | 0.473 | 0.702 [0.677, 0.727] | 0.777 [0.755, 0.799] | 0.647 | 0.651 | 69.5% |
| 10,000 | hybrid (bge-small, default) | 0.500 | 0.777 [0.756, 0.801] | 0.843 [0.824, 0.864] | 0.708 | 0.705 | 77.7% |
| 10,000 | hybrid (MiniLM) | 0.486 | 0.781 [0.759, 0.805] | 0.855 [0.836, 0.874] | 0.706 | 0.696 | 79.2% |
| 44,392 | BM25 (keyword-only) | 0.366 | 0.621 [0.595, 0.650] | 0.704 [0.680, 0.731] | 0.557 | 0.552 | 62.9% |
| 44,392 | dense (bge-small) | 0.320 | 0.554 [0.527, 0.582] | 0.635 [0.608, 0.662] | 0.488 | 0.480 | 56.4% |
| 44,392 | hybrid (bge-small, default) | 0.349 | 0.629 [0.603, 0.657] | 0.726 [0.701, 0.752] | 0.555 | 0.540 | 65.1% |
| 44,392 | hybrid (MiniLM) | 0.338 | 0.629 [0.602, 0.656] | 0.717 [0.693, 0.743] | 0.549 | 0.535 | 64.4% |


## Reproduce

```bash
python3 evals/tool-discovery/scripts/fetch_mcptoolbench.py
python3 evals/tool-discovery/scripts/fetch_toolret.py
./gradlew :evals:tool-discovery:run --args="run-retrieval \
  --datasets mcptoolbench,toolret:toolbench,toolret:toollens,toolret:metatool,toolret:apibank,toolret:apigen \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid-minilm,random,oracle \
  --k 1,3,5,10 --catalog-sizes 1000,10000,all"
./gradlew :evals:tool-discovery:run --args="run-retrieval --datasets mcptoolbench --distractors own \
  --retrievers catalog-bm25,catalog-dense,catalog-hybrid,catalog-hybrid-minilm,random,oracle \
  --k 1,3,5,10 --catalog-sizes all"
```

Reports go to `evals/tool-discovery/build/eval-results/<run-id>/`, as `report.md`,
`summary.json` and `calls.jsonl`. Each report header records the commit, the dataset revisions
and hashes, the settings, and the interval method.

## Still to measure

Running the model-in-the-loop arms (`run-llm`) against an all-tools baseline on Claude and GPT
will measure:

- selection accuracy and wrong-tool rate
- call correctness (an exact match on tool and arguments, the harness's proxy for task
  completion)
- discovery calls per task
- provider-reported input tokens

The arms are: all tools, top-k injected, the generic search tool (with and without inlined
schemas), and the native Anthropic and OpenAI adapters. See `evals/tool-discovery/README.md` for
commands and the cost guard. Results will be added here with their commits.
