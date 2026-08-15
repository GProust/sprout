# Sprout — working notes

Offline-first Android newborn/postpartum tracker. Kotlin, Compose + Material 3,
MVVM, Room. No accounts, no network calls, no analytics.

## Before changing anything

- The **why** behind the architecture lives in [`docs/adr/`](docs/adr/), and the
  product/domain rules in [`docs/decisions/`](docs/decisions/). Read the relevant
  record before reopening a settled question; if a decision genuinely changes,
  add a new record instead of editing the old one (they are immutable once
  merged).
- There is **no local Android toolchain** — CI is the build verifier
  ([ADR-0006](docs/adr/0006-ci-as-build-verifier-and-screenshots.md)). Schema and
  migration correctness is only ever proven there, so treat migrations with
  matching care.
- Room migrations are **hand-written, with no destructive fallback**
  ([ADR-0002](docs/adr/0002-local-first-on-device-storage.md)). A user cannot
  re-enter this history.
- `CHANGELOG.md` is user-facing release notes, in the voice of the existing
  entries. Docs-only changes don't go in it.

## Work in progress — partner sync

Sharing one baby's record between two parents' phones, with **no server and no
external storage**: a direct device-to-device merge, specified in
[ADR-0007](docs/adr/0007-partner-sync-by-direct-device-to-device-exchange.md).
Read it before touching any of the three phases below — the merge rules and the
"what syncs / what doesn't" line are decided there, not per-PR.

Keep this checklist current as phases land, and **delete this whole section once
phase 2 ships** (or once the work is abandoned) — the ADR is the permanent
record; this is only the live status.

- [x] **Phase 0 — make the data mergeable.** `uid` / `updatedAt` / `deletedAt` on
  the synced entities, Room migration 13 → 14 with a UUID backfill, soft deletes
  across every DAO, device id, tombstone compaction. Nothing user-visible.
  Two things worth knowing before building on it:
  - **Deleting is now two paths.** An ordinary delete flags the row
    (`deletedAt`); "permanently delete a baby" erases the rows and keeps only
    their uids in the `tombstone` table, so the deletion still travels without
    the data lingering. Both are compacted after
    `TOMBSTONE_RETENTION_DAYS`. Every read filters `deletedAt IS NULL` — a
    forgotten filter shows deleted entries again.
  - `SproutRepository` is the only place that stamps `uid`/`updatedAt`. Keep it
    that way; a DAO called directly writes an unstamped row.
- [ ] **Phase 1 — exchange a replica file by hand.** Versioned encrypted payload,
  QR pairing into the Keystore, export via `ACTION_SEND`, import + idempotent
  merge, and a summary of what the merge actually did. Includes the **stash
  switch** — pumping is the one part of the sync the user can turn off, asked at
  pairing, device-local, send-side only. No new permissions.
  - **Open question, decide before writing the merge:** two parents who both
    tracked *before* pairing have no shared uids, so a first merge duplicates
    every entry they each logged. Options: seed one side from the other at
    pairing (simple, loses one side's pre-pairing history), match on
    (kind, timestamp) heuristically (risky), or accept the duplicates and let
    them be deleted by hand (honest, ugly). Not covered by ADR-0007.
- [ ] **Phase 2 — automatic exchange on the local network.** Same payload, same
  merge. Needs its own ADR to pick the transport (`NsdManager` + TCP vs Wi-Fi
  Direct; **not** Nearby Connections), and **`PRIVACY.md` + `README.md` must be
  rewritten in the same PR** — both currently promise no internet permission.

Phases are independently shippable and land as separate PRs. Phase 1 is a
complete feature on its own if phase 2 never happens.
