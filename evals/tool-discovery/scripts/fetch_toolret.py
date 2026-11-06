#!/usr/bin/env python3
"""Download ToolRet (queries + tool corpus) from Hugging Face into JSONL for the Java harness.

ToolRet: arXiv 2503.01763, https://github.com/mangopy/tool-retrieval-benchmark (code Apache-2.0).
The Hugging Face data cards (mangopy/ToolRet-Queries, mangopy/ToolRet-Tools) carry NO license tag,
so the data is fetched locally at evaluation time only and must never be committed. The default
output directory (evals/tool-discovery/build/data/toolret) is ignored by git via `build/`.

Two download methods:
  parquet  the dataset's parquet files at a pinned revision, read with pyarrow (pip install pyarrow)
  rows     the HF datasets-server /rows JSON API, standard library only; it serves the current
           main revision (the manifest records which sha that was)
`--method auto` (default) uses parquet when pyarrow is importable, else rows.

Output (read by io.github.rahulbsw.protoweft.evals.discovery.data.ToolRetLoader):
  <out>/tools/<category>.jsonl    {"id", "category", "documentation": "<tool JSON string>"}
  <out>/queries/<subset>.jsonl    {"id", "subset", "category", "query", "instruction",
                                   "labels": [{"id", "relevance"}]}
  <out>/manifest.json             revisions, method, per-file sha256 and row counts
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

QUERIES_REPO = "mangopy/ToolRet-Queries"
TOOLS_REPO = "mangopy/ToolRet-Tools"
# Revisions verified when the harness was written; override with --queries-revision/--tools-revision.
QUERIES_REVISION = "b8c76ad3349ff17497b6bdb28bb5b8f61a0f6445"
TOOLS_REVISION = "e06c38c75612b6536bd959e08cdd345894aba6a7"
TOOL_CONFIGS = ["web", "code", "customized"]
DEFAULT_SUBSETS = ["toolbench", "toollens", "metatool", "apigen", "apibank"]
ALL_SUBSETS = [
    "apibank", "apigen", "appbench", "autotools-food", "autotools-music", "autotools-weather",
    "craft-math-algebra", "craft-tabmwp", "craft-vqa", "gorilla-huggingface", "gorilla-pytorch",
    "gorilla-tensor", "gpt4tools", "gta", "metatool", "mnms", "restgpt-spotify", "restgpt-tmdb",
    "reversechain", "rotbench", "t-eval-dialog", "t-eval-step", "taskbench-daily",
    "taskbench-huggingface", "taskbench-multimedia", "tool-be-honest", "toolace", "toolalpaca",
    "toolbench-sam", "toolbench", "toolemu", "tooleyes", "toolink", "toollens", "ultratool",
]
ROWS_API = "https://datasets-server.huggingface.co/rows"
PAGE = 100


def http_get(url, retries=6):
    headers = {"User-Agent": "protoweft-evals/0.1"}
    token = os.environ.get("HF_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    delay = 2.0
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503, 504) or attempt == retries - 1:
                raise
        except urllib.error.URLError:
            if attempt == retries - 1:
                raise
        time.sleep(delay)
        delay *= 2
    raise RuntimeError("unreachable")


def current_sha(repo):
    meta = json.loads(http_get("https://huggingface.co/api/datasets/" + repo))
    return meta.get("sha", "unknown")


def rows_via_parquet(repo, revision, path):
    import pyarrow.parquet as pq  # optional dependency
    url = "https://huggingface.co/datasets/%s/resolve/%s/%s" % (repo, revision, path)
    with tempfile.NamedTemporaryFile(suffix=".parquet") as tmp:
        tmp.write(http_get(url))
        tmp.flush()
        return pq.read_table(tmp.name).to_pylist()


def rows_via_api(repo, config, split):
    out, offset, total = [], 0, None
    while total is None or offset < total:
        q = urllib.parse.urlencode({"dataset": repo, "config": config, "split": split,
                                    "offset": offset, "length": PAGE})
        page = json.loads(http_get(ROWS_API + "?" + q))
        total = page.get("num_rows_total", 0)
        rows = [r["row"] for r in page.get("rows", [])]
        if not rows:
            break
        out.extend(rows)
        offset += len(rows)
        print("  %s/%s: %d/%d rows" % (repo, config, offset, total), file=sys.stderr)
    return out


def fetch(repo, revision, config, split, filename, method):
    if method == "parquet":
        return rows_via_parquet(repo, revision, "%s/%s" % (config, filename))
    return rows_via_api(repo, config, split)


def write_jsonl(path, rows):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    h = hashlib.sha256()
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            line = json.dumps(row, ensure_ascii=False) + "\n"
            f.write(line)
            h.update(line.encode("utf-8"))
    return {"sha256": h.hexdigest(), "rows": len(rows)}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(here, "..", "build", "data", "toolret"))
    ap.add_argument("--subsets", default=",".join(DEFAULT_SUBSETS),
                    help="comma-separated query subsets, or 'all' (default: %(default)s)")
    ap.add_argument("--tool-configs", default=",".join(TOOL_CONFIGS))
    ap.add_argument("--method", choices=["auto", "parquet", "rows"], default="auto")
    ap.add_argument("--queries-revision", default=QUERIES_REVISION)
    ap.add_argument("--tools-revision", default=TOOLS_REVISION)
    args = ap.parse_args()

    method = args.method
    if method == "auto":
        try:
            import pyarrow  # noqa: F401
            method = "parquet"
        except ImportError:
            method = "rows"
    subsets = ALL_SUBSETS if args.subsets == "all" else [s.strip() for s in args.subsets.split(",") if s.strip()]
    unknown = [s for s in subsets if s not in ALL_SUBSETS]
    if unknown:
        sys.exit("unknown subsets: %s (known: %s)" % (unknown, ", ".join(ALL_SUBSETS)))
    out = os.path.abspath(args.out)
    q_rev, t_rev = args.queries_revision, args.tools_revision
    if method == "rows":
        q_rev, t_rev = current_sha(QUERIES_REPO), current_sha(TOOLS_REPO)
        if (q_rev, t_rev) != (args.queries_revision, args.tools_revision):
            print("warning: rows API serves the current revision (%s, %s), not the pinned one" % (q_rev, t_rev),
                  file=sys.stderr)
    print("fetching ToolRet via %s into %s" % (method, out), file=sys.stderr)

    files = {}
    for cfg in [c.strip() for c in args.tool_configs.split(",") if c.strip()]:
        rows = fetch(TOOLS_REPO, t_rev, cfg, "tools", "tools-00000-of-00001.parquet", method)
        recs = [{"id": r["id"], "category": cfg, "documentation": r["documentation"]} for r in rows]
        files["tools/%s.jsonl" % cfg] = write_jsonl(os.path.join(out, "tools", cfg + ".jsonl"), recs)
    for subset in subsets:
        rows = fetch(QUERIES_REPO, q_rev, subset, "queries", "queries-00000-of-00001.parquet", method)
        recs = []
        for r in rows:
            labels = r["labels"]
            labels = json.loads(labels) if isinstance(labels, str) else labels
            recs.append({"id": r["id"], "subset": subset, "category": r.get("category"), "query": r["query"],
                         "instruction": r.get("instruction"),
                         "labels": [{"id": l["id"], "relevance": l.get("relevance", 1)} for l in labels]})
        files["queries/%s.jsonl" % subset] = write_jsonl(os.path.join(out, "queries", subset + ".jsonl"), recs)

    manifest = {
        "dataset": "ToolRet",
        "revision": "%s@%s %s@%s" % (QUERIES_REPO, q_rev, TOOLS_REPO, t_rev),
        "method": method,
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "license": "HF data cards carry no license tag: local evaluation use only, never commit or redistribute",
        "files": files,
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print("done: %d files, manifest at %s" % (len(files), os.path.join(out, "manifest.json")), file=sys.stderr)


if __name__ == "__main__":
    main()
