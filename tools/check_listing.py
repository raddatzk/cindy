#!/usr/bin/env python3
"""Checks the store listings in docs/ against the stores' field limits.

App Store Connect and the Play Console reject an over-long field on paste, after
the text has been written — this says so beforehand, per language. Keywords are
counted the way Apple counts them: the whole comma-separated string, commas
included.
"""

import pathlib
import re
import sys

DOCS = pathlib.Path(__file__).resolve().parent.parent / "docs"

# Keyed by the bold field label, or by the section heading for blocks that sit
# directly under one (App Review notes) or whose labels vary (release notes).
LISTINGS = {
    DOCS / "app-store-listing.md": {
        "Name": 30,
        "Subtitle": 30,
        "Keywords": 100,
        "Promotional text": 170,
        "Description": 4000,
        "App Review notes": 4000,
    },
    DOCS / "play-store-listing.md": {
        "App name": 30,
        "Short description": 80,
        "Full description": 4000,
        "Release notes": 500,
    },
}


def fields(text, limits):
    """Yields (section, field, value) for every fenced block under a **Field** label."""
    section = ""
    label = None
    for block in re.split(r"\n(?=#{2,3} |\*\*)", text):
        if block.startswith("## ") or block.startswith("### "):
            section = block.split("\n", 1)[0].lstrip("# ").strip()
        match = re.match(r"\*\*(.+?)\*\*", block)
        if match:
            label = match.group(1)
        fenced = re.search(r"```\n(.*?)\n```", block, re.S)
        if fenced and label:
            yield section, label, fenced.group(1)
            label = None
        elif fenced and section in limits:
            yield section, section, fenced.group(1)

    # The name lives in the info table rather than in a fenced block.
    row = re.search(r"^\| Name \| (.+?) \|$", text, re.M)
    if row:
        yield "App information", "Name", row.group(1).strip()


def main():
    failures = 0
    for listing, limits in LISTINGS.items():
        print(listing.name)
        text = listing.read_text(encoding="utf-8")
        for section, label, value in fields(text, limits):
            limit = limits.get(label, limits.get(section))
            if limit is None:
                continue
            # Line breaks are pasted as they are written, so they count.
            length = len(value)
            status = "ok " if length <= limit else "OVER"
            if length > limit:
                failures += 1
            print(f"{status} {section} / {label}: {length}/{limit}")

    if failures:
        print(f"\n{failures} field(s) over the limit.", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
