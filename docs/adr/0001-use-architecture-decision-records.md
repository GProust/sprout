# 1. Use decision records (ADRs and BDRs)

Date: 2026-06-28

## Status

Accepted

## Context

Sprout is a small but evolving app, largely built in focused bursts. Several
foundational choices — local-only storage, no backend, an inclusive parent model —
were made deliberately and for specific reasons, but those reasons live only in
chat logs and commit messages. As the project grows (and as new contributors or a
future version of ourselves pick it back up), it's easy to forget *why* something
was done and to accidentally undo a decision that was load-bearing.

Not every such decision is *architectural*: some are product, domain, or business
rules (who counts as a "parent", what licence we ship under). Mixing those into one
"architecture" log muddies both.

## Decision

We will keep decisions as short Markdown records in the lightweight Nygard format
(Status / Context / Decision / Consequences), split across **two sibling logs**:

- **`docs/adr/` — Architecture Decision Records (ADR-NNNN):** technical and
  structural decisions (stack, persistence, build/CI, system design).
- **`docs/decisions/` — Business & Product Decision Records (BDR-NNNN):** product,
  domain, and business/legal rules.

Both logs use the same format and conventions. Once a record is **accepted
(merged)** it is immutable and its number is fixed: when a decision changes we add
a new record and mark the old one `Superseded by …` rather than editing or
renumbering it. (Until then — while a record is still a draft in review — numbers
may be tidied up.) Decisions that are genuinely both (e.g. *no first-party backend*
— a business constraint realised as an architectural choice) live in the ADR log
and are cross-linked from the BDR log.
constraint realised as an architectural choice) live in the ADR log and are
cross-linked from the BDR log.

## Consequences

- Significant decisions come with written rationale and explicit trade-offs.
- "Why is the **system** built this way?" and "why is the **domain** modelled this
  way?" are answered in separate, purpose-fit logs.
- There is a small, ongoing cost to writing a record when a real decision is made —
  intentional friction that keeps decisions deliberate.
- Occasionally a decision sits on the ADR/BDR boundary; we place it by its primary
  driver and cross-link, rather than duplicating it.
