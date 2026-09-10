#!/usr/bin/env python3
"""Give the exported screenshots their names.

`xcresulttool export attachments` writes every attachment under a UUID and puts
the real names in a `manifest.json` beside them, so a capture straight out of
the bundle is forty files called things like
`FB94A33F-1EB1-40B8-BE56-6F6A4C1C1A4D.png`. This turns them back into
`01-home.png` and drops everything that is not one of ours — the UI hierarchy
dumps, the debug descriptions, the screen recording.

The names come from the attachment names `ScreenshotTests` sets, which are
`<language>-<name>`; xcresulttool appends `_<index>_<uuid>`. The language is
dropped, because the committed set is the English one exactly as
`android/screenshots/` is.

Usage: name_screenshots.py <export-dir> <output-dir>
"""

import json
import pathlib
import re
import shutil
import sys

# `en-01-home_0_87791725-E48E-4724-BC5A-7EE61F67A48E.png`
#  └lang┘ └─name─┘ └i┘ └──────────────uuid──────────────┘
SUGGESTED = re.compile(
    r"^(?P<lang>[a-z]{2}(?:-[A-Za-z]{2})?)-(?P<name>.+?)"
    r"_\d+_[0-9A-Fa-f-]{36}\.png$"
)


def pairs(node, found):
    """Walk the manifest for (exported file, suggested name) pairs.

    Deliberately shape-agnostic: it looks for any object holding two strings
    where one names a file on disk and the other looks like one of our
    attachments. xcresulttool's manifest schema is Xcode's to change, and a
    rename here should not need a matching edit in a regex nobody remembers.
    """
    if isinstance(node, list):
        for item in node:
            pairs(item, found)
        return
    if not isinstance(node, dict):
        return

    strings = [v for v in node.values() if isinstance(v, str)]
    exported = [s for s in strings if (found["dir"] / s).is_file()]
    suggested = [s for s in strings if SUGGESTED.match(s)]
    if exported and suggested:
        found["pairs"].append((exported[0], SUGGESTED.match(suggested[0])))

    for value in node.values():
        pairs(value, found)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2

    export = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    manifest = export / "manifest.json"
    if not manifest.is_file():
        print(f"error: no manifest at {manifest}", file=sys.stderr)
        return 1

    found = {"dir": export, "pairs": []}
    pairs(json.loads(manifest.read_text()), found)

    out.mkdir(parents=True, exist_ok=True)
    written = set()
    for exported, match in found["pairs"]:
        name = match.group("name")
        if name in written:
            continue
        shutil.copyfile(export / exported, out / f"{name}.png")
        written.add(name)
        print(f"  {exported} -> {name}.png")

    # Loudly, not quietly. A capture that named nothing and still went green
    # would read as "the screens are fine" when the committed set never moved —
    # the same failure the workflow's own no-PNG check exists to prevent.
    if not written:
        print(
            "error: named no screenshots — the manifest's shape has changed, "
            "or the test attached nothing",
            file=sys.stderr,
        )
        return 1

    print(f"named {len(written)} screenshots")
    return 0


if __name__ == "__main__":
    sys.exit(main())
