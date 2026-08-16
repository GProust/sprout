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

## Work in progress — sharing a baby's record

Sharing one baby's record between the phones of everyone looking after them —
usually two parents, sometimes a grandparent as well — with **no server and no
external storage**: a direct device-to-device merge, specified in
[ADR-0007](docs/adr/0007-partner-sync-by-direct-device-to-device-exchange.md)
and amended by
[ADR-0008](docs/adr/0008-pairing-by-invitation-and-the-first-merge.md) and
[ADR-0009](docs/adr/0009-the-household-is-a-group-not-a-pair.md). Read them
before touching anything below — the merge rules and the "what syncs / what
doesn't" line are decided there, not per-PR.

Keep this checklist current as work lands, and **delete this whole section once
phase 2 ships** (or once the work is abandoned) — the ADRs are the permanent
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
- [x] **Phase 1 — exchange a replica file by hand.** Landed in two PRs: the
  engine (#65), then everything the user can see (#66).
  [ADR-0008](docs/adr/0008-pairing-by-invitation-and-the-first-merge.md) settled
  the two questions it couldn't start without — **no QR code** (scanning needs a
  camera permission the phase promises not to add; pairing travels as an
  invitation file through the same channel as the data), and the first merge
  **adopts** rather than duplicates.
- [ ] **Households — more than two phones.**
  [ADR-0009](docs/adr/0009-the-household-is-a-group-not-a-pair.md): the merge
  already worked for any number of devices, so this is wording, a list of the
  phones heard from, and removal by **rotating the shared secret** — which locks
  out everyone until they are re-invited, including a phone that was merely
  switched off. Not part of the original three phases; folded in here because it
  changes what phase 2 will be built on.
- [ ] **Phase 2 — automatic exchange on the local network.** Same payload, same
  merge. Needs its own ADR to pick the transport (`NsdManager` + TCP vs Wi-Fi
  Direct; **not** Nearby Connections), and **`PRIVACY.md` + `README.md` must be
  rewritten in the same PR** — both currently promise no internet permission.

Each item is independently shippable and lands as its own PR. Phase 1 is a
complete feature on its own if phase 2 never happens.
