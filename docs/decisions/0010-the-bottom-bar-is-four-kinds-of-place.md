# BDR-10. The bottom bar is kinds of place, not kinds of log

Date: 2026-09-05
Type: Product / UX

## Status

Accepted

Amends [BDR-7](0007-pumping-belongs-to-the-parent.md), whose decision section
said the bottom bar "stays the five baby-centred screens".

## Context

The rule was written down and it was coherent: baby screens in the bottom bar,
the parent's own screens reached from the dashboard's log list. It matched the
line [BDR-1](0001-inclusive-parent-model.md) and
[BDR-7](0007-pumping-belongs-to-the-parent.md) already drew in the data model —
`PumpingEntity` and `WellbeingEntity` carry no `babyId`, by decision.

It could not quite be implemented. **Five** entities carry a `babyId`: feeding,
sleep, diaper, growth **and** treatments. Home takes a seat and a Material
navigation bar holds five, which leaves four. So treatments — baby-scoped by the
rule — fell into the log list next to pumping and wellbeing, which were there
for an entirely different reason, with nothing on screen to tell the two cases
apart.

The bar also did not stay put. `showBottomBar` was true only on the five tab
routes, so it vanished on Pumping, Treatments, Wellbeing, Statistics, Babies and
Settings. Moving between Feed and Pumping — a pairing a pumping parent makes
constantly — crossed a navigation boundary mid-task: a tab switch going one way,
a back press coming home.

The result read as an incomplete list of logs rather than as a category, because
that is what it looked like.

## Decision

**The bar holds kinds of place, and the logs all live in the dashboard's grid.**

- **Home** — the household ([BDR-9](0009-the-dashboard-is-the-household.md)).
- **The baby's name** — the picked child. Present only when there are two or
  more babies; with one, Home already is that view.
- **Trends** — the statistics screen, promoted out of the button it was hiding
  behind on the dashboard.
- **You** — pumping, the wellbeing history and the daily check-in, together,
  saying out loud what the model already decided: these belong to the parent.

Consequences for the logs:

- **All five baby logs sit in the dashboard's grid as equals**, treatments
  included. There is no seat to lose, so the arithmetic that stranded it is
  gone.
- **Log screens are pushed, not tabbed**, and each gains a back arrow. Every one
  of them now behaves the same way, which is the consistency the old bar was
  missing.
- `Routes.BABY` stays a bottom destination even when it is not drawn, so opening
  a baby from a card is always a sibling tab rather than sometimes a push on top
  of Home — the multiple-back-stack trap the navigation helper already warned
  about.

## Consequences

- Every tab is always present, so the bar stops appearing and disappearing.
- The parent/baby split becomes visible instead of implicit. A parent looking
  for pumping has somewhere to look.
- **The widget's deep link had to be fixed in the same change.** `MainScaffold`
  honoured the launching intent only `if (isBottomDestination(routeRequest))`,
  and the widget asks for `Routes.FEEDING` — which stopped being a tab. Left
  alone, that tap would have silently landed on Home. The guard now opens any
  route in a known-routes set: tabs as siblings, everything else pushed. The set
  also keeps an intent extra on an exported activity from reaching the nav
  controller as an arbitrary string.
- Four tabs where there were five, and one of them changes name with the family.
- The old `nav_diaper` / `nav_growth` labels live on as the grid's tile labels,
  so nothing had to be re-translated for seven locales.

## Relationship to other decisions

- Amends the bottom-bar clause of [BDR-7](0007-pumping-belongs-to-the-parent.md);
  the pumping data model in that record is untouched.
- The dashboard it sits under is [BDR-9](0009-the-dashboard-is-the-household.md).
- The parent/baby line it makes visible is
  [BDR-1](0001-inclusive-parent-model.md).
