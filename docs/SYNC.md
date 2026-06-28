# Cloud sync & sharing (design)

This document describes how Sprout will let two (or more) people **share the same
baby's data** and keep it backed up — using **Google Drive**, while staying
**offline-first** and **opt-in**.

> **Status:** the reconciliation engine and data model in this document are
> implemented and unit-tested (`app/src/main/java/com/gproust/sprout/sync/`).
> The Google Drive transport and the in-app opt-in toggle are **not wired up
> yet** — see [Roadmap](#roadmap). With sync turned off (the default), Sprout
> behaves exactly as before: fully local, no network, nothing leaves the device.

---

## 1. Why per-device files + reconciliation

Google Drive is great for *storing* data but has **no real-time push**: you
can't subscribe and get edits as they happen. So "almost simultaneous" editing
is achieved by **frequent polling**, not live streaming.

To avoid two people overwriting each other's single shared file, every device
writes **its own file** into a shared Drive folder:

```
Drive/ (a folder shared between the partners)
  Sprout/
    device-<deviceIdA>.json   ← written only by device A
    device-<deviceIdB>.json   ← written only by device B
```

- A device **only ever writes its own file** → no write conflicts on Drive.
- A device **reads everyone's files** and merges them locally → it sees others'
  changes.

This is a classic **state-based CRDT**: each file is one device's view of the
world, and merging any set of views in any order converges to the same result.

## 2. The merge rule (last-write-wins per record, with tombstones)

Every syncable row carries sync metadata:

| field | meaning |
|-------|---------|
| `syncId` | a stable **UUID** identifying the row across devices (not the local Room autoincrement id, which would collide between devices) |
| `updatedAt` | wall-clock millis of the row's last change |
| `deviceId` | the device that made that change (deterministic tie-breaker) |
| `deleted` | tombstone flag — a delete is a change, not an absence |

Merging groups all records by `syncId` and keeps the **winner**:

> winner = the record with the greatest `updatedAt`; ties broken by the greater
> `deviceId`.

Because the winner is chosen by a total order on `(updatedAt, deviceId)`, the
merge is **commutative, associative and idempotent** — i.e. order-independent
and safe to run repeatedly. That's what makes eventual consistency hold no
matter when each device last synced.

A delete wins if its `updatedAt` is newer than a competing edit, so deletions
propagate; an edit made *after* a delete (same or later clock) resurrects the
row, which is the intuitive behaviour for shared baby logs.

> **Clock note:** `updatedAt` is wall-clock time, so badly-skewed device clocks
> could mis-order near-simultaneous edits to the *same* row. For independent
> rows (the overwhelmingly common case here — two parents logging different
> feeds) there is no conflict at all. A Lamport/hybrid logical clock can be
> layered on later without changing the file format.

## 3. How it maps onto the app

- **Local source of truth stays Room.** Sync is a layer on top, not a rewrite.
- Each syncable entity gains `syncId` / `updatedAt` / `deleted` columns
  (additive migration; the local autoincrement `id` is kept for Room relations).
- On each sync tick the app:
  1. exports the local DB to a `DeviceSnapshot` and writes `device-<me>.json`;
  2. downloads the other `device-*.json` files from the shared folder;
  3. `Reconciler.merge(...)` combines them;
  4. the merged winners are written back into Room (applying tombstones).
- Polling cadence: frequent while the app is foregrounded (a few seconds),
  backing off in the background. Drive's *changes* API can later replace polling
  to cut bandwidth.

## 4. Privacy & F-Droid impact (opt-in)

- **Default: unchanged.** Sync is **off** until the user explicitly connects a
  Google account. With it off, Sprout makes **no network calls** and stores
  nothing remotely — see [`PRIVACY.md`](../PRIVACY.md).
- **When enabled:** the user's baby data is stored in **their own Google Drive**
  (and Drive's own privacy terms then apply). Sharing is done with Drive's
  native folder sharing, so the partner's copy lives in *their* Drive too.
- **F-Droid:** the Drive transport needs Google's proprietary sign-in / API
  client, which is **not FOSS**. To keep the F-Droid build clean, the Drive code
  will live behind the [`SyncBackend`](../app/src/main/java/com/gproust/sprout/sync/SyncBackend.kt)
  interface in a **separate, optional build flavor / module**, so the default
  (F-Droid) build ships **without** any Google dependency. This preserves the
  GPLv3 + F-Droid eligibility we set up in [`docs/RELEASING.md`](RELEASING.md).

## 5. Roadmap

- [x] Sync data model — `SyncRecord`, `DeviceSnapshot`.
- [x] Reconciliation engine — `Reconciler` (unit-tested).
- [x] JSON file format — `SnapshotJson` (round-trip tested).
- [x] Transport abstraction — `SyncBackend` interface (+ a local-folder
      implementation usable for tests and offline export/import).
- [ ] Schema: add `syncId` / `updatedAt` / `deleted` to syncable entities.
- [ ] Map Room entities ⇆ `SyncRecord` and apply merged state back to Room.
- [ ] Google Drive `SyncBackend` (Google Sign-In + Drive REST), in an optional
      flavor so the F-Droid build stays Google-free.
- [ ] Settings: opt-in toggle, account connect, shared-folder picker, status.
- [ ] Background sync scheduling (WorkManager) + foreground polling.

### Google Drive setup (for the transport PR)

The Drive backend will need a Google Cloud project with:
- the **Drive API** enabled,
- an **OAuth 2.0 client** for Android (package `com.gproust.sprout` + signing
  SHA-1), using the `drive.file` scope (access only to files the app creates),
- Google Sign-In configured in the optional flavor.

No secrets are committed; the OAuth client id is configured per build.

---

## 6. Mid-term vision: connectors & interoperability

Two separate seams grow out of this design. Keeping them apart is deliberate —
they have different audiences and different data shapes.

### 6a. Storage connectors ("different drives")

The [`SyncBackend`](../app/src/main/java/com/gproust/sprout/sync/SyncBackend.kt)
interface is intentionally just *list / read / write files in a folder*, so a new
provider is **one new class** — no change to the snapshot format or the reconciler:

| Connector | Notes |
|-----------|-------|
| Local folder | ✅ implemented (`FolderSyncBackend`); also powers plain export/import |
| Google Drive | first cloud target; proprietary → optional Google-only flavor |
| **WebDAV / Nextcloud** | FOSS-friendly → can ship in the default/F-Droid build |
| Dropbox / OneDrive / S3 | further proprietary options behind the same seam |

Because the merge is provider-agnostic, a user could even mix connectors (e.g.
back up to their own Nextcloud while sharing a folder with a partner on Drive).

### 6b. Clinical / human export ("for maternity, hospital, any doctor")

This is **not** the sync format. The device-snapshot JSON is internal and
lossless; a clinician wants a **curated, read-only, human-readable report** for a
chosen baby and date range:

- **CSV** — one file per log type (feeding/sleep/diaper/growth/wellbeing), easy
  to open in any spreadsheet. The CSV *generation* is pure Kotlin and unit-tested;
  no cloud needed.
- **Printable PDF / summary** — growth with WHO percentiles, feeding & sleep
  patterns, diaper counts; sharable via Android's share sheet.
- **Health interoperability (later)** — a FHIR-flavoured export is possible once
  the basics are in place, but CSV + PDF covers the immediate "hand it to the
  doctor" need.

The export path reads Room directly and is independent of any connector, so it
can land before — or entirely without — cloud sync.

