#!/usr/bin/env python3
"""Checks the App Store signing material before anything is built with it.

    security find-identity -v -p codesigning <keychain> \\
      | python3 ios/tools/check_signing.py <profile.plist> [<bundle id> <secret name>]

`<profile.plist>` is the provisioning profile decoded with `security cms -D`;
standard input is what `security find-identity` says the imported certificate
became. On success this prints `team=…` and `profile=…` for `$GITHUB_OUTPUT`.

Without the last two arguments it checks the app's own profile. Every bundle
inside the upload is signed for its own identifier, so the Live Activity
extension (ADR-0020) has a profile of its own, checked by naming its bundle id
and the secret it came from.

Every failure below is one `xcodebuild` reports eventually, and badly: minutes
into the archive, as "No profiles for 'com.gproust.sprout' were found" or
"doesn't include signing certificate", without saying which of the three
secrets is the wrong one. Checked here first, the wrong secret is named in
seconds, along with what to make instead.

Standard library only, like the built layer of `check_attack_surface.py`: the
macOS runners refuse `pip install` (PEP 668).
"""

import datetime
import hashlib
import plistlib
import re
import sys

BUNDLE_ID = "com.gproust.sprout"

# The certificate type App Store builds are signed with since Xcode 11, and the
# CODE_SIGN_IDENTITY project.yml's Release configuration names.
IDENTITY_PREFIX = "Apple Distribution:"

# `security find-identity` prints one line per identity:
#   1) 0123…CDEF "Apple Distribution: Jane Doe (AB12CD34EF)"
IDENTITY_LINE = re.compile(r'^\s*\d+\)\s+([0-9A-Fa-f]{40})\s+"(.+)"\s*$')

PROFILE_SECRET = "APPLE_PROVISIONING_PROFILE_BASE64"
CERTIFICATE_SECRET = "APPLE_DISTRIBUTION_P12_BASE64"


def check(
    profile: dict,
    identities: list[tuple[str, str]],
    now: datetime.datetime,
    bundle_id: str = BUNDLE_ID,
    profile_secret: str = PROFILE_SECRET,
) -> list[str]:
    """Every reason this profile and these identities cannot sign the App Store build."""
    problems = []

    entitlements = profile.get("Entitlements", {})
    prefix = (profile.get("ApplicationIdentifierPrefix") or [""])[0]
    app_id = entitlements.get("application-identifier", "")
    if app_id != f"{prefix}.{bundle_id}":
        problems.append(
            f"{profile_secret} is a profile for {app_id or 'no app'}, not {bundle_id}. "
            f"Make one for the {bundle_id} App ID."
        )

    # An App Store profile names no devices and does not let a debugger attach;
    # a development or ad hoc one does both, and App Store Connect refuses a
    # build signed with it.
    devices = profile.get("ProvisionedDevices") or []
    if devices or profile.get("ProvisionsAllDevices") or entitlements.get("get-task-allow"):
        kind = "an in-house" if profile.get("ProvisionsAllDevices") else "a development or ad hoc"
        problems.append(
            f"{profile_secret} is {kind} profile"
            + (f" listing {len(devices)} device(s)" if devices else "")
            + ". Make one of type App Store Connect (Distribution)."
        )

    expires = profile.get("ExpirationDate")
    if isinstance(expires, datetime.datetime):
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=datetime.timezone.utc)
        if expires <= now:
            problems.append(f"{profile_secret} expired on {expires:%Y-%m-%d}. Make a new one.")

    # The profile names the certificates it may be signed with, by their DER
    # bytes; the keychain names its identities by the SHA-1 of the same bytes.
    allowed = {hashlib.sha1(der).hexdigest().upper() for der in profile.get("DeveloperCertificates", [])}
    distribution = [(sha1, name) for sha1, name in identities if name.startswith(IDENTITY_PREFIX)]
    if not identities:
        problems.append(
            f"{CERTIFICATE_SECRET} gave no valid signing identity. Export the certificate "
            "from Keychain Access as a .p12 with its private key, not the .cer alone."
        )
    elif not distribution:
        names = ", ".join(name for _, name in identities)
        problems.append(
            f"{CERTIFICATE_SECRET} holds {names}, not an Apple Distribution certificate."
        )
    elif not any(sha1.upper() in allowed for sha1, _ in distribution):
        problems.append(
            f"{profile_secret} was made for a different certificate than the one in "
            f"{CERTIFICATE_SECRET}. Edit the profile, tick this certificate, and download it again."
        )

    return problems


def parse_identities(text: str) -> list[tuple[str, str]]:
    return [m.groups() for m in map(IDENTITY_LINE.match, text.splitlines()) if m]


def main() -> int:
    if len(sys.argv) not in (2, 4):
        print(__doc__, file=sys.stderr)
        return 2
    with open(sys.argv[1], "rb") as handle:
        profile = plistlib.load(handle)
    bundle_id, profile_secret = (sys.argv[2], sys.argv[3]) if len(sys.argv) == 4 else (BUNDLE_ID, PROFILE_SECRET)

    problems = check(
        profile,
        parse_identities(sys.stdin.read()),
        datetime.datetime.now(datetime.timezone.utc),
        bundle_id,
        profile_secret,
    )
    for problem in problems:
        print(f"::error::{problem}", file=sys.stderr)
    if problems:
        return 1

    print(f"team={profile['TeamIdentifier'][0]}")
    print(f"profile={profile['UUID']}")
    expires = profile.get("ExpirationDate")
    if isinstance(expires, datetime.datetime):
        print(f"::notice::The App Store profile expires on {expires:%Y-%m-%d}.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
