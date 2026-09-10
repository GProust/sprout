#!/usr/bin/env python3
"""Fail the build if the iOS app can reach the network.

The counterpart of Android's missing `INTERNET` permission, and of
`SupportLinksTest` and `AttackSurfaceTest` (ADR-0014).

**The privacy claim is a claim about absence**, and absence is not what a feature
test notices going missing. On Android the guarantee has teeth because the
permission is simply not declared: the OS refuses the socket. iOS has no such
permission — any app may open a connection — so on this side the only way to keep
the same promise is to check that the code which would do it does not exist.

So this greps for every API that could open one. A new dependency, a copied
snippet, an "just fetch the supporter count" — each of them fails here, in the
same commit, rather than in a privacy policy that quietly stopped being true.

A legitimate exception would cost one line in `ALLOWED` **and a decision
record**, because it would be reopening ADR-0014, not tidying a lint rule.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = [ROOT / "Sprout" / "Sources", ROOT / "SproutKit" / "Sources"]

# Each entry is (pattern, what it would let the app do).
FORBIDDEN = [
    (r"\bURLSession\b", "open an HTTP connection"),
    (r"\bNSURLConnection\b", "open an HTTP connection"),
    (r"\bURLRequest\b", "build an HTTP request"),
    (r"\bCFStream\b", "open a socket"),
    (r"\bNWConnection\b", "open a socket (Network framework)"),
    (r"\bNWListener\b", "listen on a socket (Network framework)"),
    (r"\bNWBrowser\b", "browse the local network"),
    (r"\bGCDAsyncSocket\b", "open a socket"),
    (r"\bimport\s+Network\b", "use the Network framework"),
    (r"\bSocket\s*\(", "open a socket"),
    (r"\bData\s*\(\s*contentsOf:\s*URL\b", "fetch a URL synchronously"),
    (r"\bString\s*\(\s*contentsOf:\s*URL\b", "fetch a URL synchronously"),
    (r"\bMultipeerConnectivity\b", "join a peer-to-peer network"),
    (r"\bNSAppTransportSecurity\b", "relax transport security"),
]

# Nothing yet, and adding to this list is reopening ADR-0014.
ALLOWED: set[str] = set()


def main() -> int:
    findings = []
    scanned = 0

    for root in SOURCES:
        for path in sorted(root.rglob("*.swift")):
            relative = path.relative_to(ROOT).as_posix()
            if relative in ALLOWED:
                continue
            scanned += 1
            for number, line in enumerate(path.read_text().splitlines(), start=1):
                # A mention in prose is how this file's own rationale is written
                # down; only code counts.
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("///"):
                    continue
                for pattern, what in FORBIDDEN:
                    if re.search(pattern, line):
                        findings.append((relative, number, what, stripped))

    if findings:
        print("::error::Sprout must not be able to reach the network (ADR-0014).")
        for relative, number, what, line in findings:
            print(f"  {relative}:{number}: would {what}")
            print(f"      {line}")
        print()
        print("If this is deliberate it needs a decision record, not an exception.")
        return 1

    print(f"{scanned} Swift files, and none of them can open a connection.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
