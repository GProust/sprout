# 17. GRDB for the iOS database, and Room's schema as the source

Date: 2026-09-10

## Status

Accepted.

Applies [ADR-0002](0002-local-first-on-device-storage.md) to the second app
([ADR-0015](0015-native-ios-in-this-repository.md)). Nothing about where data
lives changes: on the device, in SQLite, and nowhere else.

## Context

The iOS app needs the same database Android has: the same tables, the same
columns, the same soft-delete and uid columns the merge depends on
([ADR-0007](0007-partner-sync-by-direct-device-to-device-exchange.md)).

Three things make the choice narrower than it looks.

- **The schema already exists**, and it is defined by Room's migrations —
  fourteen of them, exported to `android/app/schemas/…/16.json`. Whatever iOS
  uses has to produce *that* schema, not a similar one, or the two apps' rows
  stop lining up.
- **Screens read streams.** Every Android screen collects a Room `Flow` and
  redraws when the data changes. A store without change observation would mean
  rebuilding that by hand in every view model.
- **Migrations are ours and users cannot recover from a bad one**
  ([ADR-0002](0002-local-first-on-device-storage.md)). This is the one part of
  the app where being clever is a liability.

## Decision

**GRDB**, with **Room's exported schema as the source of truth**.

The initial migration is the `CREATE TABLE` and `CREATE INDEX` statements from
Room's version 16, copied across rather than rewritten with a table builder, so
the two can be compared by reading them side by side. `SchemaTests` then checks
the built database against that same JSON file — table for table, column for
column, type and nullability included — so a drift fails CI rather than a merge.

iOS does **not** replay Android's fourteen migrations. There is no iPhone with an
older Sprout database on it, so there is nothing to migrate from: this starts at
16, and every change after it gets a migration of its own under the same rule
Android has — hand-written, no destructive fallback.

The data layer is a separate library target, `SproutData`, so `SproutKit` stays
dependency-free and buildable anywhere. Both are UI-free, and both are tested on
CI without a simulator.

### Why not the alternatives

- **SwiftData.** Apple's own, and the obvious first answer. Rejected: it owns its
  storage layout, so pointing it at a schema Room defines is working against it,
  and its migration model is declarative in a way that hides exactly what a
  hand-written migration is supposed to make explicit. The one place we want to
  see the SQL is the place it will not show us.
- **Raw SQLite via `libsqlite3`.** Dependency-free, which is in the spirit of
  [ADR-0013](0013-writing-the-pdf-and-the-workbook-by-hand.md). Rejected: that
  ADR is about *formats*, where a hand-written encoder is a few hundred lines and
  fully testable. A database layer is not that — it is a query builder, a record
  mapper and a change-observation mechanism, and hand-rolling the third one is
  where the bugs would be. Room is a dependency on Android; GRDB is its
  counterpart, not a departure.
- **Core Data.** Same objection as SwiftData, with more ceremony.

## Consequences

- **A third-party dependency**, the app's first. It is a well-established library
  with no transitive dependencies of its own, and it links no networking — which
  matters, because the iOS equivalent of "no `INTERNET` permission" is that no
  networking symbol is reachable at all
  ([ADR-0014](0014-the-way-in-is-an-allow-list.md)). A dependency that changed
  that would have to be caught by the entitlements test ADR-0015 calls for.
- **The two schemas are checked against each other on every run**, which is a
  stronger guarantee than either app had alone: Android's CI already refuses to
  let its exported schema go stale, and iOS now refuses to differ from it.
- **A schema change is now two changes.** Android's migration and JSON, then an
  iOS migration to match. `SchemaTests` fails until both are in, which is the
  intended order of discovery.
- **GRDB's `ValueObservation` replaces Room's `Flow`** one-for-one, so the
  repository reads the same on both sides and the view models can be written
  from the Kotlin without re-deriving how the data arrives.
- **`SproutData` cannot be used by the widget extension as-is** — a widget reads
  through an App Group container, which does not exist yet
  ([ADR-0015](0015-native-ios-in-this-repository.md) notes it as the one
  entitlement that will be added without a record).
