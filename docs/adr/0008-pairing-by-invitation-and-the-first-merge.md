# 8. Pairing by invitation, and what the first merge does

Date: 2026-08-15

## Status

Accepted.

Amends [ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md) on
the pairing mechanism, and settles two things it left open. Everything else in
ADR-0007 — the merge rules, what syncs and what doesn't, the three phases —
stands unchanged.

## Context

ADR-0007 specified pairing as a **QR code**: one phone shows it, the other scans
it. Writing phase 1 made the cost of that visible, and it contradicts the same
ADR two paragraphs earlier.

Scanning needs a camera. That means `android.permission.CAMERA` on the Play
listing, and a scanner: CameraX plus a decoder — ZXing, since ML Kit would drag
in Play Services that ADR-0007 rules out for phase 2. So the phase promising
**"zero new permissions, zero network code"** would ship the most alarming
permission in the list, for a feature the user might never turn on, and
[PRIVACY.md](../../PRIVACY.md) would need the rewrite ADR-0007 scheduled for
phase 2 — in phase 1 instead.

Two other questions were left open, and both have to be answered before the
merge can be written at all:

- **Pre-pairing histories.** Two parents who each tracked *before* pairing share
  no uids, so a first merge duplicates every entry they both logged.
- **Erased rows.** ADR-0007 describes deletion as a `deletedAt` column, but
  "permanently delete a baby and all of their logs" promises the data is really
  gone. A flagged-but-present row would break that promise; an erased row has
  nothing left to carry its own tombstone.

## Decision

### Pairing travels the same way the data does

There is no QR code and no camera. Pairing is an **invitation file**, sent
through the channel the user already chose for the replicas themselves —
Quick Share, Bluetooth, a messaging app. One phone creates the invitation, the
other opens it, and they are paired. It reuses the phase 1 plumbing exactly, and
costs **no permission and no dependency**.

The invitation carries the household id and the shared secret, so it is the one
thing in this design that is worth intercepting. Two consequences we accept
deliberately:

- **The secret is only as private as the channel the user picks.** Signal or
  Quick Share is fine; a public forum is not. The UI says so at the moment of
  sending, in those words, rather than in a help page.
- **An invitation is single-use and short-lived.** It is accepted once, and
  expires if unused; a leaked invitation from last month pairs nobody.

After pairing, both phones show the same **six-character verification code**,
derived from the shared secret. Reading it aloud — the way Signal's safety
numbers work — is what turns "a file arrived" into "we are paired with each
other". It is not enforced, because a parent at 3 a.m. should not be blocked by
a ceremony, but it is offered.

This is weaker than a QR code, which never lets the secret leave the two
devices. We take that trade because the alternative spends a camera permission —
permanently, on every install, including the ones that never pair — to protect a
secret that the user is already trusting a channel with when they send their
baby's data through it minutes later.

### The first merge adopts rather than duplicates

At the first merge between two phones:

- **If one side has no baby data, it adopts the other's history wholesale.** This
  is the ordinary case — one parent has been tracking for weeks, the other has
  just installed — and it produces no duplicates and loses nothing.
- **If both sides have pre-pairing data**, the app asks, once, at pairing: keep
  everything (accepting that entries both parents logged appear twice, to be
  deleted by hand) or share only from the pairing forward. Both answers are
  honest; the app does not pick for them.

Explicitly rejected: **matching entries heuristically** on kind and timestamp.
It would work most of the time, and when it failed it would silently merge two
real entries into one — an invisible loss, on data nobody can re-enter. A
visible duplicate is a nuisance; a silent deletion is a betrayal.

### An erased row leaves a tombstone behind

An ordinary delete stays as ADR-0007 describes it: the row remains, flagged with
`deletedAt`.

Permanently deleting a baby erases the rows, and records **only their uids** in a
`tombstone` table — the table, the uid, the time. Enough for the partner's phone
to know not to send the entry back; nothing about what the entry contained. Both
are compacted on the same retention window.

(This shipped with phase 0, in [#64](https://github.com/GProust/sprout/pull/64);
this record is where the decision lives.)

## Consequences

- **Phase 1 keeps its promise**: no new permissions, no new dependencies, and
  `PRIVACY.md` stays true until phase 2 makes it false.
- **Pairing is one more file to send**, not a scan — slower and more manual, and
  it depends on the user picking a sane channel. The verification code is what
  makes a wrong pairing noticeable.
- **A leaked invitation is a real, if narrow, risk.** Single use and expiry keep
  the window small; the honest statement is that this design trusts the user's
  messaging app, and says so out loud.
- **The common pairing case is clean** — the new phone simply receives the
  history — and the awkward case is answered by the person whose data it is,
  once, rather than by a heuristic on every merge.
- **A permanent delete stays permanent**, at the cost of one small table that
  outlives the rows it describes.

## Alternatives considered

- **QR pairing** (ADR-0007's original). Rejected for phase 1: see above. If the
  app ever needs a camera for another reason, this is worth revisiting — the
  cryptography is genuinely better.
- **A typed pairing code.** No permission, no third-party channel, but a code
  short enough to type at 3 a.m. carries too little entropy to be the whole
  secret, even behind a slow derivation.
- **Bluetooth pairing between the two apps.** Needs `BLUETOOTH_CONNECT` and
  device discovery — the same permission problem in a less familiar form.
