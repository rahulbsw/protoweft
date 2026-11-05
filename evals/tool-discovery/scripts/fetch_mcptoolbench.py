#!/usr/bin/env python3
"""Download the MCPToolBench++ evaluation data (MIT) at a pinned commit, standard library only.

Source: https://github.com/mcp-tool-bench/MCPToolBenchPP (MIT License, (c) 2026 mcp-tool-bench).
Fetches the six single-call data files (1,509 instances over 87 tools) plus the LICENSE into
evals/tool-discovery/build/data/mcptoolbench/ (ignored by git via `build/`), and writes a
manifest.json with the commit and per-file sha256. The Java loader
(io.agentweave.evals.discovery.data.McpToolBenchLoader) reads that directory.
"""
import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

REPO = "mcp-tool-bench/MCPToolBenchPP"
# Commit verified when the harness was written; override with --revision.
REVISION = "2bd35e58ed5ebe79bbb12df0f9919a34ed2cb037"
FILES = [
    "data/browser/browser_0724_single_v3.json",
    "data/file_system/filesystem_0723_single.json",
    "data/finance/finance_0724_single_v3.json",
    "data/map/map_0717_single_multi_lang_500.json",
    "data/pay/pay_0723_single.json",
    "data/search/search_0725_single_v2.json",
    "LICENSE",
]


def get(url, retries=5):
    delay = 2.0
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "agentweave-evals/0.1"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except (urllib.error.HTTPError, urllib.error.URLError):
            if attempt == retries - 1:
                raise
            time.sleep(delay)
            delay *= 2
    raise RuntimeError("unreachable")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(here, "..", "build", "data", "mcptoolbench"))
    ap.add_argument("--revision", default=REVISION)
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    files = {}
    for path in FILES:
        url = "https://raw.githubusercontent.com/%s/%s/%s" % (REPO, args.revision, path)
        body = get(url)
        rel = path[len("data/"):] if path.startswith("data/") else path
        dest = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(body)
        files[rel] = {"sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}
        print("  %s (%d bytes)" % (rel, len(body)), file=sys.stderr)
    manifest = {
        "dataset": "MCPToolBench++",
        "source": "https://github.com/" + REPO,
        "revision": args.revision,
        "license": "MIT",
        "fetchedAt": datetime.now(timezone.utc).isoformat(),
        "files": files,
    }
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print("done: %s" % out, file=sys.stderr)


if __name__ == "__main__":
    main()
