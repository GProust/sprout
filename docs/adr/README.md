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
- Once an ADR is **accepted (merged)** it is immutable: don't rewrite history or
  renumber — if a decision changes, add a new ADR and set the old one's status to
  `Superseded by ADR-XXXX`.
- Statuses: `Proposed`, `Accepted`, `Deprecated`, `Superseded by ADR-XXXX`.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-use-architecture-decision-records.md) | Use decision records (ADRs and BDRs) | Accepted |
| [0002](0002-local-first-on-device-storage.md) | Local-first, on-device storage | Accepted |
| [0003](0003-no-first-party-backend-user-owned-sync.md) | No first-party backend; sync through user-owned storage | Accepted (mechanism finalized in [ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md)) |
| [0004](0004-native-android-compose-mvvm-room.md) | Native Android with Compose, MVVM and Room | Accepted |
| [0005](0005-localization-with-android-resources.md) | Localization with Android string resources | Accepted |
| [0006](0006-ci-as-build-verifier-and-screenshots.md) | CI as the build verifier, with screenshots in PRs | Accepted |
| [0007](0007-partner-sync-by-direct-device-to-device-exchange.md) | Partner sync by direct device-to-device exchange | Accepted (amended by [ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md) and [ADR-0009](0009-the-household-is-a-group-not-a-pair.md); phase 2 transport open) |
| [0008](0008-pairing-by-invitation-and-the-first-merge.md) | Pairing by invitation, and what the first merge does | Accepted (extended to households by [ADR-0009](0009-the-household-is-a-group-not-a-pair.md)) |
| [0009](0009-the-household-is-a-group-not-a-pair.md) | The household is a group, not a pair | Accepted |

> Product, domain, and business decisions live in the
> [Business & Product Decision Records](../decisions/) — including the inclusive
> parent model, supporting multiple babies, the system-language default, and
> licensing.
