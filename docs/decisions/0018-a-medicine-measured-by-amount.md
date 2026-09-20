# BDR-18. A medicine measured by amount, and a leaflet that gives no gap at all

Date: 2026-09-18
Type: Product / Domain

## Status

Accepted

## Context

[BDR-15](0015-medicine-given-when-needed-and-the-wait-between-doses.md) built
the as-needed medicine around one shape of instruction: *wait six hours between
doses, no more than four in a day*. That is paracetamol, and paracetamol is what
most parents open the screen for.

It is not what every leaflet says. A teething gel reads "up to six times a day,
no more than 1.5 cm in twenty-four hours" and gives no gap at all. A syrup is
2.5 ml a dose and 10 ml a day. Sprout could hold neither:

- **The minimum wait could not be nothing.** The editor forced at least an hour,
  so a parent with no gap rule had to invent one — and then read a red traffic
  light, and a countdown, against a wait nobody had asked them to keep.
- **There was no quantity anywhere.** A dose was a dose. Three generous
  applications of the gel and three careful ones counted the same, so the number
  the leaflet actually limits was the one number the app could not count.

A parent can work around the first by typing a one-hour gap and ignoring it, and
the second by writing "half a cm" in the notes. Both are the parent doing the
arithmetic the feature exists to do for them, at 3 a.m., on the medicine where
overdoing it matters most.

## Decision

**A medicine may carry an amount, and a day may be limited by quantity as well
as by count.** Three optional figures on the medicine — how much one application
uses, the unit it is measured in, and how much is allowed in twenty-four hours —
and one on each dose: how much that dose actually used. A dose is stamped with
its medicine's amount when it is logged, and can be corrected afterwards,
because "I only used half of it" is a real correction and the day's total has to
follow it.

**The unit is free text the parent types.** "cm", "ml", "puffs", "sachets".
Sprout ships no list of units any more than it ships a list of medicines, and it
never converts between them: the figure and its unit are restated exactly as
they were given.

**No gap is a gap of none, and it is expressed as a blank field.** A minimum
interval of zero means the leaflet gave no wait, and such a medicine is held by
its daily ceilings alone. The editor's field opens empty for it and saves empty,
and the screen says "No set gap" rather than "Every 0 h" — which would read as
an instruction nobody gave.

**The quantity ceiling works exactly like the dose count.** Rolling
twenty-four hours, not a calendar day; the medicine turns red when the total
inside the window has reached the limit, and green again the moment the oldest
dose ages out of it far enough to leave room. It is counted with a tolerance,
because a parent's 0.3 three times is not the same number in binary as their
0.9, and the arithmetic should agree with their sum rather than with the last
bit of a float.

**A dose that recorded no quantity adds nothing to the total, and is not a dose
of nothing.** It still counts against the day's tally, because it happened.
Guessing a size for it would be the app inventing a figure the parent never
gave — and a medicine whose doses were never measured simply has no quantity to
report.

**Nothing here argues, and nothing here is new knowledge.** The amounts are the
parent's own, typed from a prescriber or a leaflet, and the traffic light
restates them. There is still no warning, no confirmation, no disabled button,
and a dose given while red is logged without comment (BDR-15). The state has its
own sentence and its own icon; "the day's amount is used up" is worded apart
from "that would be the seventh today", because those are two different things
to have run out of.

**A list of medicines with their usual amounts remains the one addition this
feature must not grow.** A unit field is not drug knowledge. A table saying
teething gel is 0.25 cm would be.

## Consequences

- **Two nullable columns on `medicine`, one text column beside them, and one on
  `medicine_dose`** — Room migration 17 → 18 with an iOS migration to match, and
  four new keys on the wire. All are additive: a replica written before them
  reads back as a medicine that was never measured in anything, and the older
  app reading a newer replica ignores what it does not know. No version bump
  ([ADR-0016](../adr/0016-a-transport-both-platforms-can-speak.md)), and the
  vectors land in the same commit.
- **`minIntervalMinutes` gains a meaningful zero.** It was never nullable and
  still isn't; what changed is that zero is now a value a parent can save rather
  than one the editor prevented. A reader that treats zero as "no wait" was
  already right, which is why the wire needed no key for it.
- **The as-needed screen gains three fields and the dose sheet one**, and the
  amount field on a dose only appears for a medicine that has a unit: asking how
  much of a paracetamol tablet was given is a question with no answer.
- **The dashboard card gains nothing.** It is still one line per medicine
  ([BDR-16](0016-a-running-wait-on-the-dashboard-and-a-dose-from-the-notification.md)),
  and the quantity lives on the screen the card exists to keep people off. The
  card's sentence changes only in that a wait held by the quantity says so.
- **The doctor's record gains a medicines section**: the medicines given inside
  the range with the parent's own limits beside them, and every dose with its
  time and amount. It interprets nothing
  ([BDR-12](0012-a-record-for-the-doctor.md)) — a day that reached the maximum
  is printed as the number it reached, and what that means belongs to the
  clinician reading it. A medicine set up but never given in the period is left
  out, because the document reports what happened and a shelf is not an event.
- **The workbook's `Medicines` sheet carries the ceilings on every row.** They
  are duplicated per dose on purpose: a pivot table grouping by medicine and day
  can then compare a total against the figure the parent was given without the
  sheet having decided anything about it. Its column names are English, like
  every other sheet's.
- **Statistics are untouched.** A medicine given when it was needed is not a
  daily figure, and a chart of doses per day would be the first thing here that
  looked like a finding.
