# 7. Partner sync by direct device-to-device exchange

Date: 2026-08-15

## Status

Accepted (model and phasing) — the phase 2 transport is deliberately left open,
see *Phase 2* below.

Finalizes the mechanism left open by
[ADR-0003](0003-no-first-party-backend-user-owned-sync.md).

## Context

[ADR-0003](0003-no-first-party-backend-user-owned-sync.md) settled the principle —
**we never hold user data, and we run no backend** — but explicitly left the
mechanism "Proposed, to be finalized in a later ADR". Its leading direction was a
replica file in user-owned cloud storage, reached through the Storage Access
Framework.

That direction still puts the family's health data in a third-party account (a
Drive), which is one more place it can leak from, one more thing to explain in
[PRIVACY.md](../../PRIVACY.md), and one more dependency for a feature that is
otherwise entirely local. Both phones are usually in the same home, often in the
same room — the data has no reason to travel further than the distance between
the two parents.

So: exchange the data **directly between the two devices**, with no storage in
between at all.

The transport, though, is the easy half. The hard half is that the database is
not currently mergeable:

- Every syncable entity uses `@PrimaryKey(autoGenerate = true) val id: Long` — a
  **local SQLite counter**. Feeding `#7` on one phone and feeding `#7` on the
  other are unrelated rows. Merging two databases in this state either overwrites
  real entries or duplicates them.
- Nothing records **when** a row last changed, so two concurrent edits cannot be
  arbitrated.
- Deletes are hard `DELETE`s. With no tombstone, a row deleted on phone A is
  **resurrected** by phone B's copy at the next exchange — and stays resurrected
  forever.

No transport fixes any of that. The data model has to come first.

## Decision

Partner sync is **a merge of two replicas exchanged directly between devices**,
built in three phases. Phase 0 is the data model, phase 1 is a working sync over
a transport that needs no permissions, phase 2 is the same sync made automatic.

### The merge model

Sync is not a protocol with a leader; it is a **commutative, idempotent merge**
of whatever payload arrives. Applying the same payload twice changes nothing,
and A-then-B produces the same database as B-then-A. That property is what lets
an exchange be interrupted, repeated, or run out of order without damage.

Every syncable row carries:

- **`uid`** — a globally unique, stable identifier (UUIDv4), assigned at insert
  and never reused. The existing `id: Long` stays as the local key so Room
  relations and existing queries are untouched; `uid` is the identity *across*
  devices.
- **`updatedAt`** — epoch millis of the last local write, used to arbitrate
  concurrent edits.
- **`deletedAt`** — a tombstone. Deleting sets it instead of removing the row;
  every query already scoped by baby also filters `deletedAt IS NULL`.

Merge rules, per row, matched on `uid`:

| Situation | Result |
|---|---|
| Incoming `uid` unknown locally | insert it |
| Known, incoming `updatedAt` newer | overwrite the local row |
| Known, incoming `updatedAt` older or equal | keep the local row |
| Either side has a tombstone | **the tombstone wins**, regardless of time |

The logs (feeding, sleep, diaper, growth) are effectively an append-only journal:
rows are created once and rarely edited, and they never conflict semantically —
two parents logging two feeds is two feeds, not a conflict. Union-with-tombstones
is therefore enough, and a heavier CRDT would buy nothing. Last-writer-wins on
the rare edit can lose a concurrent keystroke; for this data that is an
acceptable trade against the complexity of field-level merging.

Clock skew between two phones is real but bounded (both are network-time synced),
and the cost of getting an arbitration wrong is one overwritten note — so wall
clock is used rather than a Lamport/hybrid clock.

### What syncs, and what does not

The line is **whose data it is**, not which table it lives in: *the baby's care
is shared; a parent's own body and their phone's setup are not.*

**Synced** — `baby`, `feeding`, `sleep`, `diaper`, `growth`, `treatment`.

**Synced by default, and switchable off** — `pumping`.

`pumping` is shared even though
[BDR-0007](../decisions/0007-pumping-belongs-to-the-parent.md) established that
it belongs to the parent rather than to a baby. That record is about *not
attaching a `babyId`* — with twins the stash feeds either of them — and not about
privacy. The milk stash is precisely what the partner needs to see: they are the
one giving the bottle, and marking it used has to be visible on both phones or
the stash count is wrong on one of them.

But it is still a log of what one body produced, and how much. Some parents
will want the stash shared and some won't, and neither is unreasonable — so it
is **the one part of the sync with a switch**, on by default and offered
explicitly at pairing rather than buried as a silent default. It is a
**device-local preference** (SharedPreferences, like
[`GrowthSpurtSettings`](../../app/src/main/java/com/gproust/sprout/ui/settings/GrowthSpurtSettings.kt)),
never itself synced, so each parent answers for their own phone.

The switch governs the **send** side — what leaves this device — because that is
the half a person can actually control. A payload that carries no pumping rows
merges exactly like one that does; the union is over whatever arrived, so
**asymmetric sharing is a normal state**, not an error to reconcile. One phone
sharing its stash while the other doesn't simply means one of the two sees a
partial stash.

Turning the switch off is **prospective only**: it stops future sends, it does
not reach into the other phone and retract what is already there. The UI must
say so plainly at the moment it is switched off — a toggle that implies a recall
it cannot perform is worse than no toggle.

**Never synced** — `wellbeing` (the parent's own mood, bleeding, recovery and
breast comfort), `parent_profile`, per-device settings (language, reminder
defaults, growth-spurt alerts) and `activeBabyId`.

`wellbeing` deliberately gets **no switch**. A setting someone can be asked to
turn on is a setting they can be pressured into turning on, and the postpartum
check-in — mood, bleeding, healing — is exactly the data where that pressure
would be most damaging. "The app doesn't do that" protects a person in a way
"it's off in my settings" does not.

`parent_profile` is already per-device by construction, as
[`Entities.kt`](../../app/src/main/java/com/gproust/sprout/data/local/Entities.kt)
notes: each phone carries its own parent identity. Wellbeing stays local for the
same reason it exists at all — it is the check-in a person does about their own
recovery, and a tracker that quietly shows it to the other parent is a different,
worse product.

### Pairing and encryption

Two devices become a household by **scanning a QR code**: the payload carries a
household id and a 256-bit shared secret, generated on the first device and kept
in the **Android Keystore** on both. There are no accounts and nothing to
register.

Every exchanged payload is **encrypted with AES-GCM** under that secret, with a
fresh nonce per payload. This matters most in phase 1, where the file crosses a
third-party channel (a messaging app, Quick Share): what transits is an opaque
blob that only the paired phone can open. It stays in place for phase 2 so the
transport never has to be trusted.

### The three phases

The phases are ordered so that each one is useful on its own, and so that nothing
built in an earlier phase is discarded by a later one. **If phase 2 is never
built, phase 1 is still a complete feature.**

---

#### Phase 0 — Make the data mergeable

Invisible to the user; required by every possible transport. This is the phase
that is expensive to get wrong, because it rewrites how rows are identified and
deleted.

1. Add `uid`, `updatedAt` and `deletedAt` to the synced entities (`baby`,
   `feeding`, `sleep`, `diaper`, `growth`, `treatment`, `pumping`), with a unique
   index on `uid`.
2. Write **Room migration 13 → 14**: create the columns, then **backfill a UUID
   for every existing row** and stamp `updatedAt`. Per
   [ADR-0002](0002-local-first-on-device-storage.md) this is hand-written with no
   destructive fallback; per
   [ADR-0006](0006-ci-as-build-verifier-and-screenshots.md) it is CI that proves
   the schema, so the migration test is part of this phase, not a follow-up.
3. Move the repository to **soft deletes**: `delete` sets `deletedAt` and bumps
   `updatedAt`; every read filters `deletedAt IS NULL`. This touches every DAO
   query and is the main regression risk of the phase — the "permanently delete a
   baby and all of their logs" path in particular must keep genuinely erasing.
4. Stamp `uid` on insert and `updatedAt` on every write, in one place in
   `SproutRepository` rather than at each call site.
5. Generate and persist a **device id** on first run.
6. Add **compaction**: tombstones older than a retention window are physically
   removed, so soft deletes don't grow the database forever.

Ships nothing user-visible. The app behaves exactly as before.

---

#### Phase 1 — Exchange a replica file, by hand

The first working sync. **Zero new permissions, zero network code.**

1. Define the **payload format**: a versioned document (rows + tombstones +
   schema version), serialized, compressed, then AES-GCM encrypted. Version it
   from the first byte — this format outlives phase 1.
2. **Pairing UI**: show a QR code on one phone, scan it on the other, store the
   secret in the Keystore. Pairing also **asks about the stash** — share
   expressed milk with the partner, yes or no — so the default is a choice
   rather than a discovery.
3. **The stash switch** in Settings, mirroring that answer: device-local, on by
   default, excluded from the payload when off, with copy at switch-off time
   saying that what has already been sent stays on the other phone.
4. **Export**: write the payload to a `.sprout` file in the app's cache and hand
   it to `ACTION_SEND`. The user picks the channel — Quick Share, Bluetooth,
   Signal, whatever they already trust.
5. **Import**: an intent filter for the file type, plus an in-app picker.
   Decrypt, verify the household id, refuse payloads from an unpaired household,
   and **merge** with the rules above.
6. **Show what happened**: how many entries came in, and what was already known.
   A merge that reports nothing is indistinguishable from a merge that failed.
7. Refuse gracefully across schema versions: a newer payload on an older app must
   say so rather than half-apply.

This is a manual, "sneakernet" sync — but because the merge is idempotent and
bidirectional, sending the file both ways genuinely converges the two phones. It
is also the only mode that works when the parents are **not** in the same place.

---

#### Phase 2 — Make it automatic, on the local network

Same payload, same merge, no user file handling: the two paired phones find each
other when they are on the same network and exchange replicas.

1. Pick the transport in its **own ADR**. The two candidates:
   - **`NsdManager` (mDNS) + a TCP socket** on the shared Wi-Fi — much simpler,
     but requires both phones on the same network, and some home routers isolate
     clients.
   - **Wi-Fi Direct (`WifiP2pManager`)** — works with no network at all, but the
     API is painful and it needs `NEARBY_WIFI_DEVICES` / location permissions.

   **Google Nearby Connections is excluded**: it would drag Play Services into an
   app that currently has no Google dependency, and would break non-Play builds.
2. Add the permission the chosen transport needs, and **update
   [PRIVACY.md](../../PRIVACY.md) and the README accordingly** — this is not a
   detail. PRIVACY.md currently states that Sprout requests *"no sensitive
   runtime permissions and no internet permission"*, and any IP transport makes
   `android.permission.INTERNET` appear on the Play listing. The claim that no
   data reaches us stays true; the sentence about permissions does not, and
   shipping phase 2 without rewriting it would make the policy false.
3. Discovery restricted to the paired household, exchange still encrypted, and
   the whole thing **opt-in and switchable off** — with it off, the app is
   exactly the phase 1 app.

---

## Consequences

- **Nothing is stored anywhere but the two phones.** No backend, no Drive, no
  account: the strongest form of the ADR-0003 constraint, and the simplest thing
  to state in the privacy policy.
- **Sync happens when the devices meet.** There is no real-time propagation, and
  none is possible without a relay. A parent at the office is a phase 1 file
  exchange, not a live feed. This is intrinsic to the decision, not a gap to be
  filled later.
- **We own the merge.** Conflict resolution, tombstones and compaction are ours
  to get right, with no server consistency model to lean on.
- **The two phones can legitimately hold different data.** With the stash switch
  off on one side, the milk stash is not a single shared number, and the app
  must never present it as one — no "you and your partner have 320 ml", and no
  warning that the databases disagree, because disagreeing is the point.
- **Phase 0 rewrites how rows are deleted** across every DAO — the riskiest
  change the app has taken, on data users cannot re-enter, validated only in CI
  ([ADR-0006](0006-ci-as-build-verifier-and-screenshots.md)).
- **Deleted rows survive as tombstones** until compaction. A "delete" is no
  longer instantly physical, which the permanent-delete path has to account for.
- **Losing both phones still loses everything.** This is sync, not backup;
  ADR-0002's consequence stands.
- **Phase 2 costs the "no internet permission" claim**, and that is a real cost
  in a product whose pitch is that it cannot phone home. It is the reason phase 2
  is separable and last.

## Alternatives considered

- **Replica file in the user's own Drive via SAF** (ADR-0003's leading
  direction). Rejected as the primary path: it reintroduces third-party storage
  for data that only needs to cross a room. It remains a natural *backup* story,
  which is a different feature.
- **Google Nearby Connections.** Rejected: Play Services dependency, see phase 2.
- **BLE as the transport.** Rejected: throughput too low for a full history, and
  the pairing complexity buys nothing over phase 2's options.
- **Full CRDT (per-field merge, vector clocks).** Rejected as over-engineering
  for an append-only log edited by two people who live together.
- **Syncing wellbeing along with the rest.** Rejected: see *What syncs*.
