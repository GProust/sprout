# BDR-17. A breastfeed can stop and carry on, and the break is not time at the breast

Date: 2026-09-17
Type: Product / Domain

## Status

Accepted

## Context

The live timer has had two states since it was built: nursing on a side, or
finished. A real feed has a third. Between the two sides a baby is sat up and
winded, which takes as long as it takes. Halfway through there is a nappy, a
doorbell, a toddler who needs something now.

Parents had two ways round it and both spoil the record:

- **Stop and save, then start again.** One feed becomes two, an hour apart
  becomes half an hour apart, and everything counting feeds — the day's tally,
  the average gap, the "it has been a while since a feed" reminder — is counting
  something that did not happen.
- **Leave it running.** The ten minutes of winding are logged as ten minutes at
  the breast. That is the number this feature exists to produce: how long a baby
  fed is the nearest thing anybody has to how much they drank, and it is what a
  parent is asked at the six-week check.

Neither is a small inaccuracy in a log whose whole claim is that it is a record
of what happened.

## Decision

**A session can be paused, and pausing does not end it.** The feed keeps its
start time and its history; the stretch being nursed is banked as a completed
stretch, the clock at the breast stops, and the break runs a clock of its own.
The parent comes back to the same feed, however long the burping took.

**Coming back names the breast.** The resume control is two buttons, *Resume
left* and *Resume right*, positioned to match — not a single *Resume* that
carries on where it left off. The most common break is the one between the
sides, and after five minutes of winding at 3 a.m. nobody remembers which side
they were on. Resuming on the same breast is allowed and ordinary: the nappy in
the middle of the left side is not a switch.

**A break is not a field. It is the gap between two stretches.** Sprout already
records a feed stretch by stretch, each with its own start and end; a break is
what is left when one ends and the next has not begun. So there is no new
column, no new key on the wire, no migration, and nothing to backfill — a feed
logged before this existed reads back as a feed with no breaks, which is exactly
what it was.

**Feeding time means time at the breast, everywhere.** The timer, the bar on the
feeding screen, the dashboard card, the home-screen widget, the statistics, the
PDF and the workbook all count the stretches and never the span. They mostly
already did — the statistics have summed the stretches since the day they were
recorded — and this makes it a rule rather than a coincidence. A feed saved
during a break ends when its last stretch ended, not when *Save* was tapped, so
putting the phone down and saving in the morning cannot invent eight hours of
feeding.

**The break is shown, not hidden.** It appears in the session's timeline where
it happened, with its own clock range, and on the finished feed as *Paused 5m*
beside the totals. A parent watching a break run wants to know when it started,
because that is how they decide whether the baby is done. A figure that quietly
disappeared into the arithmetic would be the app editing the story.

**Nothing about a break is required, and nothing about it is judged.** There is
no maximum, no nudge after twenty minutes, no automatic stop, no "are you still
feeding?". A break that runs all night is a parent who fell asleep on the sofa
with the baby, and being told off by a phone in the morning helps nobody; the
feed they saved is still the stretches they actually fed, which is the honest
answer. Pausing twice does not restart the break either — a second tap must
never quietly reset the minutes already spent.

## Consequences

- **A feed's wall clock and its feeding time can now genuinely differ**, and
  where a single number is shown it is the feeding time. The doctor's record
  ([BDR-12](0012-a-record-for-the-doctor.md)) reports the figure it always
  reported, and reports it under the same name; it gains no column and no
  sentence, because a break is not a finding.
- **The running session stays device-local**, as it always has — nothing about
  a feed reaches the other phones of a household until it is saved
  ([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)).
  A parent pausing is not a thing the other parent is notified of.
- **The widget counts nursing time now**, not the time since the feed began, so
  it and the app agree during and after a break. A paused session shows a
  stopped clock; the platform cannot be asked to pause a launcher's chronometer,
  so it is told where to stand and left there.
- **A feed edited by hand keeps its breaks but cannot gain one.** The form is a
  list of stretches and their lengths, and the breaks between them are carried
  through untouched rather than collapsed. Typing a break in would mean a field
  for something that is meant to be a consequence of what happened; if parents
  ask for it, that is a decision to make then.
- **Automatic anything is left for later.** Ending a session that has been
  paused for hours, or asking about it, is a reasonable thing to want and a
  decision of its own — with a threshold, a tone and a way to be wrong. It is
  not a behaviour to bolt onto a Pause button.
