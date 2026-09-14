#!/usr/bin/env python3
"""Checks docs/app-store-listing.md against App Store Connect's field limits.

App Store Connect rejects an over-long field on paste, after the text has been
written — this says so beforehand, per language. Keywords are counted the way
Apple counts them: the whole comma-separated string, commas included.
"""

import pathlib
import re
import sys

LIMITS = {
    "Name": 30,
    "Subtitle": 30,
    "Keywords": 100,
    "Promotional text": 170,
    "Description": 4000,
    "App Review notes": 4000,
}

LISTING = pathlib.Path(__file__).resolve().parent.parent / "docs" / "app-store-listing.md"


def fields(text):
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
        elif fenced and section in LIMITS:
            yield section, section, fenced.group(1)

    # The name lives in the info table rather than in a fenced block.
    row = re.search(r"^\| Name \| (.+?) \|$", text, re.M)
    if row:
        yield "App information", "Name", row.group(1).strip()


def main():
    text = LISTING.read_text(encoding="utf-8")
    failures = 0
    for section, label, value in fields(text):
        limit = LIMITS.get(label)
        if limit is None:
            continue
        # Descriptions are written as wrapped paragraphs; the line breaks are
        # real and are pasted as such, so they count.
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
