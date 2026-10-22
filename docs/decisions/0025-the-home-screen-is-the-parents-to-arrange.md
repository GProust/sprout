# BDR-25. The home screen's logs are the parent's to arrange

Date: 2026-10-22
Type: Product / UX

## Status

Accepted

Amends [BDR-10](0010-the-bottom-bar-is-four-kinds-of-place.md), whose grid
holds the logs "as equals" in one fixed order, and the clause of
[BDR-20](0020-a-solid-meal-says-what-was-in-it.md) that says the First foods
page "is always in the grid".

## Context

The dashboard's log grid is nine tiles, three to a row, in one order on both
apps: Feed, Pumping, Sleep, Nappy, Growth, Treatments, As needed, First foods,
and Wellbeing for a parent who tracks it
([BDR-6](0006-check-in-waits-on-the-dashboard.md)). It appears on Home with one
baby, and on each baby's own tab with two or more
([BDR-9](0009-the-dashboard-is-the-household.md)).

Every family sees all nine. A family that bottle-feeds and never pumps, or that
has no treatment running, still has those tiles between them and the ones they
use; and the log they open most may be on the third row.

The order itself already says why it matters. When First foods arrived it went
at the end of the grid, "so that no tile a parent already reaches for by
position has moved". Position is habit, and habits differ from one household to
the next — which is the argument for letting the parent choose the positions,
not for the app choosing them better.

Two records put tiles in front of everyone on purpose, and both have to be read
carefully here:

- [BDR-7](0007-pumping-belongs-to-the-parent.md) refused to show Pumping only to
  a parent who answered "breastfeeding", because hiding it from the parent who
  exclusively pumps is the worse failure.
- [BDR-20](0020-a-solid-meal-says-what-was-in-it.md) refused to hide First foods
  until a certain age, because that would be the app deciding when
  diversification starts.

Both refused **the app** deciding which logs a parent sees. A parent putting a
tile away is a different act, but nothing in either record says so, and a
reader of BDR-20's "always in the grid" would take it as a rule.

Two practical things shape the answer. Neither app has any reordering UI or
dependency, and Compose has no reorderable grid. And dragging, the obvious way
to reorder, is the one gesture TalkBack and VoiceOver cannot make.

## Decision

**The grid's order, and which logs it shows, are the parent's to arrange —
never the app's to decide.**

- **Customise home**, reached from Settings after the daily check-in and before
  Sharing, lists every log with its usual label and icon. Each row has:
  - a switch, *shown on home*;
  - *Move up* and *Move down*.

  *Restore the default order* sits at the bottom. On iOS the rows can also be
  dragged.
- **The default is today's grid, exactly.** With nothing stored, every tile is
  shown, in the order above, and there is no *More* tile. A parent who never
  opens the screen sees no change.
- **Hiding is presentation only.** A hidden log waits behind a **More** tile at
  the end of the grid, which lists the hidden logs and opens each in one tap.
  - Nothing about its data changes: it syncs, the other phone can still write
    to it, and it still counts in Trends and in the doctor's record.
  - *More* appears only when something is hidden.
- **The app never hides anything and never suggests it.** Nothing is hidden by
  default. There is no "you never use Pumping, hide it?", and no ordering by
  how often a log is used. *Restore the default order* restores the order and
  leaves what the parent hid alone: each of those is one switch away.
- **Wellbeing keeps its master switch** (BDR-6). Turned off, its tile is in
  neither the grid, nor *More*, nor the editor. Turned back on, it returns where
  the parent had put it, shown or hidden as it was.
- **One arrangement per phone, for every baby.** The order is about the
  parent's habits, not about one child, and with twins both panes want the same
  order. Home with one baby and each baby's tab draw the same grid.
- **Stored on the phone, backed up, never synced.** An ordered list of tile ids
  and a set of hidden ids, in the ordinary settings — Android's `settings.xml`,
  iOS's `UserDefaultsStore` — like the reminder switches.
  - A restored phone keeps it ([ADR-0011](../adr/0011-what-survives-a-new-phone.md)).
  - The other parent arranges their own phone.
- **Tile ids are stable, and one set for both apps:** `feeding`, `pumping`,
  `sleep`, `diaper`, `growth`, `treatments`, `medicines`, `foods`, `wellbeing`,
  the names iOS's tiles already carried.
  - A log added in a later release, so missing from a stored order, appears
    **shown**, at its place in the default order.
  - An id the app no longer knows is ignored.
  - The arithmetic is one pure function on each side (`arrangeTiles` and the
    moves beside it), with the same cases tested on both.
- **It works without dragging.** Each row carries *Move up* and *Move down* as
  accessibility actions as well as buttons, and the new position is announced
  ("Pumping, position 3 of 8"). The switch reads as "Pumping, shown on home".

## Consequences

- Someone who never opens *Customise home* sees the dashboard they had, pixel
  for pixel. The screenshot runs, which start from empty settings, depend on
  it.
- BDR-20's "always in the grid" now means **there unless the parent puts it
  away**. The tile is still in the grid for every baby from the day they are
  born, never hidden by age, and a parent who has put it away finds it under
  *More*. The same holds for Pumping (BDR-7): no answer in the profile hides it.
- BDR-10's "as equals" stands as the default. The parent may rank them.
- Adding a log now means appending its id on both apps, at the end, and never
  renaming or reusing one: a stored arrangement names tiles by these ids.
- *Customise home* covers the grid only. The rest of the dashboard stays where
  its records put it:
  - what is running, on top (BDR-9);
  - the medicine card below the feed buttons, absent rather than empty
    ([BDR-16](0016-a-running-wait-on-the-dashboard-and-a-dose-from-the-notification.md));
  - the "what's new" card ([BDR-22](0022-whats-new-is-a-card-the-parent-puts-away.md));
  - the check-in card, last (BDR-6);
  - the *Since* chips and the *Today* row.

## Alternatives considered

- **Dragging the tiles inside the grid itself.** It is what a home screen
  usually does. It needs a reorderable grid neither platform's toolkit has, and
  a drag is the one gesture a screen reader cannot make. A list with switches
  and arrows is plainer, and works for everyone.
- **One arrangement per baby.** Twins are fed by the same hands in the same
  order; two arrangements would double the screen to set up two grids that
  disagree. It can be revisited if a family asks.
- **Ordering by use.** That is the app deciding, and tiles that move under the
  thumb are exactly what the fixed order protected.
- **Hiding a log entirely**, with no *More*. A log the other phone still writes
  to should not become unreachable on this one; its screen stays one tap away.
- **Syncing the arrangement.** One parent's habits are not the other's.
- **Hiding by profile answer**, such as no Pumping unless breastfeeding. BDR-7
  already refused it, for the parent who exclusively pumps.
- **Going beyond the grid**, such as hiding the *Today* row or the *Since*
  chips. Each is its own question about what the dashboard is for, and none was
  asked for.
