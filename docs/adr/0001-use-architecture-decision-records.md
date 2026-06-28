# 1. Use Architecture Decision Records

Date: 2026-06-28

## Status

Accepted

## Context

Sprout is a small but evolving app, largely built in focused bursts. Several
foundational choices — local-only storage, no backend, an inclusive data model —
were made deliberately and for specific reasons, but those reasons live only in
chat logs and commit messages. As the project grows (and as new contributors or a
future version of ourselves pick it back up), it's easy to forget *why* something
was done and to accidentally undo a decision that was load-bearing.

## Decision

We will keep **Architecture Decision Records** in `docs/adr/`, one Markdown file
per decision, using the lightweight Nygard format (Status / Context / Decision /
Consequences).

ADRs are immutable once accepted. When a decision changes, we add a new ADR and
mark the previous one `Superseded by ADR-XXXX` rather than editing it in place, so
the history of reasoning stays intact.

## Consequences

- Significant decisions come with written rationale and explicit trade-offs.
- There is a small, ongoing cost to writing an ADR when a real decision is made —
  this is intentional friction that keeps decisions deliberate.
- The ADR log becomes the canonical answer to "why is it built this way?", which
  reduces re-litigation of settled questions.
