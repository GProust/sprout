# BDR-19. Two breastfeeds saved apart can be joined into the one feed they were

Date: 2026-09-24
Type: Product / UX

## Status

Accepted

## Context

[BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md) gave the live timer a
break: a feed can stop for a burp or a nappy and carry on, and the gap is kept
as a break rather than counted as feeding. That fixes the feeds timed from now
on. It does nothing for the ones already saved the old way, and it cannot help
with the ones saved the old way out of habit or haste: *Stop & save* at the end
of the first side, the baby winded, *Start right* five minutes later.

Those are two feeds in the log, and everything that counts feeds counts them as
two. The day's tally is one too many, the average gap between feeds is halved
for that pair, the "it has been a while since a feed" reminder is measured from
the second one, and the doctor's record lists a feed that did not happen. Nothing
was lost: both halves are in the log, stretch by stretch, with their clock
times. The record just draws the line in the wrong place. And a parent who
notices has no way to redraw it except to delete one feed and type the other
back in by hand.

## Decision

**Two breastfeeds that follow each other can be joined, and the join is exactly
a pause that happened after the fact.** The earlier feed keeps its start; it gains
the later feed's stretches, and it ends when the last of them ended. The minutes
between the two become a break, in the same place and with the same clock range
that pressing *Pause* at the time would have given it. It is not feeding time,
it shows as *Paused 5m* beside the totals, and the per-side times are the sum of
both feeds' stretches. Notes from both are kept. The later feed is deleted.

A break was already the gap between two stretches, so there is **no new field,
no new key on the wire and no migration**: the joined feed is an ordinary feed
with a gap in it, and every screen that reads a break already reads this one.

**It is offered, quietly, only where it plausibly applies.** A small *Join with
previous* sits beside *Details* on a breastfeed card, in a muted colour quieter
than *Details* itself, when the feed directly below it:

- is also a breastfeed, for the same baby;
- was timed stretch by stretch (a feed logged before per-side timing existed
  has no stretches to line up, and a feed with a start and nothing else has no
  end to measure from);
- ended at most **30 minutes** before this one began, and not after it began;
- and nothing else — no bottle, no solids — was logged between the two.

The thirty minutes limits where the offer appears. It is not a rule about how
long a break may be: a break still has no maximum
([BDR-17](0017-a-breastfeed-can-stop-and-carry-on.md)), and a feed paused for
an hour is still one feed. Without some limit, *Join* would be on nearly every
breastfeed card, which is the opposite of quiet; beyond half an hour, two feeds
side by side are far more likely to be two feeds — cluster feeding is often an
hour apart from start to start — than a burp that was saved in the middle.

**It asks first.** Tapping *Join with previous* opens a confirmation that names
both feeds by their start times and the gap between them, shows the joined
feed's timeline — each stretch and the break, with their clock times — and says
the join cannot be undone. *Cancel* leaves both feeds exactly as they were.

**Nothing suggests it.** No banner, no "these look like one feed", no badge on
the dashboard, and never an automatic join. Whether two feeds were one is
something only the parent who was there knows, and a feeding log that rewrote
itself would stop being a record of what happened.

## Consequences

- **Joining is an edit and a deletion**, so it travels to the rest of a
  household like any other edit and deletion
  ([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)).
  A phone that has not synced yet shows the two feeds until it has. The two
  writes happen in one transaction, so no phone can be left holding a joined
  feed while the later one still exists.
- **The join is worked out from what the database holds when it is confirmed**,
  not from what was on screen when the dialog opened. If either feed was edited
  or deleted in the meantime, by an exchange with another phone for example, and
  the two no longer qualify, nothing is written.
- **Everything counting feeds counts one**: the tally, the average gap, the
  reminder, the widget's last feed, the statistics and the doctor's record. The
  time at the breast does not move, because it was always the sum of the
  stretches.
- **Joined feeds can be joined again.** Three stretches saved as three feeds join
  two at a time into one.
- **There is no split.** A joined feed can be edited like any other, and the edit
  keeps its break (BDR-17), but turning one feed back into two is not offered.
  Nobody has asked for it, and it would need a way to choose where to cut. If
  parents ask, that is a separate decision.
- **The logic is the same on both platforms and tested with the same cases**
  (`BreastfeedJoin.kt` / `BreastfeedJoin.swift`), because a household with one
  phone of each has to offer the same join on the same two feeds.
