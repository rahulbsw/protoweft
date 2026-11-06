#!/usr/bin/env python3
"""Macro-averaged retrieval metrics and paired bootstrap deltas from run-retrieval calls.jsonl files.

Standard library only. For every catalog size N:
  * macro average over datasets (each dataset's mean over its tasks, then the mean of those) of each
    metric, per retriever;
  * paired differences variant - baseline on the same tasks: tasks are resampled with replacement
    within each dataset (stratified), the per-dataset mean differences are macro-averaged, and the
    2.5 / 97.5 percentiles of --resamples resamples (fixed --seed) give a 95% interval;
  * per-query latency (median, p95) of the fields retrieveMs and rerankMs when present.

Usage:
  scripts/paired_bootstrap.py build/eval-results/<run>/calls.jsonl [more calls.jsonl ...]
      --baseline catalog-hybrid [--variants a,b,...] [--metrics recall@5,ndcg@10] [--json out.json]
"""
import argparse
import json
import math
import random
import sys
from collections import defaultdict


def load(paths):
    rows = []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            rows.extend(json.loads(line) for line in f if line.strip())
    return rows


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def percentile(sorted_xs, q):
    if not sorted_xs:
        return float("nan")
    rank = max(1, min(len(sorted_xs), math.ceil(q * len(sorted_xs))))
    return sorted_xs[rank - 1]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("calls", nargs="+")
    ap.add_argument("--baseline", default="catalog-hybrid")
    ap.add_argument("--variants", default="", help="comma-separated; default: every other retriever")
    ap.add_argument("--metrics", default="recall@5,ndcg@10")
    ap.add_argument("--resamples", type=int, default=2000)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--no-bootstrap", action="store_true")
    ap.add_argument("--json")
    args = ap.parse_args()

    rows = load(args.calls)
    metrics = [m.strip() for m in args.metrics.split(",") if m.strip()]
    # (N, retriever, dataset) -> taskId -> row
    cells = defaultdict(dict)
    retrievers, sizes = [], set()
    for r in rows:
        cells[(r["catalogSize"], r["retriever"], r["dataset"])][r["taskId"]] = r
        if r["retriever"] not in retrievers:
            retrievers.append(r["retriever"])
        sizes.add(r["catalogSize"])
    variants = [v for v in args.variants.split(",") if v] or [r for r in retrievers if r != args.baseline]
    if args.baseline not in retrievers:
        sys.exit("baseline %s not in %s" % (args.baseline, retrievers))
    out = {"macro": {}, "deltas": {}, "latency": {}}

    for n in sorted(sizes):
        datasets = sorted({d for (size, _, d) in cells if size == n})
        print("\n## N = %d (%d datasets: %s)\n" % (n, len(datasets), ", ".join(datasets)))
        print("| retriever | " + " | ".join("macro " + m for m in metrics) + " | queries |")
        print("|---|" + "---|" * (len(metrics) + 1))
        for name in [args.baseline] + variants:
            per = []
            for m in metrics:
                vals = [mean([t[m] for t in cells[(n, name, d)].values()]) for d in datasets if cells.get((n, name, d))]
                per.append(mean(vals) if len(vals) == len(datasets) else float("nan"))
            count = sum(len(cells.get((n, name, d), {})) for d in datasets)
            out["macro"]["%s|%d" % (name, n)] = dict(zip(metrics, per))
            print("| %s | %s | %d |" % (name, " | ".join("%.4f" % v for v in per), count))

        print("\nLatency (ms per query; retrieveMs = whole catalog search, rerankMs = reranking step):\n")
        print("| retriever | search median | search p95 | rerank median | rerank p95 |")
        print("|---|---|---|---|---|")
        for name in [args.baseline] + variants:
            line = [name]
            lat = {}
            for field in ("retrieveMs", "rerankMs"):
                xs = sorted(t[field] for d in datasets for t in cells.get((n, name, d), {}).values() if field in t)
                med, p95 = percentile(xs, 0.5), percentile(xs, 0.95)
                lat[field] = {"median": med, "p95": p95, "n": len(xs)}
                line += ["%.1f" % med if xs else "", "%.1f" % p95 if xs else ""]
            out["latency"]["%s|%d" % (name, n)] = lat
            print("| " + " | ".join(line) + " |")

        if args.no_bootstrap:
            continue
        print("\nPaired deltas vs %s (points; 95%% stratified bootstrap, %d resamples, seed %d):\n"
              % (args.baseline, args.resamples, args.seed))
        print("| variant | " + " | ".join("Δ " + m for m in metrics) + " |")
        print("|---|" + "---|" * len(metrics))
        for name in variants:
            diffs = {}  # dataset -> metric -> [diff per paired task]
            for d in datasets:
                base, var = cells.get((n, args.baseline, d), {}), cells.get((n, name, d), {})
                common = sorted(set(base) & set(var))
                if not common:
                    continue
                diffs[d] = {m: [var[t][m] - base[t][m] for t in common] for m in metrics}
            if len(diffs) != len(datasets):
                continue
            rng = random.Random(args.seed)
            boots = {m: [] for m in metrics}
            for _ in range(args.resamples):
                picks = {d: [rng.randrange(len(diffs[d][metrics[0]])) for _ in diffs[d][metrics[0]]] for d in datasets}
                for m in metrics:
                    boots[m].append(mean([mean([diffs[d][m][i] for i in picks[d]]) for d in datasets]))
            cellout = {}
            line = [name]
            for m in metrics:
                point = mean([mean(diffs[d][m]) for d in datasets])
                b = sorted(boots[m])
                lo, hi = b[int(0.025 * len(b))], b[min(len(b) - 1, int(0.975 * len(b)))]
                cellout[m] = {"delta": point, "lo": lo, "hi": hi}
                line.append("%+.1f [%+.1f, %+.1f]" % (100 * point, 100 * lo, 100 * hi))
            out["deltas"]["%s|%d" % (name, n)] = cellout
            print("| " + " | ".join(line) + " |")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=2)


if __name__ == "__main__":
    main()
