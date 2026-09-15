#!/usr/bin/env python3
"""The iOS half of ADR-0014: pin the way in, so a new door costs a line here.

`AttackSurfaceTest` does this for Android by pinning exact lists — the
permissions twice over, which components are exported, what the FileProvider
will hand out. ADR-0015 says the claim is "only checked on one platform" without
a sibling, and this is it.

It runs in two layers, for the same reason the Kotlin one checks both Sprout's
own manifest and the whole merged build:

* **The source layer** (no arguments) reads `project.yml` and
  `Sprout.entitlements` — what we wrote down. Cheap, needs no Mac, runs on every
  push.
* **The built layer** (`--app`) reads the `Info.plist` Xcode actually produced
  and the libraries the binary actually links. That is the half that catches
  something a dependency dragged in, which is the whole point of Android's
  merged-permission pin.

The two are separate modes rather than one cumulative run, and deliberately: the
built layer needs nothing but the standard library, because the macOS runners
refuse `pip install` (PEP 668) and a check that cannot run is not a check.

A legitimate new door is fine and costs one line in the list below, in the same
commit. A door nobody meant to open fails CI instead.
"""

import argparse
import json
import pathlib
import plistlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = ROOT / "ios" / "Sprout" / "project.yml"
ENTITLEMENTS = ROOT / "ios" / "Sprout" / "Sources" / "Sprout.entitlements"

# --- the pins -------------------------------------------------------------

# Every key Sprout declares, and no others. Adding one is a decision.
INFO_PLIST_KEYS = {
    "CFBundleDisplayName",
    "CFBundleDocumentTypes",
    "CFBundleLocalizations",
    "CFBundleShortVersionString",
    "CFBundleVersion",
    "NSBluetoothAlwaysUsageDescription",
    "UILaunchScreen",
    "UISupportedInterfaceOrientations",
    "UTExportedTypeDeclarations",
}

# Keys Xcode adds to the built plist on its own. Not decisions of ours, so the
# built layer ignores them rather than demanding they be pinned.
BUILD_ADDED_KEYS = {
    "BuildMachineOSBuild", "CFBundleDevelopmentRegion", "CFBundleExecutable",
    "CFBundleIdentifier", "CFBundleInfoDictionaryVersion", "CFBundleName",
    "CFBundleNumericVersion", "CFBundlePackageType", "CFBundleSignature",
    "CFBundleSupportedPlatforms", "DTCompiler", "DTPlatformBuild",
    "DTPlatformName", "DTPlatformVersion", "DTSDKBuild", "DTSDKName",
    "DTXcode", "DTXcodeBuild", "LSMinimumSystemVersion", "MinimumOSVersion",
    "UIDeviceFamily", "UILaunchScreen~ipad", "UIRequiredDeviceCapabilities",
    # Written by the asset catalog compiler once there is an app icon.
    "CFBundleIconName", "CFBundleIcons", "CFBundleIcons~ipad",
}

# The refusal list. Present at any depth and the build fails, whatever else the
# pins above say — the counterpart of Android's "nothing asks for a permission
# Sprout refuses".
FORBIDDEN_INFO_KEYS = {
    # There is no network, so nothing may describe how to use one.
    "NSAppTransportSecurity": "there is no networking to configure (ADR-0014)",
    "NSLocalNetworkUsageDescription": "the exchange is Bluetooth, never the LAN (ADR-0010)",
    "NSBonjourServices": "the exchange is Bluetooth, never the LAN (ADR-0010)",
    # No door that another app can knock on beyond the document type.
    "CFBundleURLTypes": "a URL scheme is a way in that nothing needs",
    # Background execution is a claim this app does not make (ADR-0019).
    "UIBackgroundModes": "nothing runs when the app is closed (ADR-0010, ADR-0019)",
    "BGTaskSchedulerPermittedIdentifiers": "nothing runs when the app is closed (ADR-0019)",
    # Sprout asks for none of these, and asking would be a product decision.
    "NSLocationWhenInUseUsageDescription": "location is never needed (ADR-0010)",
    "NSLocationAlwaysAndWhenInUseUsageDescription": "location is never needed (ADR-0010)",
    "NSContactsUsageDescription": "the household is phones, not contacts (ADR-0009)",
    "NSPhotoLibraryUsageDescription": "Sprout stores no photographs",
    "NSCameraUsageDescription": "pairing is a file, never a QR code (ADR-0008)",
    "NSMicrophoneUsageDescription": "Sprout records no audio",
    "NSHealthShareUsageDescription": "the record is Sprout's own, not HealthKit's",
    "NSHealthUpdateUsageDescription": "the record is Sprout's own, not HealthKit's",
}

# Entitlements Sprout may hold. Empty today, and the file says why.
ALLOWED_ENTITLEMENTS: set[str] = set()

# …and the ones that would undo the privacy claim outright.
FORBIDDEN_ENTITLEMENTS = {
    "com.apple.developer.icloud-services": "nothing of a baby's record goes to iCloud (ADR-0003)",
    "com.apple.developer.icloud-container-identifiers": "nothing of a baby's record goes to iCloud (ADR-0003)",
    "com.apple.developer.ubiquity-kvstore-identifier": "nothing of a baby's record goes to iCloud (ADR-0003)",
    "aps-environment": "there is no server to push from (ADR-0003)",
    "keychain-access-groups": "the household secret is reachable by Sprout alone (ADR-0018)",
    "com.apple.developer.associated-domains": "there is no website to be associated with",
    "com.apple.developer.networking.multicast": "the exchange is Bluetooth, never the LAN (ADR-0010)",
}

# Frameworks the binary must never link. `check_no_network.py` reads the source;
# this reads what actually got linked, which is the half a dependency could move.
FORBIDDEN_FRAMEWORKS = {
    "Network": "no sockets, at all (ADR-0014)",
    "CFNetwork": "no sockets, at all (ADR-0014)",
    "MultipeerConnectivity": "the exchange is Sprout's own protocol (ADR-0007)",
    "CloudKit": "there is no server (ADR-0003)",
    "HealthKit": "the record is Sprout's own",
    "CoreLocation": "location is never needed (ADR-0010)",
}

# The one document type Sprout offers, and its terms — the counterpart of the
# FileProvider pin. Anything a parent taps arrives through here.
EXPECTED_UTI = "com.gproust.sprout.sync"
EXPECTED_EXTENSION = "sprout"


class Failures:
    def __init__(self) -> None:
        self.lines: list[str] = []

    def add(self, message: str) -> None:
        self.lines.append(message)

    def report(self, what: str) -> bool:
        if not self.lines:
            print(f"{what}: the way in is what it says it is.")
            return True
        print(f"::error::{what}: the attack surface moved.")
        for line in self.lines:
            print(f"  - {line}")
        return False


def walk_keys(node, seen: set) -> None:
    """Every key at every depth — a forbidden one nested in a dictionary is
    still declared."""
    if isinstance(node, dict):
        for key, value in node.items():
            seen.add(key)
            walk_keys(value, seen)
    elif isinstance(node, list):
        for item in node:
            walk_keys(item, seen)


def check_info(properties: dict, where: str, fail: Failures, pin: bool) -> None:
    declared = set(properties)
    if pin:
        for extra in sorted(declared - INFO_PLIST_KEYS):
            fail.add(f"{where} declares {extra}, which is not in the pinned list")
        for missing in sorted(INFO_PLIST_KEYS - declared):
            fail.add(f"{where} no longer declares {missing} — pin it or drop it from the list")

    nested: set = set()
    walk_keys(properties, nested)
    for key, why in FORBIDDEN_INFO_KEYS.items():
        if key in nested:
            fail.add(f"{where} declares {key} — {why}")

    # The document type, in full.
    types = properties.get("UTExportedTypeDeclarations", [])
    identifiers = [t.get("UTTypeIdentifier") for t in types]
    if identifiers != [EXPECTED_UTI]:
        fail.add(f"{where} exports {identifiers}, expected exactly ['{EXPECTED_UTI}']")
    for declaration in types:
        tags = declaration.get("UTTypeTagSpecification", {})
        extensions = tags.get("public.filename-extension", [])
        if extensions != [EXPECTED_EXTENSION]:
            fail.add(f"{where} claims extensions {extensions}, expected ['{EXPECTED_EXTENSION}']")

    documents = properties.get("CFBundleDocumentTypes", [])
    for document in documents:
        content = document.get("LSItemContentTypes", [])
        if content != [EXPECTED_UTI]:
            fail.add(f"{where} opens {content}, expected exactly ['{EXPECTED_UTI}']")
        if document.get("CFBundleTypeRole") != "Viewer":
            fail.add(f"{where} takes a document role other than Viewer")


def check_entitlements(fail: Failures) -> None:
    with ENTITLEMENTS.open("rb") as handle:
        entitlements = plistlib.load(handle)
    for key in sorted(set(entitlements) - ALLOWED_ENTITLEMENTS):
        fail.add(f"Sprout.entitlements holds {key}, which is not in the allowed list")
    for key, why in FORBIDDEN_ENTITLEMENTS.items():
        if key in entitlements:
            fail.add(f"Sprout.entitlements holds {key} — {why}")


def check_linked_frameworks(app: pathlib.Path, fail: Failures) -> None:
    binary = app / app.stem
    if not binary.is_file():
        fail.add(f"no binary at {binary}")
        return
    try:
        linked = subprocess.run(
            ["otool", "-L", str(binary)],
            capture_output=True, text=True, check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        fail.add(f"could not read the binary's libraries: {error}")
        return

    for framework, why in FORBIDDEN_FRAMEWORKS.items():
        if f"/{framework}.framework/{framework}" in linked:
            fail.add(f"the binary links {framework} — {why}")


def check_source(fail: Failures) -> None:
    # Imported here, not at the top: the built layer must not need it.
    import yaml

    project = yaml.safe_load(PROJECT.read_text())
    check_info(project["targets"]["Sprout"]["info"]["properties"], "project.yml", fail, pin=True)
    check_entitlements(fail)


def check_built(app: pathlib.Path, fail: Failures) -> None:
    plist = app / "Info.plist"
    if not plist.is_file():
        fail.add(f"no Info.plist at {plist}")
    else:
        with plist.open("rb") as handle:
            built = plistlib.load(handle)
        # Xcode's own keys are not ours to pin; everything else must be.
        ours = {k: v for k, v in built.items() if k not in BUILD_ADDED_KEYS}
        check_info(ours, "the built Info.plist", fail, pin=True)
    check_linked_frameworks(app, fail)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--app",
        type=pathlib.Path,
        help="a built Sprout.app; checks what the build produced instead of what "
             "the source declares",
    )
    args = parser.parse_args()

    fail = Failures()
    if args.app:
        check_built(args.app, fail)
        what = "the built app"
    else:
        check_source(fail)
        what = "project.yml and the entitlements"
    return 0 if fail.report(what) else 1


if __name__ == "__main__":
    sys.exit(main())
