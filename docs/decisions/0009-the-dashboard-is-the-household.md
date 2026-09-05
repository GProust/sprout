# BDR-9. The dashboard is the household; the active baby scopes the logs

Date: 2026-09-05
Type: Product / UX

## Status

Accepted

Amends [BDR-3](0003-multiple-babies.md), which said the active baby "is what the
home screen and logs show". The logs half stands. The home screen half does not.

## Context

[BDR-3](0003-multiple-babies.md) gave the app several babies with **one active
at a time**, selected from a dropdown in the home screen's top bar, and had the
repository follow that selection: reads filter by it, writes are stamped with
it, and "screens/ViewModels never thread a baby id around". That is a clean
model and every per-baby feature since has inherited it for free.

It has one failure mode, and it lands on exactly the families the feature was
added for. With twins, the baby a feed belongs to is a **mode** — set on one
screen, read minutes later by the insert. `addFeeding` resolves
`activeBabyIdNow()` when it writes, so logging without first checking the
dropdown attributes the feed to whoever happens to be selected. Nothing on the
logging screen shows which baby is active, the mistake is silent, and with feeds
minutes apart it is close to impossible to reconstruct afterwards.

The dashboard was also the wrong shape for the job it actually gets. It opened
on a greeting, a check-in card and a growth-spurt note, with the seven logging
shortcuts below the fold as identical full-width buttons — while the thing being
done fifteen times a day, at night, one-handed, was five actions away.

## Decision

**Home shows every tracked baby. The active baby still scopes the logs.**

- The dashboard lists **one card per tracked baby**, in birth order, each with
  its own "how long since" answers and its own actions. Tapping a card's feed
  button sets that baby active **and awaits it** before starting the session, so
  the write cannot land on a sibling.
- **The screen composes by baby count.** With one baby there is nothing to
  disambiguate, so the single card opens out in place into the full baby view —
  log grid and all — and nothing is a tap further than it was. With two or more,
  cards collapse to summaries and the per-baby view gets its own tab.
- **The active-baby model is untouched below the UI.** Every log flow is still
  `activeBabyId.flatMapLatest { … }`; the log screens are still scoped; opening
  a baby still sets the selection. What is new is *one* cross-baby read for the
  dashboard — per-baby summaries, not histories — and one write that takes an
  explicit baby id.
- **The dashboard read is a summary, and bounded.** `HOUSEHOLD_WINDOW_MS` is a
  week. Past it a baby has no "last fed" to show, which is the honest answer;
  histories stay in the log screens where they belong.
- **A quick breastfeed starts from the card**, on the breast the last session
  did not begin on — the rule the widget already had, reused rather than
  restated, with the other side offered beside it. It is a default, never a
  rule.
- **What is running says so.** A live breastfeed was already persisted and
  already shown on the launcher widget, but never in the app itself; an
  unfinished sleep was counted as running without being mentioned. Both now
  appear at the top of the dashboard.

## Consequences

- The mis-attribution failure is designed out rather than warned about: there is
  no global "current child" involved in logging a feed from the dashboard.
- Families with one baby see a dashboard that is strictly better than the old
  one and no deeper. Families with two or more get a screen that finally
  admits there are two.
- The repository grows a small, clearly-labelled exception to "everything
  follows the active baby". It is three flows, and they are summaries.
- The check-in card moves below the babies. Still on the dashboard, still
  dismissible, still nothing standing between a parent and the app
  ([BDR-6](0006-check-in-waits-on-the-dashboard.md) is unaffected in substance).
- The top bar's baby switcher is gone, because the dashboard *is* the switcher.
- Archive and delete are unchanged: the dashboard lists active babies, and
  archiving still hides one without touching its history
  ([BDR-3](0003-multiple-babies.md)).

## Relationship to other decisions

- Amends the home-screen clause of [BDR-3](0003-multiple-babies.md).
- The bar that carries the two views is [BDR-10](0010-the-bottom-bar-is-four-kinds-of-place.md).
- Pumping and wellbeing stay per-parent — [BDR-1](0001-inclusive-parent-model.md),
  [BDR-7](0007-pumping-belongs-to-the-parent.md).
