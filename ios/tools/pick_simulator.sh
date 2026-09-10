#!/usr/bin/env bash
#
# Print the UDID of an iOS simulator to build and test on.
#
# Not a device name. `-destination 'platform=iOS Simulator,name=iPhone 16'` is
# how every example writes it, and it is a hard dependency on one model existing
# on the runner — which broke the day the macOS image shipped with a different
# set. `xcodebuild build` degrades to a placeholder destination and carries on,
# so the build stayed green while the screenshot run, which needs a device it
# can actually boot, failed with "Unable to find a device matching the provided
# destination specifier". Green build, no screenshots, and nothing saying why.
#
# So: ask the machine what it has. Any iPhone on the newest installed runtime
# will do — nothing Sprout captures depends on the model — and if the image
# ships runtimes but no devices, make one rather than failing.
set -euo pipefail

pick() {
  xcrun simctl list devices available --json | python3 -c '
import json, re, sys

devices = json.load(sys.stdin)["devices"]

def version(runtime):
    match = re.search(r"iOS-([0-9]+)(?:-([0-9]+))?$", runtime)
    if not match:
        return None
    return (int(match.group(1)), int(match.group(2) or 0))

best = None
for runtime, entries in devices.items():
    order = version(runtime)
    if order is None:
        continue
    for device in entries:
        # An iPhone, and a plain one: the Pro and Max models differ only in
        # screen size, and a capture of a 6.9" phone is no more informative
        # than a capture of a 6.1" one.
        if not device.get("isAvailable") or not device["name"].startswith("iPhone"):
            continue
        rank = (order, "Pro" not in device["name"], device["name"])
        if best is None or rank > best[0]:
            best = (rank, device["udid"], device["name"], runtime)

if best:
    sys.stderr.write(f"Using {best[2]} on {best[3].rsplit('.', 1)[-1]}\n")
    print(best[1])
'
}

udid="$(pick)"

if [ -z "$udid" ]; then
  echo "No iPhone simulator installed; creating one." >&2
  runtime="$(
    xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"]
            if r.get("isAvailable") and r["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-")]
print(sorted(runtimes, key=lambda r: r["version"])[-1]["identifier"] if runtimes else "")
'
  )"
  if [ -z "$runtime" ]; then
    echo "::error::this runner has no iOS simulator runtime at all — nothing to build against" >&2
    xcrun simctl list runtimes >&2
    exit 1
  fi
  # The device type is far less likely to be missing than a specific model, and
  # if it is, the error names it.
  xcrun simctl create Sprout-CI com.apple.CoreSimulator.SimDeviceType.iPhone-16 "$runtime" >/dev/null \
    || xcrun simctl create Sprout-CI com.apple.CoreSimulator.SimDeviceType.iPhone-15 "$runtime" >/dev/null
  udid="$(pick)"
fi

if [ -z "$udid" ]; then
  echo "::error::could not find or create an iOS simulator" >&2
  xcrun simctl list devices >&2
  exit 1
fi

echo "$udid"
