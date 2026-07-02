# BDR-2. GPLv3 copyleft with a reserved trademark

Date: 2026-06-28
Type: Business / Legal

## Status

Accepted

## Context

Sprout handles sensitive family and health data, so users have to trust what the
app does with it. Open source makes that auditable. But "open source" alone would
let someone ship a closed, modified — possibly malicious — fork, or trade on the
Sprout name and icon. This is a licensing and brand decision, not a technical one.

## Decision

License the app under the **GNU General Public License v3.0** (`LICENSE`). GPLv3's
copyleft means any redistributed, modified version must also publish its source
under the GPL — a closed fork that hides what it does with user data is not
permitted.

Separately, the **name "Sprout" and the app icon/logo are reserved** and are *not*
covered by the GPL (`TRADEMARK.md`). This is narrow on purpose: forking, improving
and contributing are unrestricted — a fork only needs its own name and icon if it
is **published** in a way users could mistake for the official app. The app also
ships a `PRIVACY.md` stating that it collects nothing.

## Consequences

- The code is auditable and forks are kept honest — a redistributed version must
  show its source, which matters for a health/privacy app.
- **GPLv3 is incompatible with proprietary/closed reuse**; anyone building on
  Sprout inherits copyleft.
- Forks can exist and be improved freely; only a **published** version that could
  be confused with the official app needs its own name and icon. This protects
  users from impersonation without discouraging good-faith contributors.
- Distribution channels (e.g. Google Play) must be handled in a way
  compatible with GPLv3 and the reserved-trademark terms — see
  [docs/RELEASING.md](../RELEASING.md).

## Relationship to the privacy stance

This reinforces, but is distinct from, the data-handling decisions in
[ADR-0002 (local-first storage)](../adr/0002-local-first-on-device-storage.md) and
[ADR-0003 (no first-party backend)](../adr/0003-no-first-party-backend-user-owned-sync.md):
those keep data on the user's device; this keeps the *code* open so the claim is
verifiable.
