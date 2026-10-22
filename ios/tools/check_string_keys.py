#!/usr/bin/env python3
"""Checks every string key the Swift references exists in the catalog.

A mistyped key does not fail to build. It renders as the key itself — a screen
reading "feed_type_breast" where it should say "Breast" — which survives review
right up until somebody looks at that screen in that language. There are 582
keys and seven languages, so "somebody looks" is not a plan.

It also checks that the help sheets (BDR-26) are the list Android shows: the
same pages, the same sections and the same words, in the same order. The
catalog being complete says each sheet *can* be read here; this says it says
what the other phone says. Android's `HelpPagesTest` makes the same comparison
from its side, so a change to either list is checked by whichever CI it wakes.

    python3 ios/tools/check_string_keys.py
"""

from __future__ import annotations

import difflib
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "ios/Sprout/Sources/Localizable.xcstrings"
SOURCES = REPO / "ios/Sprout/Sources"

# Str.t("key") and Str.t("key", arg, …)
REFERENCE = re.compile(r'\bStr\.t\(\s*"([A-Za-z0-9_]+)"')

# The two help lists, each naming its strings in the same order: the section
# headings, the pages' titles, then every page's entries.
HELP_SWIFT = SOURCES / "Screens/Help/HelpPages.swift"
HELP_KOTLIN = REPO / "android/app/src/main/java/com/gproust/sprout/ui/help/HelpPages.kt"
KOTLIN_REFERENCE = re.compile(r"\bR\.string\.([A-Za-z0-9_]+)")
# One entry of a page's sheet, and the suffix its key must carry for the
# section it is filed under — `help_feeding_how` is How to use it, on Feeding.
HELP_ENTRY = re.compile(r'HelpEntry\(\s*section:\s*\.(\w+),\s*body:\s*Str\.t\(\s*"([A-Za-z0-9_]+)"')
SECTION_SUFFIX = {"purpose": "_purpose", "how": "_how", "goodToKnow": "_good_to_know"}


def help_pages_match() -> bool:
    """True when the iOS help list names Android's strings, in Android's order."""
    for path in (HELP_SWIFT, HELP_KOTLIN):
        if not path.is_file():
            print(f"  ! help list not found at {path.relative_to(REPO)}", file=sys.stderr)
            return False

    swift_text = HELP_SWIFT.read_text(encoding="utf-8")
    swift = REFERENCE.findall(swift_text)
    kotlin = KOTLIN_REFERENCE.findall(HELP_KOTLIN.read_text(encoding="utf-8"))

    matches = True
    if swift != kotlin:
        print("  ! the help pages differ between the two apps:", file=sys.stderr)
        for line in difflib.unified_diff(
            kotlin, swift, "android: HelpPages.kt", "ios: HelpPages.swift", lineterm="", n=1
        ):
            print(f"    {line}", file=sys.stderr)
        matches = False

    for section, key in HELP_ENTRY.findall(swift_text):
        suffix = SECTION_SUFFIX.get(section)
        if suffix is None or not key.endswith(suffix):
            print(f"  ! {key} is filed under .{section} in HelpPages.swift", file=sys.stderr)
            matches = False

    if matches:
        print(f"the help pages name the same {len(swift)} strings on both apps")
    return matches


def main() -> int:
    if not CATALOG.is_file():
        print(f"catalog not found at {CATALOG}", file=sys.stderr)
        return 1

    catalog = set(json.loads(CATALOG.read_text(encoding="utf-8"))["strings"])

    referenced: dict[str, list[str]] = {}
    for path in sorted(SOURCES.rglob("*.swift")):
        for key in REFERENCE.findall(path.read_text(encoding="utf-8")):
            referenced.setdefault(key, []).append(str(path.relative_to(REPO)))

    missing = {key: files for key, files in referenced.items() if key not in catalog}

    print(f"{len(referenced)} keys referenced, {len(catalog)} in the catalog")
    failed = False
    if missing:
        for key, files in sorted(missing.items()):
            print(f"  ! {key} — used in {', '.join(sorted(set(files)))}", file=sys.stderr)
        print(f"{len(missing)} key(s) are not in the catalog", file=sys.stderr)
        failed = True
    else:
        print("every referenced key is in the catalog")

    if not help_pages_match():
        print("the help pages are not the same list as Android's", file=sys.stderr)
        failed = True

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
