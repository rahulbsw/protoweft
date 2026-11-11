#!/usr/bin/env python3
"""Build realistic, heavy-tailed result fixtures for the milestone-4 result-shaping study.

The size distribution of tool results is the most important input to the study, so this uses
*real* repository file contents for the heavy tail (read_file / fetch_url on README, architecture,
changelog, goal docs) alongside realistic structured outputs for issue-tracker, SQL and search
tools. Writes one JSON object per line to
evals/tool-discovery/build/fixtures-study/fx.jsonl (gitignored), each
{tool, args, content, tokens} where tokens is an o200k-ish estimate (len/4) used to seed the
synthetic-fallback size distribution for uncovered calls. Standard library only.
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(os.path.dirname(__file__), "..", "build", "fixtures-study", "fx.jsonl")


def toks(s):
    return max(1, len(s) // 4)


def read(path):
    p = os.path.join(ROOT, path)
    try:
        with open(p, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return f"[missing file {path}]"


def fx(tool, args, content):
    return {"tool": tool, "args": args, "content": content, "tokens": toks(content)}


def main():
    rows = []

    # --- issue tracker (small-to-medium structured) ---
    issues = {"issues": [
        {"id": "142", "title": "Checkout fails on expired card", "priority": "high",
         "labels": ["bug", "payments"], "comments": 4, "updated": "2026-11-02"},
        {"id": "139", "title": "Slow dashboard load over 2s", "priority": "medium",
         "labels": ["perf"], "comments": 1, "updated": "2026-10-28"},
        {"id": "131", "title": "Typo in onboarding email", "priority": "low",
         "labels": ["docs"], "comments": 0, "updated": "2026-10-20"}],
        "total": 3, "highest_priority_id": "142"}
    for args in ({"repo": "acme/widgets", "state": "open"}, {"repo": "acme/widgets"}):
        rows.append(fx("list_issues", args, json.dumps(issues)))

    issue142 = {"id": "142", "title": "Checkout fails on expired card", "priority": "high",
                "state": "open", "author": "jdoe",
                "body": ("Users report that submitting checkout with an expired card returns a 500 "
                         "instead of a validation error. Repro: add item, use card 4111 exp 01/20, "
                         "submit. Expected inline 'card expired' error; actual 500 and the cart is "
                         "cleared. Seen across web and mobile. "),
                "comments": [{"user": "amehta", "body": "Confirmed on staging."},
                             {"user": "jdoe", "body": "Stack trace points at PaymentValidator.line88."}]}
    rows.append(fx("get_issue", {"issue_id": "142"}, json.dumps(issue142)))

    # --- SQL (variable row sets) ---
    rows.append(fx("run_sql", {"query": "SELECT count(*) FROM users"},
                   json.dumps({"columns": ["count"], "rows": [[1284]], "row_count": 1})))
    big_rows = [[i, f"user{i}@acme.test", "active" if i % 3 else "churned"] for i in range(1, 121)]
    rows.append(fx("run_sql", {"query": "SELECT id, email, status FROM users LIMIT 120"},
                   json.dumps({"columns": ["id", "email", "status"], "rows": big_rows,
                               "row_count": len(big_rows)})))

    # --- code search (medium) ---
    matches = {"pattern": "TODO", "matches": [
        {"path": f"src/mod{i}/Service.java", "line": 40 + i, "text": "// TODO: handle retry"}
        for i in range(1, 25)], "total": 24}
    rows.append(fx("grep_code", {"pattern": "TODO"}, json.dumps(matches)))

    # --- real file contents (the heavy tail) ---
    for path in ("CHANGELOG.md", "README.md", "goal-and-requirements.md", "architecture.md"):
        rows.append(fx("read_file", {"path": path}, read(path)))

    # --- web fetch (real-ish large text) ---
    rows.append(fx("web_search", {"q": "Protoweft latest release notes"},
                   json.dumps({"results": [
                       {"rank": 1, "title": "Protoweft release notes",
                        "url": "https://example.com/protoweft/releases/latest",
                        "snippet": "Capability catalog, UTCP 1.1, RAP subscriptions."},
                       {"rank": 2, "title": "Protoweft changelog",
                        "url": "https://example.com/protoweft/changelog", "snippet": "All changes."}]})))
    rows.append(fx("fetch_url", {"url": "https://example.com/protoweft/releases/latest"},
                   read("README.md")))
    rows.append(fx("save_note", {"title": "note", "body": "ok"},
                   json.dumps({"saved": True, "id": "note-1"})))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    sizes = sorted(r["tokens"] for r in rows)
    print(f"wrote {len(rows)} fixtures to {os.path.relpath(OUT, ROOT)}")
    print(f"token sizes: min={sizes[0]} median={sizes[len(sizes) // 2]} max={sizes[-1]}")


if __name__ == "__main__":
    main()
