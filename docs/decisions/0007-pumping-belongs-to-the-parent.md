# BDR-7. Pumping is the parent's log, and the stash only counts usable milk

Date: 2026-08-15
Type: Product / Domain

## Status

Accepted

## Context

Sprout tracks what goes *into* a baby (feeds) but had nowhere to record milk
coming *out* of a parent. A parent who expresses milk needs three things at the
moment they finish pumping: **when**, **how much**, and **where they just put
it** — and later, the question that actually matters at 3 a.m.: *how much milk
is in the fridge right now?*

Two modelling questions came with it.

**Who owns a pumping session?** Every other tracked log
([BDR-3](0003-multiple-babies.md)) is stamped with the active baby: feeds,
sleep, diapers, growth, treatments. Milk is different — it is expressed by one
body, and with twins the same bottle can end up feeding either of them.
Attaching sessions to the active baby would split one stash into two lists and
would delete half of it with the baby ([BDR-3](0003-multiple-babies.md) allows
deleting a baby and all of their logs).

**What is "in the stash"?** Milk does not keep forever, and a total that adds up
every batch ever expressed is worse than no total at all: it says there are
800 ml available when half of it has been drunk or has been sitting in the
fridge for a week.

## Decision

**Pumping belongs to the parent, like the wellbeing check-in
([BDR-1](0001-inclusive-parent-model.md)) — not to a baby.**

- The `pumping` table has **no `babyId`**. There is one stash per device, shared
  by however many babies are being tracked, and deleting a baby leaves it
  untouched.
- The screen is reached from the dashboard's log list, not the bottom bar, which
  stays the five baby-centred screens.

**A batch records where it went, and the stash only counts what can be given.**

- Storage is `FRIDGE`, `FREEZER`, `ROOM` or `USED`. `USED` is the milk that never
  went into storage or has since been drunk or discarded — kept in the history,
  out of the stash. Re-opening an entry is how a bottle moves from fridge to
  freezer, or gets corrected.
- **Marking a batch used is one tap from the list**, not a trip through the
  editor: it is the thing a parent does to the stash several times a day, and it
  should cost less than logging the feed it goes with. It is *not* confirmed
  first — a confirmation on a routine action trains people to tap through it —
  but the snackbar offers an **undo** that puts the batch back exactly where it
  was, which a confirmation dialog could not do for a tap noticed a second late.
- Totals **leave out used milk, and milk kept past its storage guidance** — the
  widely published "about 4 hours at room temperature, 4 days in the fridge,
  6 months in the freezer" figures (CDC; Santé publique France gives the same).
  Each entry shows how long it still keeps, or that it is past that date.
- Sprout **never deletes or hides a batch** on its own. The guidance moves it out
  of a *total*; the entry stays exactly where the parent left it.

**No new opt-in switch.** The shortcut is on the dashboard for everyone, not
gated on the profile's `breastfeeding` flag: a parent who exclusively pumps may
well have answered "no" to *are you breastfeeding?*, and hiding the feature from
precisely the parent who needs it most is the worse failure.

## Consequences

- The stash is honest by construction: what it shows is what can be given today.
  The flip side is that a total can shrink on its own, as batches age out — which
  is the intended reading, but is worth knowing before it surprises someone.
- The keep-by dates are *general* guidance. A premature or hospitalised baby is
  often given stricter advice, so the wording stays a "rough guide" and no alert,
  notification or automatic deletion is ever built on top of it.
- Sharing one stash across babies is right for twins, and would need revisiting
  only in the unlikely case of two lactating parents on one device — an
  ambiguity that partner sync ([ADR-0003](../adr/0003-no-first-party-backend-user-owned-sync.md))
  would have to settle anyway.
- Storage is a single place per batch rather than a history of moves, so "pumped
  Monday, frozen Tuesday" dates the keep-by from the pumping time. That is the
  conservative direction (it expires sooner, never later) and keeps one entry per
  session.
