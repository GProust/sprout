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

## Sharing a baby's record between phones

Shipped, and the one part of the app with rules that are easy to break by
accident. The **why** is in
[ADR-0007](docs/adr/0007-partner-sync-by-direct-device-to-device-exchange.md)
(direct device-to-device merge),
[ADR-0008](docs/adr/0008-pairing-by-invitation-and-the-first-merge.md) (pairing
by invitation file, no QR; the first merge adopts),
[ADR-0009](docs/adr/0009-the-household-is-a-group-not-a-pair.md) (a household,
removal by rotating the secret) and
[ADR-0010](docs/adr/0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)
(Bluetooth rather than the local network; bounded, triggered discovery). Read
the relevant one before changing behaviour — what syncs, what doesn't, and how
conflicts resolve are decided there, not per-PR.

Four things that a change can quietly undo:

- **Deleting is two paths.** An ordinary delete flags the row (`deletedAt`);
  "permanently delete a baby" erases the rows and keeps only their uids in the
  `tombstone` table, so the deletion still travels without the data lingering.
  Both are compacted after `TOMBSTONE_RETENTION_DAYS`. Every read filters
  `deletedAt IS NULL` — a forgotten filter shows deleted entries again.
- **`SproutRepository` is the only place that stamps `uid`/`updatedAt`.** Keep
  it that way; a DAO called directly writes an unstamped row, which then loses
  every merge.
- **No `INTERNET` permission, ever.** It grants any socket at all, and its
  absence is the one privacy claim a user can check for themselves rather than
  take on trust. The same goes for `ACCESS_FINE_LOCATION`: `BLUETOOTH_SCAN` is
  declared `neverForLocation`, which is why the automatic exchange is offered on
  API 31+ only while `minSdk` stays 26.
- **Discovery is bounded and triggered** — a ~10 s window when the app comes to
  the foreground (throttled by `NearbyPolicy`) or an explicit *Sync now*. No
  background scan, no periodic job, no foreground service. Latency in minutes is
  the accepted trade for battery.

Anything user-visible here also touches `PRIVACY.md`, which carries its own
dated change log at the end.

## Historical — how it was built

Delivered in the order below; kept only as a map of which PR introduced what.

- **Phase 0 — make the data mergeable** (#64). `uid` / `updatedAt` / `deletedAt`
  on the synced entities, Room migration 13 → 14 with a UUID backfill, soft
  deletes across every DAO, device id, tombstone compaction.
- **Phase 1 — exchange a replica file by hand.** The engine (#65), then
  everything the user can see (#66).
- **Households — more than two phones** (#67). The merge already worked for any
  number of devices, so this was wording, a list of the phones heard from, and
  removal by rotating the shared secret.
- **Phase 2 — automatic exchange when the phones are near each other.** What CI
  can prove — beacon, session protocol, discovery policy (#69), then the radio
  and the screen.
