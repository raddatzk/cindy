#!/usr/bin/env python3
"""Compare the L("…") calls in ios/Cindy/ against the .strings catalogs.

Reports keys used in code but missing from a catalog, and keys left in a
catalog that nothing uses any more. Run it after adding or removing strings:

    python3 tools/check_localization.py

Exits non-zero when either list is non-empty, so it can gate a commit.
"""
import re
import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Interpolated values become format specifiers. The type is inferred from the
# expression text, so new interpolations may need a hint added here.
STRING_HINTS = (
    "formattedDecimal", "formattedPercent", "displayName", "singularName",
    "summary", "notation", "exerciseList", "localizedDescription", "reserveText",
    "recordingSizePerSession", "format(", "name(", "title", "reason", "names", "plankSummary", "setSummary",
    # Any `.formatted(…)` produces a String, whatever it started as.
    ".formatted(",
)
INT_HINTS = (
    "durationMinutes", "rounds", "reps", "totalReps", "extraReps", "stepNumber",
    "stepCount", "currentRound", "Int(", "minutes", "diff", "round", "target",
    "value", "countdownValue", "countdown", "remaining", "readiness.score", "plankSet",
)

# Plural forms live in Localizable.stringsdict, never in Localizable.strings.
PLURAL_KEYS = {
    "%lld minutes left",
    "%lld seconds left",
    "%lld rounds + %lld reps · %lld total reps",
    "%lld reps",
    "+%lld reps",
    "Time. %lld rounds plus %lld",
    "Full Cindy, pace steady. Target next time: %lld rounds.",
    "Finish the full %lld minutes first, then step up.",
}


def specifier(expression):
    if any(hint in expression for hint in STRING_HINTS):
        return "%@"
    if any(hint in expression for hint in INT_HINTS):
        return "%lld"
    raise SystemExit(f"Cannot infer a format specifier for: {expression}\n"
                     f"Add a hint to STRING_HINTS or INT_HINTS in {__file__}.")


def key_for(literal):
    """Turn a Swift string literal into its localization key."""
    out, i = [], 0
    while i < len(literal):
        if literal[i] == "\\" and i + 1 < len(literal) and literal[i + 1] == "(":
            depth, j = 1, i + 2
            while j < len(literal) and depth:
                if literal[j] == "(":
                    depth += 1
                elif literal[j] == ")":
                    depth -= 1
                j += 1
            out.append(specifier(literal[i + 2:j - 1]))
            i = j
        else:
            out.append(literal[i])
            i += 1
    return "".join(out)


def keys_in_source():
    keys = set()
    for path in (ROOT / "ios/Cindy").rglob("*.swift"):
        for match in re.finditer(r'\bL\("((?:[^"\\]|\\.)*)"\)', path.read_text()):
            keys.add(key_for(match.group(1)))
    return keys


def keys_in_catalog(language):
    path = ROOT / "ios/Cindy/Resources" / f"{language}.lproj/Localizable.strings"
    return set(re.findall(r'^"((?:[^"\\]|\\.)*)" = ', path.read_text(), re.M))


def main():
    used = keys_in_source() - PLURAL_KEYS
    failed = False
    for language in ("en", "de"):
        catalog = keys_in_catalog(language)
        missing = sorted(used - catalog)
        orphaned = sorted(catalog - used)
        if missing or orphaned:
            failed = True
        print(f"{language}: {len(catalog)} keys")
        for key in missing:
            print(f"  missing:  {key!r}")
        for key in orphaned:
            print(f"  orphaned: {key!r}")
    if not failed:
        print(f"OK — {len(used)} keys, both catalogs in sync "
              f"(+{len(PLURAL_KEYS)} plural keys in .stringsdict).")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
