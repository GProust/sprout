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
- **Never per platform.** A BDR says what Sprout does and what it refuses to do,
  which is the same answer on an Android phone and on an iPhone — every record
  below applies to both apps, and none has a platform in its scope. Where a
  platform genuinely forces a different *mechanism* the record for it is an ADR
  ([ADR-0017](../adr/0017-grdb-for-the-ios-database.md),
  [ADR-0018](../adr/0018-where-ios-keeps-what-must-not-travel.md),
  [ADR-0019](../adr/0019-a-reminder-decided-when-it-is-scheduled.md) are the
  only three), and it changes nothing a BDR decided. The rule and its reasoning
  are in the [ADR log's conventions](../adr/README.md#one-decision-both-apps).
- A capability one app has first is **not** a second decision. It is the same
  one, not yet built twice.

## Index

| BDR | Title | Type | Status |
|-----|-------|------|--------|
| [0001](0001-inclusive-parent-model.md) | Capability-based, inclusive parent model | Product / Domain | Accepted |
| [0002](0002-gplv3-copyleft-reserved-trademark.md) | GPLv3 copyleft with a reserved trademark | Business / Legal | Accepted |
| [0003](0003-multiple-babies.md) | Support multiple babies | Product / Domain | Accepted |
| [0004](0004-default-to-system-language.md) | Default to the device's system language | Product / UX | Accepted |
| [0005](0005-growth-spurt-guidance.md) | Growth spurt periods: gentle guidance, opt-in alerts | Product / Domain | Accepted |
| [0006](0006-check-in-waits-on-the-dashboard.md) | The daily check-in waits on the dashboard, and can be stopped | Product / UX | Accepted |
| [0007](0007-pumping-belongs-to-the-parent.md) | Pumping is the parent's log, and the stash only counts usable milk | Product / Domain | Accepted |
| [0008](0008-statistics-and-the-who-growth-curves.md) | Statistics, and WHO curves read against both references | Product / Domain | Accepted |
| [0009](0009-the-dashboard-is-the-household.md) | The dashboard is the household; the active baby scopes the logs | Product / UX | Accepted |
| [0010](0010-the-bottom-bar-is-four-kinds-of-place.md) | The bottom bar is kinds of place, not kinds of log | Product / UX | Accepted |
| [0011](0011-donations-are-a-link-out-and-buy-nothing.md) | Donations are a link out, and they buy nothing | Business / Product | Accepted |
| [0012](0012-a-record-for-the-doctor.md) | A record for the doctor: the figures, and no interpretation | Product / Domain | Accepted |
| [0013](0013-protecting-the-file-that-leaves.md) | Protecting the file that leaves, and refusing the encryption that doesn't | Product / Domain | Accepted |
| [0014](0014-what-a-sleep-records-beyond-its-hours.md) | What a sleep records beyond its hours, and why it isn't graded | Product / Domain | Accepted |
| [0015](0015-medicine-given-when-needed-and-the-wait-between-doses.md) | Medicine given when it is needed, and the wait between doses | Product / Domain | Accepted |
| [0016](0016-a-running-wait-on-the-dashboard-and-a-dose-from-the-notification.md) | A running wait on the dashboard, and a dose from the notification | Product / UX | Accepted |
| [0017](0017-a-breastfeed-can-stop-and-carry-on.md) | A breastfeed can stop and carry on, and the break is not time at the breast | Product / Domain | Accepted |

See also the [Architecture Decision Records](../adr/) for technical decisions.
