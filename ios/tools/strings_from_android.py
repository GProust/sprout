#!/usr/bin/env python3
"""Builds the iOS string catalog from the Android string resources.

The copy is *product*, not platform: "Left breast" is the same sentence on both
phones, in all seven languages. Translating it twice would mean maintaining two
sets that drift, and drift in the direction nobody notices — a string added on
Android and forgotten on iOS shows up as an English word in the middle of a
Polish screen.

So Android's `res/values*/strings.xml` is the single source and this generates
`Localizable.xcstrings` from it. That direction is deliberate: Android's lint
treats MissingTranslation as fatal (ADR-0005), so the source is guaranteed
complete in all seven languages before it ever gets here.

Deterministic, like spec/vectors/generate.mjs: re-running must leave the working
tree clean, and CI checks it.

    python3 ios/tools/strings_from_android.py
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ANDROID_RES = REPO / "android/app/src/main/res"
OUTPUT = REPO / "ios/Sprout/Sources/Localizable.xcstrings"

SOURCE_LANGUAGE = "en"
# values/ is the default (English); the rest carry their language code.
LOCALES = {
    "values": "en",
    "values-fr": "fr",
    "values-de": "de",
    "values-es": "es",
    "values-it": "it",
    "values-pl": "pl",
    "values-pt": "pt",
}

# Android's plural buckets and Apple's happen to share these names.
PLURAL_CATEGORIES = ("zero", "one", "two", "few", "many", "other")


def convert_format_specifiers(text: str) -> str:
    """Rewrites Android's format specifiers as Foundation's.

    `%1$d` -> `%1$lld` because Swift's Int is 64-bit and `%d` would truncate on
    a value nobody expects to be large until it is; `%1$s` -> `%1$@` because
    Foundation takes an object where Java takes a C string.
    """

    def replace(match: re.Match[str]) -> str:
        index, conversion = match.group(1), match.group(2)
        position = index or ""
        if conversion == "d":
            return f"%{position}lld"
        if conversion == "s":
            return f"%{position}@"
        return match.group(0)

    # `%%` is a literal percent and must not be touched, so it is matched first
    # and handed back unchanged.
    return re.sub(r"%%|%(\d+\$)?([dsf])", lambda m: m.group(0) if m.group(0) == "%%" else replace(m), text)


def unescape_android(text: str) -> str:
    """Undoes the escaping Android requires inside a resource value."""
    out = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            following = text[index + 1]
            out.append({"n": "\n", "t": "\t"}.get(following, following))
            index += 2
            continue
        out.append(char)
        index += 1
    return "".join(out)


def value_of(element: ET.Element) -> str:
    """The element's text, with any inline markup flattened.

    A handful of strings carry `<b>`/`<i>`; SwiftUI takes markdown, and keeping
    the emphasis would mean a second dialect to maintain in seven languages for
    two words. The text is kept and the markup dropped, which is what the string
    means.
    """
    return unescape_android("".join(element.itertext()))


def read_locale(directory: Path) -> tuple[dict[str, str], dict[str, dict[str, str]], set[str]]:
    path = directory / "strings.xml"
    root = ET.parse(path).getroot()

    singles: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    # Brand name and the language endonyms: "Français" is Français in every
    # locale. Android marks them and lint stops asking for translations; this
    # keeps them out of the completeness report for the same reason.
    untranslatable: set[str] = set()

    for element in root:
        name = element.get("name")
        if not name:
            continue
        if element.tag == "string":
            if element.get("translatable") == "false":
                untranslatable.add(name)
            singles[name] = convert_format_specifiers(value_of(element))
        elif element.tag == "plurals":
            buckets = {
                item.get("quantity"): convert_format_specifiers(value_of(item))
                for item in element.findall("item")
                if item.get("quantity") in PLURAL_CATEGORIES
            }
            if buckets:
                plurals[name] = buckets

    return singles, plurals, untranslatable


def string_unit(value: str) -> dict:
    return {"stringUnit": {"state": "translated", "value": value}}


def main() -> int:
    if not ANDROID_RES.is_dir():
        print(f"android resources not found at {ANDROID_RES}", file=sys.stderr)
        return 1

    per_locale = {}
    for folder, language in LOCALES.items():
        directory = ANDROID_RES / folder
        if not (directory / "strings.xml").is_file():
            print(f"missing {directory}/strings.xml", file=sys.stderr)
            return 1
        per_locale[language] = read_locale(directory)

    source_singles, source_plurals, untranslatable = per_locale[SOURCE_LANGUAGE]
    catalog: dict[str, dict] = {}

    # The default locale decides which keys exist. A key present only in a
    # translation is a leftover, and carrying it here would hide that.
    for key in sorted(source_singles):
        localizations = {}
        for language, (singles, _, _) in per_locale.items():
            if key in singles:
                localizations[language] = string_unit(singles[key])
        catalog[key] = {"localizations": localizations}

    for key in sorted(source_plurals):
        localizations = {}
        for language, (_, plurals, _) in per_locale.items():
            buckets = plurals.get(key)
            if not buckets:
                continue
            localizations[language] = {
                "variations": {
                    "plural": {
                        category: string_unit(buckets[category])
                        for category in PLURAL_CATEGORIES
                        if category in buckets
                    }
                }
            }
        catalog[key] = {"localizations": localizations}

    document = {
        "sourceLanguage": SOURCE_LANGUAGE,
        "strings": catalog,
        "version": "1.0",
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    expected = set(LOCALES.values())
    incomplete = {
        key: sorted(expected - set(entry["localizations"]))
        for key, entry in sorted(catalog.items())
        if key not in untranslatable and set(entry["localizations"]) != expected
    }

    print(f"wrote {len(catalog)} keys to {OUTPUT.relative_to(REPO)}")
    print(f"  {len(source_singles)} strings, {len(source_plurals)} plurals")
    print(f"  {len(catalog) - len(untranslatable) - len(incomplete)} complete in all {len(LOCALES)} languages")
    print(f"  {len(untranslatable)} deliberately untranslated (brand name, language endonyms)")

    if incomplete:
        # Android lint makes MissingTranslation fatal, so this should be
        # unreachable — but a half-English screen in Polish is worth failing
        # loudly for rather than discovering in a screenshot.
        for key, missing in incomplete.items():
            print(f"  ! {key} missing: {', '.join(missing)}", file=sys.stderr)
        print(f"{len(incomplete)} key(s) are not translated everywhere", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
