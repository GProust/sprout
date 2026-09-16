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

### One decision, both apps

Sprout is one product on two platforms, and the records describe **the product**.
There is **one numbered sequence**, not one per app, and no decision is ever
taken twice — [ADR-0015](0015-native-ios-in-this-repository.md) settled that when
the second app arrived: *"they describe the product rather than either app.
Duplicated they diverge; cross-linked they rot."*

So, when writing a new record:

- **Default to `Both`.** What Sprout does, what it refuses to do, what travels
  between phones, and what a screen promises are product questions, and a second
  record for the other app would be a second answer waiting to disagree.
- **A platform-scoped record is for a mechanism the platform forces**, never for
  a behaviour. There have been three such forcings, and they are the whole list:
  iOS has no Room ([ADR-0017](0017-grdb-for-the-ios-database.md), the other half
  of [ADR-0004](0004-native-android-compose-mvvm-room.md)'s storage choice), no
  per-key backup exclusion
  ([ADR-0018](0018-where-ios-keeps-what-must-not-travel.md)), and nothing of ours
  runs when a notification fires
  ([ADR-0019](0019-a-reminder-decided-when-it-is-scheduled.md)).
- **Such a record names the shared one it applies**, in its first lines, and
  changes nothing that record decided. ADR-0018 and ADR-0019 are the shape to
  copy: each opens by saying which record it is applying and to which app.
- **A capability arriving on one platform first is not a scope.** It is the same
  decision, not yet built twice — say so in the record's consequences and leave
  the scope `Both`, the way
  [ADR-0016](0016-a-transport-both-platforms-can-speak.md) does. Which app has
  built what today belongs in a status line that moves; a decision record does
  not move.

The `Scope` column below is that rule, made visible. **No BDR is
platform-scoped**, and only four ADRs are — two of them the same storage question
asked of two databases.

## Index

| ADR | Title | Scope | Status |
|-----|-------|-------|--------|
| [0001](0001-use-architecture-decision-records.md) | Use decision records (ADRs and BDRs) | Both | Accepted |
| [0002](0002-local-first-on-device-storage.md) | Local-first, on-device storage | Both | Accepted |
| [0003](0003-no-first-party-backend-user-owned-sync.md) | No first-party backend; sync through user-owned storage | Both | Accepted (mechanism finalized in [ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md)) |
| [0004](0004-native-android-compose-mvvm-room.md) | Native Android with Compose, MVVM and Room | Android | Accepted (its "Android only" consequence reopened by [ADR-0015](0015-native-ios-in-this-repository.md)) |
| [0005](0005-localization-with-android-resources.md) | Localization with Android string resources | Both | Accepted |
| [0006](0006-ci-as-build-verifier-and-screenshots.md) | CI as the build verifier, with screenshots in PRs | Both | Accepted |
| [0007](0007-partner-sync-by-direct-device-to-device-exchange.md) | Partner sync by direct device-to-device exchange | Both | Accepted (amended by [ADR-0008](0008-pairing-by-invitation-and-the-first-merge.md), [ADR-0009](0009-the-household-is-a-group-not-a-pair.md) and [ADR-0010](0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md)) |
| [0008](0008-pairing-by-invitation-and-the-first-merge.md) | Pairing by invitation, and what the first merge does | Both | Accepted (extended to households by [ADR-0009](0009-the-household-is-a-group-not-a-pair.md)) |
| [0009](0009-the-household-is-a-group-not-a-pair.md) | The household is a group, not a pair | Both | Accepted |
| [0010](0010-automatic-exchange-over-bluetooth-when-the-app-is-open.md) | Automatic exchange over Bluetooth, while the app is open | Both | Accepted (transport and advertisement amended by [ADR-0016](0016-a-transport-both-platforms-can-speak.md)) |
| [0011](0011-what-survives-a-new-phone.md) | What survives a new phone | Both | Accepted (exclusion list amended by [ADR-0012](0012-the-widget-diagnostics-screen-comes-out.md)) |
| [0012](0012-the-widget-diagnostics-screen-comes-out.md) | The widget diagnostics screen comes out | Both | Accepted |
| [0013](0013-writing-the-pdf-and-the-workbook-by-hand.md) | Writing the PDF and the workbook by hand | Both | Accepted |
| [0014](0014-the-way-in-is-an-allow-list.md) | The way in is an allow-list, and untrusted bytes are fuzzed | Both | Accepted |
| [0015](0015-native-ios-in-this-repository.md) | Native iOS, in this repository | Both | Accepted |
| [0016](0016-a-transport-both-platforms-can-speak.md) | A transport both platforms can speak | Both | Accepted (Android implemented; iOS radio still to be written) |
| [0017](0017-grdb-for-the-ios-database.md) | GRDB for the iOS database, and Room's schema as the source | iOS | Accepted |
| [0018](0018-where-ios-keeps-what-must-not-travel.md) | Where iOS keeps what must not travel | iOS | Accepted |
| [0019](0019-a-reminder-decided-when-it-is-scheduled.md) | A reminder decided when it is scheduled | iOS | Accepted |

Two of those titles read as Android's and are not, which is what the column is
for:

- **[ADR-0005](0005-localization-with-android-resources.md)** decides where the
  text lives, and Android's string resources are that place *for both apps*:
  `ios/tools/strings_from_android.py` generates the iOS catalog from them and CI
  fails if the committed catalog is not what they produce. A translation is
  written once.
- **[ADR-0006](0006-ci-as-build-verifier-and-screenshots.md)** decides that CI is
  the build verifier and that screenshots are committed to the PR branch. It was
  written when there was one app and holds for both: `ci.yml` and `screenshots.yml`
  for Android, `ios.yml` for iOS, same rule, same reason — no local toolchain, and
  visual review built into the pull request.

> Product, domain, and business decisions live in the
> [Business & Product Decision Records](../decisions/) — including the inclusive
> parent model, supporting multiple babies, the system-language default, and
> licensing.
