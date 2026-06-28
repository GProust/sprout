# Business & Product Decision Records

This directory records **product, domain, and business decisions** — the *rules and
policies* of Sprout, as opposed to its technical structure. Things like "who counts
as a parent", "what licence we ship under", or "what we will and won't do with user
data" live here.

They share the same lightweight format as the
[Architecture Decision Records](../adr/) (Status / Context / Decision /
Consequences); the only difference is the subject. We keep them in a separate log so
that "*why is the **domain** modelled this way?*" and "*why is the **system** built
this way?*" don't get tangled together. Decisions that are genuinely both (e.g.
[ADR-0003 — no first-party backend](../adr/0003-no-first-party-backend-user-owned-sync.md),
which is driven by a business constraint but is an architectural choice) live in the
ADR log and are cross-linked from here.

## Conventions

- One decision per file, `NNNN-short-title.md`, referred to as **BDR-NNNN**.
- BDRs are immutable; supersede with a new record rather than rewriting.
- Statuses: `Proposed`, `Accepted`, `Deprecated`, `Superseded by BDR-XXXX`.

## Index

| BDR | Title | Type | Status |
|-----|-------|------|--------|
| [0001](0001-inclusive-parent-model.md) | Capability-based, inclusive parent model | Product / Domain | Accepted |
| [0002](0002-gplv3-copyleft-reserved-trademark.md) | GPLv3 copyleft with a reserved trademark | Business / Legal | Accepted |
| [0003](0003-multiple-babies.md) | Support multiple babies | Product / Domain | Accepted |
| [0004](0004-default-to-system-language.md) | Default to the device's system language | Product / UX | Accepted |

See also the [Architecture Decision Records](../adr/) for technical decisions.
