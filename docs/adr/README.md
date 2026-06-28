# Architecture Decision Records

This directory records the significant **architecture and technical** decisions
behind Sprout — what we decided, **why**, and what it costs us — so the reasoning
isn't lost to time or buried in pull-request threads.

Product, domain, and business decisions (who counts as a parent, what licence we
ship under, …) live in a sibling log: the
[Business & Product Decision Records](../decisions/).

We use the lightweight [Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions):
each record has a **Status**, **Context**, **Decision**, and **Consequences**.

## Conventions

- One decision per file, named `NNNN-short-title.md` (zero-padded, monotonic).
- ADRs are **immutable**. Don't rewrite history — if a decision changes, add a new
  ADR and set the old one's status to `Superseded by ADR-XXXX`. Numbers are never
  reused, so gaps are expected (e.g. records that were reclassified to the
  [decisions log](../decisions/)).
- Statuses: `Proposed`, `Accepted`, `Deprecated`, `Superseded by ADR-XXXX`.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-use-architecture-decision-records.md) | Use decision records (ADRs and BDRs) | Accepted |
| [0002](0002-local-first-on-device-storage.md) | Local-first, on-device storage | Accepted |
| [0003](0003-no-first-party-backend-user-owned-sync.md) | No first-party backend; sync through user-owned storage | Accepted (mechanism proposed) |
| [0004](0004-native-android-compose-mvvm-room.md) | Native Android with Compose, MVVM and Room | Accepted |
| [0006](0006-localization-with-android-resources.md) | Localization with Android string resources | Accepted |
| [0007](0007-ci-as-build-verifier-and-screenshots.md) | CI as the build verifier, with screenshots in PRs | Accepted |

> Reclassified to the [Business & Product Decision Records](../decisions/): the
> inclusive parent model ([BDR-0001](../decisions/0001-inclusive-parent-model.md)),
> the GPLv3 / trademark decision ([BDR-0002](../decisions/0002-gplv3-copyleft-reserved-trademark.md)),
> and supporting multiple babies ([BDR-0003](../decisions/0003-multiple-babies.md)).
> Their old ADR numbers (0005, 0008, 0009) are retired.
