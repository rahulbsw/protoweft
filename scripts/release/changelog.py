#!/usr/bin/env python3
"""CHANGELOG.md steps of the Release workflow (.github/workflows/release.yml).

    changelog.py notes CHANGELOG.md
        Prints the [Unreleased] section's body: the release notes.

    changelog.py record --version 0.1.0 --date 2026-11-12 --released RELEASED.md CHANGELOG.md
        Rewrites CHANGELOG.md (current main) after a release. RELEASED.md is the CHANGELOG of the
        released commit; its [Unreleased] body becomes the version's section, unchanged. Entries
        main gained since stay under [Unreleased], in their sections. The unit of comparison is a
        whole entry (a "- " line at column 0 with its indented continuation lines), so an entry
        added next to released ones is never attributed to the release. A released entry edited
        on main since counts as new and stays under [Unreleased], visible for review.
"""
import argparse
import re
import sys

UNRELEASED = "## [Unreleased]"


def split(text):
    """(prefix, [Unreleased] body lines, rest) of a changelog."""
    lines = text.split("\n")
    start = next((i for i, l in enumerate(lines) if l.strip() == UNRELEASED), None)
    if start is None:
        sys.exit("CHANGELOG.md has no '## [Unreleased]' section")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## [")), len(lines))
    return lines[:start], lines[start + 1:end], lines[end:]


def sections(body):
    """[(heading or None, [entry, ...])] where an entry is its lines joined, in order."""
    out, heading, entries, entry = [], None, [], None
    for line in body:
        if line.startswith("### "):
            if entry is not None:
                entries.append(entry)
            if heading is not None or entries:
                out.append((heading, entries))
            heading, entries, entry = line, [], None
        elif re.match(r"^[-*] ", line) or (line.strip() and not line[0].isspace()):
            if entry is not None:
                entries.append(entry)
            entry = line
        elif line.strip() and entry is not None:
            entry += "\n" + line
        elif line.strip():
            entry = line   # indented text before any entry: keep it as one
    if entry is not None:
        entries.append(entry)
    if heading is not None or entries:
        out.append((heading, entries))
    return out


def render(groups):
    blocks = []
    for heading, entries in groups:
        lines = ([heading] if heading else []) + entries
        if lines:
            blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def strip_blank(lines):
    text = "\n".join(lines).strip("\n")
    return text


def notes(path):
    _, body, _ = split(open(path, encoding="utf-8").read())
    text = strip_blank(body)
    if text:
        print(text)


def record(path, released_path, version, date):
    prefix, main_body, rest = split(open(path, encoding="utf-8").read())
    _, released_body, _ = split(open(released_path, encoding="utf-8").read())
    released = {e for _, entries in sections(released_body) for e in entries}
    kept = [(h, [e for e in entries if e not in released]) for h, entries in sections(main_body)]
    kept = [(h, entries) for h, entries in kept if entries]
    out = prefix + [UNRELEASED, ""]
    if kept:
        out += [render(kept), ""]
    out += [f"## [{version}] - {date}", "", strip_blank(released_body), ""]
    tail = strip_blank(rest)
    if tail:
        out += [tail, ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    return sum(len(e) for _, e in kept)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    n = sub.add_parser("notes")
    n.add_argument("changelog")
    r = sub.add_parser("record")
    r.add_argument("--version", required=True)
    r.add_argument("--date", required=True)
    r.add_argument("--released", required=True)
    r.add_argument("changelog")
    args = p.parse_args()
    if args.command == "notes":
        notes(args.changelog)
    else:
        kept = record(args.changelog, args.released, args.version, args.date)
        print(f"{kept} entr{'y' if kept == 1 else 'ies'} added since the release stay under [Unreleased]")


if __name__ == "__main__":
    main()
