#!/usr/bin/env python3
"""Download the MS MARCO MiniLM cross-encoders (quantized ONNX + vocabulary) for the reranker experiment.

Standard library only. Files are pinned to a Hugging Face revision and checked against the SHA-256 of the
LFS object (or, for vocab.txt, of the known bge-small vocabulary). A re-run verifies what is on disk and
refuses (exit 1, nothing overwritten) if a file differs; --force re-downloads mismatching files.

Models (the original cross-encoders are Apache-2.0, sentence-transformers / UKPLab):
  l6  cross-encoder/ms-marco-MiniLM-L6-v2 (formerly ms-marco-MiniLM-L-6-v2)
  l2  cross-encoder/ms-marco-MiniLM-L2-v2

Sources (--source):
  xenova  (default) Xenova/ms-marco-MiniLM-L-*-v2 onnx/model_quantized.onnx: Transformers.js export,
          dynamic int8 (QInt8 weights, per-channel, reduce_range), portable across CPUs
  arm64   cross-encoder/ms-marco-MiniLM-L*-v2 onnx/model_qint8_arm64.onnx (Optimum, arm64 / avx512)
  avx2    cross-encoder/ms-marco-MiniLM-L*-v2 onnx/model_quint8_avx2.onnx (Optimum, x86 avx2)

The vocabulary must be the 30,522-entry bert-base-uncased list that bge-small-en-v1.5 uses (same git blob
fb140275...), so AgentWeave's WordPieceTokenizer applies unchanged; the script checks that.

Output: <out>/<model-dir>/model.onnx, vocab.txt, manifest.json (repo, revision, file, size, sha256, source).
Default <out>: evals/tool-discovery/build/models (ignored by git via build/).
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

VOCAB_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"  # bge-small vocab.txt
VOCAB_SIZE = 30522
VOCAB_BLOB = "fb140275c155a9c7c5a3b3e0e77a9e839594a938"

# model -> source -> (repo, revision, onnx path, size, sha256). Revisions and LFS sha256 from
# https://huggingface.co/api/models/<repo>/tree/<revision>/onnx
MODELS = {
    "l6": {
        "dir": "ms-marco-minilm-l6-v2",
        "original": "cross-encoder/ms-marco-MiniLM-L6-v2",
        "sources": {
            "xenova": ("Xenova/ms-marco-MiniLM-L-6-v2", "a09144355adeed5f58c8ed011d209bf8ee5a1fec",
                       "onnx/model_quantized.onnx", 23143499,
                       "e9d8ebf845c413e981c175bfe49a3bfa9b3dcce2a3ba54875ee5df5a58639fbe"),
            "arm64": ("cross-encoder/ms-marco-MiniLM-L6-v2", "233902d25c440f23af6f7d6e94d2946bac0bee0a",
                      "onnx/model_qint8_arm64.onnx", 23200716,
                      "3573b6b9593cb2f75987a31815d409ca3dd8808629118fd20451bb1a5d90cec7"),
            "avx2": ("cross-encoder/ms-marco-MiniLM-L6-v2", "233902d25c440f23af6f7d6e94d2946bac0bee0a",
                     "onnx/model_quint8_avx2.onnx", 23200716,
                     "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9"),
        },
    },
    "l2": {
        "dir": "ms-marco-minilm-l2-v2",
        "original": "cross-encoder/ms-marco-MiniLM-L2-v2",
        "sources": {
            "xenova": ("Xenova/ms-marco-MiniLM-L-2-v2", "b84c4fa7efd7b4801931e75773c940f002a494f5",
                       "onnx/model_quantized.onnx", 15772586,
                       "3263a5312f162a4b8f5578d47a57de6461cd3ad24dbc1bdee8884d3ac6b871e8"),
            "arm64": ("cross-encoder/ms-marco-MiniLM-L2-v2", "1b5cd67b15209f24824c50370e0397743aa9b787",
                      "onnx/model_qint8_arm64.onnx", 15794398,
                      "09fb2faaa61c554136314e6e4476f330638a5b157f1f7715cfe7aa319164b212"),
            "avx2": ("cross-encoder/ms-marco-MiniLM-L2-v2", "1b5cd67b15209f24824c50370e0397743aa9b787",
                     "onnx/model_quint8_avx2.onnx", 15794398,
                     "2c7f4c682cf9320acb3748c619b2e21d24ef460e2f96b98b0ab6b190c5eb2ca9"),
        },
    },
}


def http_get(urls, retries=4):
    """GETs the first URL that answers; returns (bytes, url)."""
    headers = {"User-Agent": "agentweave-evals/0.1"}
    token = os.environ.get("HF_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    errors = []
    for url in urls:
        delay = 2.0
        for attempt in range(retries):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=300) as r:
                    return r.read(), url
            except urllib.error.HTTPError as e:
                errors.append("%s: HTTP %d" % (url, e.code))
                if e.code not in (429, 500, 502, 503, 504):
                    break
            except urllib.error.URLError as e:
                errors.append("%s: %s" % (url, e.reason))
            time.sleep(delay)
            delay *= 2
    raise RuntimeError("download failed:\n  " + "\n  ".join(errors))


def candidates(repo, revision, path):
    # resolve/ is the normal route; raw/ serves non-LFS files when resolve/ is blocked by a proxy.
    return ["https://huggingface.co/%s/resolve/%s/%s" % (repo, revision, path),
            "https://huggingface.co/%s/raw/%s/%s" % (repo, revision, path)]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def check_vocab(data):
    lines = data.decode("utf-8").split("\n")
    if lines and lines[-1] == "":
        lines = lines[:-1]
    if len(lines) != VOCAB_SIZE:
        raise ValueError("vocab.txt has %d entries, expected %d" % (len(lines), VOCAB_SIZE))
    if sha256(data) != VOCAB_SHA256:
        raise ValueError("vocab.txt sha256 %s differs from bge-small's %s" % (sha256(data), VOCAB_SHA256))


def ensure(path, expected_sha, expected_size, urls, force, check=None):
    """Returns (sha256, size, url or 'cached'). Refuses to overwrite a mismatching file unless forced."""
    if os.path.exists(path):
        with open(path, "rb") as f:
            data = f.read()
        actual = sha256(data)
        if actual == expected_sha and (expected_size is None or len(data) == expected_size):
            if check:
                check(data)
            return actual, len(data), "cached"
        if not force:
            sys.exit("refusing: %s has sha256 %s (%d bytes), expected %s; rerun with --force to replace it"
                     % (path, actual, len(data), expected_sha))
    data, url = http_get(urls)
    actual = sha256(data)
    if actual != expected_sha or (expected_size is not None and len(data) != expected_size):
        sys.exit("refusing: %s downloaded with sha256 %s (%d bytes), expected %s (%s bytes)"
                 % (url, actual, len(data), expected_sha, expected_size))
    if check:
        check(data)
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return actual, len(data), url


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(here, "..", "build", "models"))
    ap.add_argument("--models", default="l6,l2", help="comma-separated: l6, l2 (default: %(default)s)")
    ap.add_argument("--source", choices=["xenova", "arm64", "avx2"], default="xenova")
    ap.add_argument("--force", action="store_true", help="replace files whose checksum differs")
    args = ap.parse_args()

    for key in [m.strip() for m in args.models.split(",") if m.strip()]:
        if key not in MODELS:
            sys.exit("unknown model %s (known: %s)" % (key, ", ".join(MODELS)))
        model = MODELS[key]
        repo, revision, onnx_path, size, onnx_sha = model["sources"][args.source]
        out = os.path.abspath(os.path.join(args.out, model["dir"]))
        os.makedirs(out, exist_ok=True)
        manifest_path = os.path.join(out, "manifest.json")
        if os.path.exists(manifest_path) and not args.force:
            with open(manifest_path, encoding="utf-8") as f:
                old = json.load(f)
            if old.get("model", {}).get("sha256") != onnx_sha:
                sys.exit("refusing: %s records %s (%s), not the requested %s; use --force or another --out"
                         % (manifest_path, old.get("model", {}).get("file"), old.get("model", {}).get("sha256"),
                            onnx_path))
        print("%s: %s@%s %s" % (key, repo, revision[:8], onnx_path), file=sys.stderr)
        m_sha, m_size, m_url = ensure(os.path.join(out, "model.onnx"), onnx_sha, size,
                                      candidates(repo, revision, onnx_path), args.force)
        v_sha, v_size, v_url = ensure(os.path.join(out, "vocab.txt"), VOCAB_SHA256, None,
                                      candidates(repo, revision, "vocab.txt"), args.force, check_vocab)
        manifest = {
            "model": {"key": key, "original": model["original"], "license": "Apache-2.0 (original model card)",
                      "repo": repo, "revision": revision, "file": onnx_path, "source": args.source,
                      "size": m_size, "sha256": m_sha, "downloadedFrom": m_url},
            "vocab": {"repo": repo, "revision": revision, "file": "vocab.txt", "entries": VOCAB_SIZE,
                      "size": v_size, "sha256": v_sha, "gitBlob": VOCAB_BLOB,
                      "note": "identical to bge-small-en-v1.5's vocab.txt", "downloadedFrom": v_url},
            "fetchedAt": datetime.now(timezone.utc).isoformat(),
        }
        if os.path.exists(manifest_path):
            with open(manifest_path, encoding="utf-8") as f:
                old = json.load(f)
            # keep the original download record when everything was already cached
            if m_url == "cached":
                manifest["model"]["downloadedFrom"] = old.get("model", {}).get("downloadedFrom", "cached")
            if v_url == "cached":
                manifest["vocab"]["downloadedFrom"] = old.get("vocab", {}).get("downloadedFrom", "cached")
            if m_url == "cached" and v_url == "cached":
                manifest["fetchedAt"] = old.get("fetchedAt", manifest["fetchedAt"])
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)
        print("  ok: model %s (%d bytes), vocab %s" % (m_sha, m_size, v_sha), file=sys.stderr)


if __name__ == "__main__":
    main()
