#!/usr/bin/env python3
"""Checks every string key the Swift references exists in the catalog.

A mistyped key does not fail to build. It renders as the key itself — a screen
reading "feed_type_breast" where it should say "Breast" — which survives review
right up until somebody looks at that screen in that language. There are 582
keys and seven languages, so "somebody looks" is not a plan.

    python3 ios/tools/check_string_keys.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CATALOG = REPO / "ios/Sprout/Sources/Localizable.xcstrings"
SOURCES = REPO / "ios/Sprout/Sources"

# Str.t("key") and Str.t("key", arg, …)
REFERENCE = re.compile(r'\bStr\.t\(\s*"([A-Za-z0-9_]+)"')


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
    if missing:
        for key, files in sorted(missing.items()):
            print(f"  ! {key} — used in {', '.join(sorted(set(files)))}", file=sys.stderr)
        print(f"{len(missing)} key(s) are not in the catalog", file=sys.stderr)
        return 1

    print("every referenced key is in the catalog")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
