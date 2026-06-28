# 2. Local-first, on-device storage

Date: 2026-06-28

## Status

Accepted

## Context

Sprout records some of the most sensitive data a person has: a newborn's feeding,
sleep and growth, and the birthing parent's postpartum health (mood, bleeding,
recovery, breast comfort). It is also used in the exhausting newborn fog, often
one-handed at 3 a.m., sometimes with no connectivity.

Three forces shape how we store this:

- **Privacy.** This data should not leave the user's device unless they explicitly
  choose to move it. Health data carries real sensitivity and real liability.
- **Simplicity and reliability.** No login walls, no "connect to continue", no
  spinner waiting on a server. The app must work fully offline.
- **Cost and custody.** As the publisher we do not want to hold user data or run
  paid infrastructure (see [ADR-0003](0003-no-first-party-backend-user-owned-sync.md)).

## Decision

All application data is stored **locally on the device** using **Room/SQLite**.
The core app has **no accounts, no analytics, no telemetry, and makes no network
calls**. Nothing is uploaded anywhere by default.

Schema evolution is handled with **explicit, hand-written Room migrations** (no
destructive fallback), so that an upgrade never silently drops a user's history.

## Consequences

- **Strong privacy by construction** — data physically cannot leak server-side
  because there is no server.
- **Fully offline, instant, no auth friction.**
- **Zero infrastructure cost and zero data-custody liability.**
- **No automatic backup or cross-device access** out of the box: if the device is
  lost or the app is uninstalled, local data is gone. This is the motivation for an
  *optional*, user-controlled sync/backup path — see
  [ADR-0003](0003-no-first-party-backend-user-owned-sync.md).
- **Migrations are our responsibility.** Every schema change needs a tested
  migration; getting one wrong risks user data, and our CI (not a local toolchain)
  is what validates the Room schema — see
  [ADR-0007](0007-ci-as-build-verifier-and-screenshots.md).

## Alternatives considered

- **Cloud-backed storage (Firebase / custom backend) as the source of truth.**
  Rejected: it inverts the privacy model, adds cost and custody liability, and
  breaks offline-first. See [ADR-0003](0003-no-first-party-backend-user-owned-sync.md).
