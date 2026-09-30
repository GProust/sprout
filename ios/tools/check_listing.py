#!/usr/bin/env python3
"""Checks the App Store listing before anything uploads it, and stages its screenshots.

`ios/fastlane/metadata/<locale>/` is what fastlane's `deliver` reads, one file
per field. App Store Connect enforces its limits only when the upload arrives,
and then rejects the whole thing for one field a character too long. So the
limits are checked here, on every pull request that touches the listing, along
with the rules that keep the seven languages one listing:

- every language has every field, and nothing over Apple's limit;
- the name starts with "Sprout" everywhere, because it is one app;
- a keyword never repeats a word of the name or the subtitle — Apple already
  searches both, so the repeat is 100 characters spent on nothing;
- keywords stay within 100 *bytes*, not characters, so an "ä" cannot tip one
  over;
- no other platform is named (App Review Guideline 2.3.10);
- the three URLs are the same in every language.

    python3 ios/tools/check_listing.py
    python3 ios/tools/check_listing.py stage <captured-dir> <out-dir>

`stage` takes a capture from `ios-listing-screenshots.yml` — one folder per
locale, every screen the UI test takes — and copies the ones named in
`ios/fastlane/listing-screenshots.txt` into the layout `deliver` uploads,
numbered so they appear in that order.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
METADATA = REPO / "ios/fastlane/metadata"
SCREENSHOT_LIST = REPO / "ios/fastlane/listing-screenshots.txt"
REVIEW_SCREENSHOTS = REPO / "ios/screenshots"

LOCALES = ("en-US", "fr-FR", "de-DE", "es-ES", "it", "pl", "pt-PT")

# Apple's limits, in characters. Keywords are also held to 100 bytes below.
LIMITS = {
    "name": 30,
    "subtitle": 30,
    "promotional_text": 170,
    "keywords": 100,
    "description": 4000,
}
URLS = ("support_url", "marketing_url", "privacy_url")
FIELDS = (*LIMITS, *URLS)

OTHER_PLATFORMS = re.compile(r"\b(android|google play|play store)\b", re.IGNORECASE)
MAX_SCREENSHOTS = 10


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def words(text: str) -> set[str]:
    """The words in a field, lower-cased; short joining words don't count."""
    return {w for w in re.findall(r"[\w-]+", text.lower()) if len(w) > 2}


def screenshot_names() -> list[str]:
    names = []
    for line in SCREENSHOT_LIST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.append(line)
    return names


def check() -> list[str]:
    errors: list[str] = []
    urls: dict[str, set[str]] = {key: set() for key in URLS}

    present = sorted(p.name for p in METADATA.iterdir() if p.is_dir())
    if present != sorted(LOCALES):
        errors.append(f"metadata languages are {present}, expected {sorted(LOCALES)}")

    for locale in LOCALES:
        folder = METADATA / locale
        values: dict[str, str] = {}
        for field in FIELDS:
            path = folder / f"{field}.txt"
            if not path.is_file():
                errors.append(f"{locale}: {field}.txt is missing")
                continue
            values[field] = read(path)
            if not values[field]:
                errors.append(f"{locale}: {field}.txt is empty")

        for field, limit in LIMITS.items():
            if field in values and len(values[field]) > limit:
                errors.append(f"{locale}: {field} is {len(values[field])} characters, the limit is {limit}")

        name = values.get("name", "")
        if name and not name.startswith("Sprout"):
            errors.append(f"{locale}: the name {name!r} does not start with Sprout")

        keywords = values.get("keywords", "")
        if keywords:
            size = len(keywords.encode("utf-8"))
            if size > LIMITS["keywords"]:
                errors.append(f"{locale}: keywords are {size} bytes, the limit is {LIMITS['keywords']}")
            entries = keywords.split(",")
            if any(entry != entry.strip() or not entry for entry in entries):
                errors.append(f"{locale}: keywords need commas with no spaces around them and no empty entries")
            lowered = [entry.strip().lower() for entry in entries]
            if len(set(lowered)) != len(lowered):
                errors.append(f"{locale}: a keyword appears twice")
            searched = (words(name) | words(values.get("subtitle", ""))) - {"sprout"}
            repeated = sorted(words(keywords) & searched)
            if repeated:
                errors.append(f"{locale}: keywords repeat {repeated}, which the name or subtitle already has")
            if "sprout" in words(keywords):
                errors.append(f"{locale}: 'sprout' is in the keywords; the name already carries it")

        for field, value in values.items():
            if OTHER_PLATFORMS.search(value):
                errors.append(f"{locale}: {field} names another platform ({OTHER_PLATFORMS.search(value).group(0)})")

        for field in URLS:
            if field in values:
                urls[field].add(values[field])
                if not values[field].startswith("https://"):
                    errors.append(f"{locale}: {field} is not an https:// address")

    for field, seen in urls.items():
        if len(seen) > 1:
            errors.append(f"{field} differs between languages: {sorted(seen)}")

    copyright_file = METADATA / "copyright.txt"
    if not copyright_file.is_file() or not read(copyright_file):
        errors.append("copyright.txt is missing or empty")

    names = screenshot_names()
    if not 1 <= len(names) <= MAX_SCREENSHOTS:
        errors.append(f"listing-screenshots.txt names {len(names)} screenshots; App Store Connect takes 1 to {MAX_SCREENSHOTS}")
    if len(set(names)) != len(names):
        errors.append("listing-screenshots.txt names a screenshot twice")
    for name in names:
        if not (REVIEW_SCREENSHOTS / f"{name}.png").is_file():
            errors.append(f"listing-screenshots.txt names {name}, which is not a screen the UI test captures")

    return errors


def stage(captured: Path, out: Path) -> int:
    names = screenshot_names()
    missing = []
    for locale in LOCALES:
        target = out / locale
        target.mkdir(parents=True, exist_ok=True)
        for position, name in enumerate(names, start=1):
            source = captured / locale / f"{name}.png"
            if not source.is_file():
                missing.append(f"{locale}/{name}.png")
                continue
            # deliver orders a language's screenshots by file name; the number
            # in front is the position in listing-screenshots.txt.
            shutil.copyfile(source, target / f"{position:02d}-{name}.png")
    if missing:
        print("not in the capture: " + ", ".join(missing), file=sys.stderr)
        return 1
    print(f"staged {len(names)} screenshots in each of {len(LOCALES)} languages")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[0] == "stage":
        return stage(Path(argv[1]), Path(argv[2]))
    if argv:
        print(__doc__.strip().split("\n\n")[-2], file=sys.stderr)
        return 2

    errors = check()
    for error in errors:
        print(f"::error::{error}")
    if errors:
        return 1
    print(f"the listing is within Apple's limits in all {len(LOCALES)} languages")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
