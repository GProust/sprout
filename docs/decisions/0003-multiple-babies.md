# BDR-3. Support multiple babies

Date: 2026-06-28
Type: Product / Domain rule

## Status

Accepted

## Context

The first version assumed a single baby (a hardcoded row, `id = 1`, with logs that
had no baby reference). Real families have twins, or a second child a few years
later, and may want to retire or remove a child's data without wiping the app.

Whether the app tracks *one* baby or *several* is a product decision about who the
app is for. It also **ripples into other product rules**: there has to be a notion
of which baby you're currently looking at, every per-baby feature (feeding, sleep,
diapers, growth, and later treatments/medications) must be scoped to a baby, and
"removing" a baby needs a defined policy.

## Decision

Support **several babies**, with a single **active baby** selected at a time. The
product rules:

- A baby can be **added, edited, and switched between**; one is "active" and is what
  the home screen and logs show.
- Removing a baby has two distinct, deliberate options:
  - **Stop tracking** (archive) — reversible, **keeps the history**.
  - **Delete** — permanent, removes the baby and all of its logs.
- **Wellbeing stays per-parent**, not per-baby — a birth and recovery belong to the
  person, not to a particular child
  ([BDR-0001](0001-inclusive-parent-model.md)). A second pregnancy years later is
  the same parent's continued record, not a new per-baby one.
- Upgrading from the single-baby version must **lose no data**: existing logs are
  attributed to the baby that already existed.

## Technical realization

(The "how" — recorded here so the decision is self-contained; it leans on the
data architecture in [ADR-0002](../adr/0002-local-first-on-device-storage.md).)

- `BabyEntity` gets an auto-generated `id` and an `archived` flag; the activity
  tables gain an indexed `babyId`; the parent profile stores `activeBabyId`.
- The repository **follows the active baby** — reads filter by it and writes are
  stamped with it — so screens/ViewModels never thread a baby id around.
- The schema migration **backfills** existing logs to the current baby.

## Consequences

- The app fits twins and growing families, and supports a clean "start over" via
  archive or delete.
- Every per-baby feature added afterwards inherits the active-baby scoping for free
  (e.g. per-baby treatments).
- "Archive" vs "delete" is a permanent product distinction users must understand;
  the delete confirmation spells out that it is irreversible and cascades the logs.

## Relationship to other decisions

- Builds on the local-first data model — [ADR-0002](../adr/0002-local-first-on-device-storage.md).
- The per-parent wellbeing choice comes from the inclusive parent model —
  [BDR-0001](0001-inclusive-parent-model.md).
