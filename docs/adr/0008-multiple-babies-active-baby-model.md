# 8. Multiple babies via an active-baby model

Date: 2026-06-28

## Status

Accepted

## Context

The first version assumed a single baby (a hardcoded row, `id = 1`, with logs that
had no baby reference). Real families have twins, or a second child a few years
later, and may want to retire or remove a child's data without wiping the app.

## Decision

Support several babies, with a single **active baby** selected at a time:

- `BabyEntity` gets an auto-generated `id` and an `archived` flag.
- The activity tables (`feeding`, `sleep`, `diaper`, `growth`) gain an indexed
  `babyId`; the parent profile stores `activeBabyId`.
- The **repository follows the active baby**: reads filter by it and writes are
  stamped with it, so individual screens/ViewModels don't thread a baby id around.
- The UI gets a **baby switcher** in the home top bar and a "Babies" manager to add,
  edit, switch, **stop tracking** (archive — reversible, keeps history), or
  **permanently delete** a baby together with all of its logs.
- Wellbeing stays **per-parent**, not per-baby — a birth and recovery belong to the
  person, not to a particular child
  ([BDR-0001](../decisions/0001-inclusive-parent-model.md)).
- The schema migration **backfills** existing logs to the current baby, so upgrades
  lose no data ([ADR-0002](0002-local-first-on-device-storage.md)).

## Consequences

- The app fits twins and growing families, and supports a clean "start over" via
  archive or delete.
- Every log read/write is now baby-scoped; the repository centralizes that so the
  rest of the code stays simple.
- "Stop tracking" (archive) and "delete" are distinct: archive is reversible and
  preserves history; delete is permanent and cascades the baby's logs.
- Choosing per-parent wellbeing means a second pregnancy years later is the same
  parent's continued record, not a new per-baby one.
