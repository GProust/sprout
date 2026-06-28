# Architecture Decision Records

This directory records the significant architecture and product decisions behind
Sprout — what we decided, **why**, and what it costs us — so the reasoning isn't
lost to time or buried in pull-request threads.

We use the lightweight [Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
each record has a **Status**, **Context**, **Decision**, and **Consequences**.

## Conventions

- One decision per file, named `NNNN-short-title.md` (zero-padded, monotonic).
- ADRs are **immutable**. Don't rewrite history — if a decision changes, add a new
  ADR and set the old one's status to `Superseded by ADR-XXXX`.
- Statuses: `Proposed`, `Accepted`, `Deprecated`, `Superseded by ADR-XXXX`.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-use-architecture-decision-records.md) | Use Architecture Decision Records | Accepted |
| [0002](0002-local-first-on-device-storage.md) | Local-first, on-device storage | Accepted |
| [0003](0003-no-first-party-backend-user-owned-sync.md) | No first-party backend; sync through user-owned storage | Accepted (mechanism proposed) |
| [0004](0004-native-android-compose-mvvm-room.md) | Native Android with Compose, MVVM and Room | Accepted |
| [0005](0005-capability-based-inclusive-parent-model.md) | Capability-based, inclusive parent model | Accepted |
| [0006](0006-localization-with-android-resources.md) | Localization with Android string resources | Accepted |
| [0007](0007-ci-as-build-verifier-and-screenshots.md) | CI as the build verifier, with screenshots in PRs | Accepted |
| [0008](0008-multiple-babies-active-baby-model.md) | Multiple babies via an active-baby model | Accepted |
| [0009](0009-gplv3-copyleft-with-reserved-trademark.md) | GPLv3 copyleft with a reserved trademark | Accepted |
