#!/usr/bin/env python3
"""Print the UDID of an iOS simulator to build and test on.

Not a device name. ``-destination 'platform=iOS Simulator,name=iPhone 16'`` is
how every example writes it, and it is a hard dependency on one model existing
on the runner — which stopped being true when the macOS image changed underneath
us. Worse, it failed *differently* in each job: ``xcodebuild build`` degrades to
a placeholder destination and carries on, while ``test`` needs a device it can
boot. Green build, no screenshots, and nothing saying they were one cause.

So: ask the machine what it has. Any iPhone on the newest installed runtime will
do — nothing Sprout captures depends on the model — and if the image ships
runtimes but no devices, make one rather than failing.

Python rather than shell because the first version of this was a `python3 -c`
inside a single-quoted shell string, and the `'.'` in it closed that string.
There is no version of quoting a program inside a program that is worth
maintaining.
"""

import json
import re
import subprocess
import sys

RUNTIME_PREFIX = "com.apple.CoreSimulator.SimRuntime.iOS-"
# Ordered by preference; the first one this image has is the one we create.
DEVICE_TYPES = [
    "com.apple.CoreSimulator.SimDeviceType.iPhone-16",
    "com.apple.CoreSimulator.SimDeviceType.iPhone-15",
    "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation",
]


def simctl(*args):
    return json.loads(
        subprocess.run(
            ["xcrun", "simctl", *args, "--json"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    )


def runtime_order(identifier):
    """(major, minor) for an iOS runtime identifier, or None if it is not one."""
    match = re.search(r"iOS-([0-9]+)(?:-([0-9]+))?$", identifier)
    if not match:
        return None
    return int(match.group(1)), int(match.group(2) or 0)


def pick():
    """The best available iPhone: newest runtime, plainest model."""
    best = None
    for runtime, devices in simctl("list", "devices", "available")["devices"].items():
        order = runtime_order(runtime)
        if order is None:
            continue
        for device in devices:
            name = device.get("name", "")
            if not device.get("isAvailable") or not name.startswith("iPhone"):
                continue
            # A plain iPhone over a Pro or a Max: they differ only in screen
            # size, and a capture of a 6.9" phone is no more informative than
            # one of a 6.1" phone.
            rank = (order, "Pro" not in name and "Max" not in name, name)
            if best is None or rank > best[0]:
                best = (rank, device["udid"], name, runtime)
    return best


def create():
    """Make an iPhone, for an image that ships runtimes but no devices."""
    runtimes = [
        r for r in simctl("list", "runtimes")["runtimes"]
        if r.get("isAvailable") and r["identifier"].startswith(RUNTIME_PREFIX)
    ]
    if not runtimes:
        print(
            "::error::this runner has no iOS simulator runtime at all — "
            "nothing to build against",
            file=sys.stderr,
        )
        return False

    runtime = max(runtimes, key=lambda r: runtime_order(r["identifier"]) or (0, 0))
    for device_type in DEVICE_TYPES:
        result = subprocess.run(
            ["xcrun", "simctl", "create", "Sprout-CI", device_type, runtime["identifier"]],
            capture_output=True,
            text=True,
        )
        if result.returncode == 0:
            print(f"Created {device_type} on {runtime['identifier']}", file=sys.stderr)
            return True
    print(f"::error::could not create a simulator on {runtime['identifier']}", file=sys.stderr)
    return False


def main():
    best = pick()
    if best is None:
        print("No iPhone simulator installed; creating one.", file=sys.stderr)
        if not create():
            return 1
        best = pick()

    if best is None:
        print("::error::could not find or create an iOS simulator", file=sys.stderr)
        subprocess.run(["xcrun", "simctl", "list", "devices"], check=False)
        return 1

    _, udid, name, runtime = best
    print(f"Using {name} on {runtime.rsplit('.', 1)[-1]}", file=sys.stderr)
    print(udid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
