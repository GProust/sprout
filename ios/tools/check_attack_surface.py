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
  merged-permission pin. On a signed build — the one the iOS release workflow
  uploads — it also reads the entitlements the signature carries, which come
  from the provisioning profile as much as from `Sprout.entitlements`.

Both layers check the app's extensions too (ADR-0020). An extension is code that
ships inside the app and runs in a process of its own, so an unpinned one would
be a way in the app's own pins never look at: which extensions exist is pinned,
and each one's Info.plist, linked libraries and entitlements are held to the
same refusals as the app's.

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
    # The export-compliance answer (only the OS's own ciphers). Not a door;
    # pinned so that changing it is as visible as adding one.
    "ITSAppUsesNonExemptEncryption",
    "NSBluetoothAlwaysUsageDescription",
    # A running breastfeed on the lock screen (BDR-24, ADR-0020). Updated by the
    # app alone: no push, and `aps-environment` stays refused below.
    "NSSupportsLiveActivities",
    "UILaunchScreen",
    "UISupportedInterfaceOrientations",
    "UTExportedTypeDeclarations",
}

# The app's extensions — the whole list, by target name, with the one extension
# point each may plug into. A second extension is a door like any other: a line
# here, in the same commit, and a decision record (ADR-0020).
EXTENSIONS = {
    "SproutLiveActivity": "com.apple.widgetkit-extension",
}

# Every key an extension declares, and no others.
EXTENSION_INFO_PLIST_KEYS = {
    "CFBundleDisplayName",
    "CFBundleShortVersionString",
    "CFBundleVersion",
    "NSExtension",
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

# What signing adds on its own: the app's identity, whether a debugger may
# attach, and TestFlight's flag. Anything else in a signed build came from the
# provisioning profile rather than from Sprout.entitlements, which is the half
# the source layer cannot see.
SIGNING_ADDED_ENTITLEMENTS = {
    "application-identifier",
    "com.apple.developer.team-identifier",
    "get-task-allow",
    "beta-reports-active",
}

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


def check_extension_info(properties: dict, name: str, where: str, fail: Failures) -> None:
    """One extension's Info.plist: the pinned keys, the refusals, and the one
    extension point it is allowed to plug into."""
    declared = set(properties)
    for extra in sorted(declared - EXTENSION_INFO_PLIST_KEYS):
        fail.add(f"{where} declares {extra}, which is not in the pinned list")
    for missing in sorted(EXTENSION_INFO_PLIST_KEYS - declared):
        fail.add(f"{where} no longer declares {missing} — pin it or drop it from the list")

    nested: set = set()
    walk_keys(properties, nested)
    for key, why in FORBIDDEN_INFO_KEYS.items():
        if key in nested:
            fail.add(f"{where} declares {key} — {why}")

    point = properties.get("NSExtension", {}).get("NSExtensionPointIdentifier")
    if point != EXTENSIONS.get(name):
        fail.add(f"{where} plugs into {point}, expected {EXTENSIONS.get(name)}")


def check_extensions_source(project: dict, fail: Failures) -> None:
    targets = project["targets"]
    declared = {name for name, target in targets.items() if target.get("type") == "app-extension"}
    for extra in sorted(declared - set(EXTENSIONS)):
        fail.add(f"project.yml declares the extension {extra}, which is not in the pinned list")
    for missing in sorted(set(EXTENSIONS) - declared):
        fail.add(f"project.yml no longer declares the extension {missing} — pin it or drop it from the list")

    for name in sorted(declared & set(EXTENSIONS)):
        target = targets[name]
        properties = target.get("info", {}).get("properties", {})
        check_extension_info(properties, name, f"project.yml's {name}", fail)
        # An extension holds no entitlements at all: everything it does, it
        # does through the app (ADR-0020).
        settings = target.get("settings", {})
        layers = [settings.get("base", {})] + list(settings.get("configs", {}).values())
        if any("CODE_SIGN_ENTITLEMENTS" in (layer or {}) for layer in layers):
            fail.add(f"project.yml gives {name} entitlements; an extension holds none (ADR-0020)")


def check_entitlements(fail: Failures) -> None:
    with ENTITLEMENTS.open("rb") as handle:
        entitlements = plistlib.load(handle)
    for key in sorted(set(entitlements) - ALLOWED_ENTITLEMENTS):
        fail.add(f"Sprout.entitlements holds {key}, which is not in the allowed list")
    for key, why in FORBIDDEN_ENTITLEMENTS.items():
        if key in entitlements:
            fail.add(f"Sprout.entitlements holds {key} — {why}")


def check_linked_frameworks(app: pathlib.Path, fail: Failures) -> None:
    # An app's binary and an extension's are both named after their bundle.
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


def check_signed_entitlements(app: pathlib.Path, fail: Failures) -> None:
    # CI's simulator builds are made with CODE_SIGNING_ALLOWED=NO, so there is
    # no signature to read; only a bundle Xcode sealed has this.
    if not (app / "_CodeSignature" / "CodeResources").is_file():
        return
    try:
        result = subprocess.run(
            ["codesign", "-d", "--entitlements", "-", "--xml", str(app)],
            capture_output=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        fail.add(f"could not read the signature's entitlements: {error}")
        return
    entitlements = plistlib.loads(result.stdout) if result.stdout.strip() else {}

    # Signing may also write out the default keychain group: the app's own
    # identifier, which is the group it has anyway. That one value shares
    # nothing (ADR-0018); any other group is shared with something else.
    if entitlements.get("keychain-access-groups") == [entitlements.get("application-identifier")]:
        del entitlements["keychain-access-groups"]

    what = f"the signed {app.name}"
    for key in sorted(set(entitlements) - ALLOWED_ENTITLEMENTS - SIGNING_ADDED_ENTITLEMENTS):
        fail.add(f"{what} holds {key}, which neither Sprout.entitlements allows nor signing adds")
    for key, why in FORBIDDEN_ENTITLEMENTS.items():
        if key in entitlements:
            fail.add(f"{what} holds {key} — {why}")


def check_source(fail: Failures) -> None:
    # Imported here, not at the top: the built layer must not need it.
    import yaml

    project = yaml.safe_load(PROJECT.read_text())
    check_info(project["targets"]["Sprout"]["info"]["properties"], "project.yml", fail, pin=True)
    check_extensions_source(project, fail)
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
    check_signed_entitlements(app, fail)
    check_extensions_built(app, fail)


def check_extensions_built(app: pathlib.Path, fail: Failures) -> None:
    """What actually shipped inside the app's PlugIns folder, held to the pins."""
    plugins = app / "PlugIns"
    built = sorted(p for p in plugins.glob("*.appex")) if plugins.is_dir() else []
    names = {p.stem for p in built}
    for extra in sorted(names - set(EXTENSIONS)):
        fail.add(f"the built app carries the extension {extra}, which is not in the pinned list")
    for missing in sorted(set(EXTENSIONS) - names):
        fail.add(f"the built app no longer carries the extension {missing}")

    for appex in built:
        if appex.stem not in EXTENSIONS:
            continue
        plist = appex / "Info.plist"
        if not plist.is_file():
            fail.add(f"no Info.plist at {plist}")
            continue
        with plist.open("rb") as handle:
            info = plistlib.load(handle)
        ours = {k: v for k, v in info.items() if k not in BUILD_ADDED_KEYS}
        check_extension_info(ours, appex.stem, f"the built {appex.name}", fail)
        check_linked_frameworks(appex, fail)
        check_signed_entitlements(appex, fail)


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
