# 3. No first-party backend; sync through user-owned storage

Date: 2026-06-28

## Status

Accepted (principle) — concrete sync mechanism **Proposed**, to be finalized in a
later ADR.

## Context

Partners want to share the same data: both parents seeing and updating one baby's
record from their own phones. That is a real, desired feature.

At the same time, the publisher has hard constraints, stated up front:

- **Never hold user data.** We do not want custody of anyone's health data.
- **No commercial or operational cost.** No servers to pay for, scale, or keep
  alive for the life of the app.
- **No liability or compliance burden** (e.g. GDPR/health-data obligations that
  come with processing personal data on someone else's behalf).

A conventional approach — Firebase, or our own API + database — fails all three:
it puts user data in our custody, costs money, and makes us a data processor.

## Decision

The app will **never communicate with a publisher-operated backend.** We ship only
the app; we store nothing.

Any sync or backup is mediated through **storage the user already owns and
controls** — for example their own Google Drive via the Android **Storage Access
Framework (SAF)**, or another user-chosen file location. The two devices in a
couple exchange data through *their* storage, not ours. Sync is **opt-in** and
out of band; the app remains fully functional with it switched off.

The leading direction (not yet locked in) is: each device writes its own replica
file to the shared user-owned location, and the app merges replicas locally
(per-replica, last-writer-wins or append-merge) so no device clobbers another.
SAF is preferred over the Drive API because it needs **no Google Cloud project, no
OAuth client, and no app-held credentials** — the user grants access to a folder
and that's it. The exact format and merge strategy will get their own ADR once
designed.

## Consequences

- **Zero data custody, zero cost, zero compliance burden** for the publisher — the
  defining constraint is satisfied.
- **The user owns their data end to end**, including the synced copy.
- **Sync is genuinely harder to build.** We own conflict resolution and merge
  logic on-device instead of leaning on a server's consistency model.
- **The UX is more manual** (the user picks/authorizes a folder) and there is no
  real-time push between devices — sync happens when the app reads/writes the
  shared file, not instantly.
- **No server-side features** are possible (no push notifications from a backend,
  no central moderation, no server-side analytics) — consistent with
  [ADR-0002](0002-local-first-on-device-storage.md).

## Alternatives considered

- **Firebase / Firestore.** Rejected: data custody, recurring cost, vendor lock-in.
- **Self-hosted backend + API.** Rejected: cost, ops, and liability for the life of
  the app.
- **End-to-end-encrypted relay we operate.** Rejected: still our infrastructure and
  still our cost, even if we can't read the contents.
