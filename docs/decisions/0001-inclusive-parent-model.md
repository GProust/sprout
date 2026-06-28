# BDR-1. Capability-based, inclusive parent model

Date: 2026-06-28
Type: Product / Domain rule

## Status

Accepted

## Context

The first version modelled the adults as a fixed **mother / co-parent** role, and
attached postpartum questions (healing, bleeding, breast comfort) to "the mother".
That excludes or misgenders many real families: co-nursing lesbian couples,
adoptive parents, single parents, and any arrangement that doesn't map onto one
"mother" and one "co-parent".

This is a decision about the **domain** — who a "parent" is and how we ask about
their body and recovery — rather than about how the software is built.

## Decision

Describe each parent by **capabilities, not a role.** The per-device parent profile
stores booleans — `gaveBirth`, `breastfeeding` — plus an optional `deliveryType`
(vaginal / C-section). The daily check-in is **driven by those capabilities**:

- Everyone gets mood and notes.
- `gaveBirth` adds healing and bleeding questions.
- `breastfeeding` adds the breast-comfort question.
- The healing question is **worded by delivery type** ("incision" vs "perineal").

Onboarding asks these as plain questions about the specific baby ("Did you give
birth to <baby>?", "Are you breastfeeding <baby>?") rather than asking the person
to pick a label.

## Consequences

- The app fits co-nursing, adoptive, single, and other families without singling
  anyone out or assuming gender.
- Wellbeing is a single, per-parent log rather than role-specific tables, which
  also simplified the schema.
- The UI carries a little more conditional logic (which questions to show), but
  that logic is centralized and unit-tested as pure functions.
- The model is per-device, which is forward-compatible with user-owned sync
  ([ADR-0003](../adr/0003-no-first-party-backend-user-owned-sync.md)) — each phone
  carries its own parent identity.
