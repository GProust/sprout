#!/usr/bin/env python3
"""Draw the iOS app icon from Android's, so the two cannot drift.

Sprout's launcher icon is a vector in `android/app/src/main/res` — two leaves
and a stem over a green square. It is the same product, so it is the same icon,
and copying a PNG across would be a second copy of a thing that already exists.
This renders the Android vector instead, the way the string catalog is generated
from Android's resources rather than typed twice.

What iOS needs is one 1024×1024 PNG with **no alpha channel** (App Store Connect
rejects an icon with transparency) and no rounded corners of its own — the system
applies the mask.

Usage: make_app_icon.py [--check]
  --check  render and compare against what is committed, without writing
"""

import argparse
import math
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]
FOREGROUND = ROOT / "android/app/src/main/res/drawable/ic_launcher_foreground.xml"
COLORS = ROOT / "android/app/src/main/res/values/colors.xml"
OUT = ROOT / "ios/Sprout/Sources/Assets.xcassets/AppIcon.appiconset/icon-1024.png"

SIZE = 1024
# Rendered large and shrunk, because Pillow's polygon fill has no antialiasing
# of its own and a leaf's edge is all curve.
SUPERSAMPLE = 4
# How much of the canvas the artwork fills. Android crops its adaptive icon to a
# safe zone; iOS shows the whole square, so the padding has to be ours.
ARTWORK_FRACTION = 0.62

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def parse_color(text: str) -> tuple[int, int, int]:
    text = text.strip().lstrip("#")
    if len(text) == 8:  # AARRGGBB
        text = text[2:]
    return tuple(int(text[i:i + 2], 16) for i in (0, 2, 4))


def background_color() -> tuple[int, int, int]:
    root = ET.parse(COLORS).getroot()
    for color in root.findall("color"):
        if color.get("name") == "ic_launcher_background":
            return parse_color(color.text or "")
    raise SystemExit("error: ic_launcher_background not found")


def tokenize(data: str):
    """Path data into (command, [numbers]) pairs."""
    for command, arguments in re.findall(r"([MmLlHhVvCcZz])([^MmLlHhVvCcZz]*)", data):
        numbers = [float(n) for n in re.findall(r"-?\d*\.?\d+(?:[eE][-+]?\d+)?", arguments)]
        yield command, numbers


def cubic(p0, p1, p2, p3, steps=24):
    """A cubic bézier as points. Flattened rather than drawn, because the only
    fill available takes a polygon."""
    for step in range(1, steps + 1):
        t = step / steps
        u = 1 - t
        yield (
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        )


def flatten(data: str) -> list[list[tuple[float, float]]]:
    """The subpaths of one `pathData`, each as a list of points."""
    subpaths: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    x = y = 0.0
    start = (0.0, 0.0)

    for command, numbers in tokenize(data):
        if command in "Mm":
            if current:
                subpaths.append(current)
            for index in range(0, len(numbers), 2):
                nx, ny = numbers[index], numbers[index + 1]
                x, y = (nx, ny) if command == "M" else (x + nx, y + ny)
                if index == 0:
                    current = [(x, y)]
                    start = (x, y)
                else:
                    current.append((x, y))
        elif command in "Ll":
            for index in range(0, len(numbers), 2):
                nx, ny = numbers[index], numbers[index + 1]
                x, y = (nx, ny) if command == "L" else (x + nx, y + ny)
                current.append((x, y))
        elif command in "Hh":
            for nx in numbers:
                x = nx if command == "H" else x + nx
                current.append((x, y))
        elif command in "Vv":
            for ny in numbers:
                y = ny if command == "V" else y + ny
                current.append((x, y))
        elif command in "Cc":
            for index in range(0, len(numbers), 6):
                c1, c2, end = numbers[index:index + 2], numbers[index + 2:index + 4], numbers[index + 4:index + 6]
                if command == "c":
                    c1 = [x + c1[0], y + c1[1]]
                    c2 = [x + c2[0], y + c2[1]]
                    end = [x + end[0], y + end[1]]
                current.extend(cubic((x, y), tuple(c1), tuple(c2), tuple(end)))
                x, y = end
        elif command in "Zz":
            if current:
                current.append(start)
                subpaths.append(current)
                current = []
            x, y = start

    if current:
        subpaths.append(current)
    return subpaths


def render() -> Image.Image:
    tree = ET.parse(FOREGROUND).getroot()
    viewport_w = float(tree.get(f"{ANDROID_NS}viewportWidth", "108"))
    viewport_h = float(tree.get(f"{ANDROID_NS}viewportHeight", "108"))

    shapes = []
    for path in tree.findall("path"):
        data = path.get(f"{ANDROID_NS}pathData")
        fill = path.get(f"{ANDROID_NS}fillColor", "#FFFFFF")
        if data:
            shapes.append((flatten(data), parse_color(fill)))
    if not shapes:
        raise SystemExit("error: no paths in the Android vector")

    # Fit the artwork's own bounds, not the viewport's: the vector is drawn with
    # room around it for Android's mask, and iOS does not crop.
    points = [p for subpaths, _ in shapes for sub in subpaths for p in sub]
    min_x = min(p[0] for p in points); max_x = max(p[0] for p in points)
    min_y = min(p[1] for p in points); max_y = max(p[1] for p in points)
    span = max(max_x - min_x, max_y - min_y)

    canvas = SIZE * SUPERSAMPLE
    scale = canvas * ARTWORK_FRACTION / span
    offset_x = (canvas - (max_x - min_x) * scale) / 2 - min_x * scale
    offset_y = (canvas - (max_y - min_y) * scale) / 2 - min_y * scale

    # RGB, never RGBA: an icon with an alpha channel is rejected at upload.
    image = Image.new("RGB", (canvas, canvas), background_color())
    draw = ImageDraw.Draw(image)
    for subpaths, fill in shapes:
        for sub in subpaths:
            if len(sub) < 3:
                continue
            draw.polygon([(x * scale + offset_x, y * scale + offset_y) for x, y in sub], fill=fill)

    return image.resize((SIZE, SIZE), Image.LANCZOS)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="compare, do not write")
    args = parser.parse_args()

    image = render()

    if args.check:
        if not OUT.is_file():
            print(f"::error::{OUT.relative_to(ROOT)} is missing — run make_app_icon.py")
            return 1
        committed = Image.open(OUT).convert("RGB")
        # `tobytes` rather than `getdata`: same comparison, and it is not on
        # its way out of Pillow.
        if committed.tobytes() != image.tobytes():
            print(f"::error::{OUT.relative_to(ROOT)} is not what the Android vector renders.")
            print("Re-run 'python3 ios/tools/make_app_icon.py' and commit the result.")
            return 1
        print("The committed icon is the one Android's vector renders.")
        return 0

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)} ({SIZE}×{SIZE}, no alpha)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
