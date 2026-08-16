# BDR-8. Statistics that answer a parent's questions, and WHO curves read against both references

Date: 2026-08-16
Type: Product / Domain rule

## Status

Accepted

## Context

Sprout has been good at recording and bad at telling you what it recorded. The
dashboard shows three numbers for today; every other question — *how much is she
actually drinking?*, *is he sleeping less than last week?*, *are we still on the
curve?* — meant scrolling a history list and doing arithmetic in your head, at
the hour of day when nobody can do arithmetic in their head.

Those questions also have an audience beyond the parent. "How many feeds a day,
how many wet nappies, what's the weight doing" is the opening of most paediatric
appointments in the first months, and a parent who can answer it from their
phone has a better appointment.

Two things stood in the way.

**Solids had no quantity.** A bottle records its millilitres; a purée recorded
only that it happened. Any "how much did they eat" summing over the day was
therefore blind to a growing share of the food.

**The WHO growth standards are published per sex,** and Sprout has never asked a
baby's sex. Nothing in the app needed it, and every field a tracker asks for is
a field it then has to be trusted with. Making it a required question, so that
one screen could draw one curve, would have been a real cost charged to every
user of the app for a feature some of them will never open.

There is a third risk that shapes the wording rather than the data model:
growth centiles are easy to over-read. A baby on the 8th centile who has always
been on the 8th centile is fine; a baby who crosses two bands in a month may not
be. A screen that says "outside the normal range" in red does harm.

## Decision

**A Statistics screen**, one tap from the dashboard's "today" numbers, over a
window of 7, 30 or 90 days.

- **Per-day totals** for feeding (breastfeeds and time at the breast, bottles
  and millilitres, solids and grams), sleep (time asleep, and how many times the
  baby settled) and nappies (changes, wet, dirty), each with a bar per day so the
  shape of the week is visible, and a **daily average** above it.
- Averages are over **completed days only**. Today is half-lived, and averaging
  a morning in with whole days makes a normal week look like a declining one.
  When today is all there is, today is used.
- **A day with nothing logged is a zero, not a gap.** Days are what the averages
  divide by, and quietly dropping the empty ones would flatter every figure.
- **The window never reaches back past the birth.** The same rule read the other
  way: days the baby did not live are not days, so a twelve-day-old asked for
  "30 days" is averaged over twelve. Without this, a newborn feeding ten times a
  day is reported as feeding four — the window is shortened and the screen says
  how many days it actually covered.
- **Feeds and changes belong to the day they started; a sleep is split across
  the days it covers.** A night from 20:00 to 06:00 is ten hours of sleep, and
  putting all ten on the evening would leave the next day looking sleepless —
  but the *count* stays on the evening, because "three naps" means three times
  settled, not three fragments of clock.

**Solids gain an optional weight in grams** (schema 14 → 15), alongside the
bottle's millilitres. Optional both in the form and in the statistics: a purée
is often given with no scale in sight, and an unrecorded amount adds to the
count of feeds while leaving the quantity alone. Feeds logged before this
existed backfill to NULL, never to 0.

**The WHO Child Growth Standards are shown against both references, and the
baby's sex is still never asked.** Weight, length and head circumference are
each drawn over the WHO band, and the reading is given as a **span** — "P22–P34"
— because the girls' and boys' curves disagree and Sprout does not know which
one applies. "Inside the band" means inside the 3rd–97th percentile of *at
least one* reference: the baby is one sex or the other, so flagging a healthy
baby on the strength of a fact we chose not to collect would be the worse error.

The curves stop at **two years**. Sprout is a newborn and postpartum tracker,
and the length standard changes measurement at 24 months (lying down becomes
standing up, with its own table) — comparing a toddler's standing height against
a lying-down reference would be quietly wrong.

**Each card draws one number at a time, and the window slides.** A count, a
duration, millilitres and grams cannot share an axis, so the feeding card has
its own selector rather than a combined chart — two scales on one picture would
invent a relationship that isn't there. And the period chips choose how *wide*
the window is, while dragging any chart sideways chooses *where* it sits: the
three charts move together, and the window stops at the birth in the same way
and for the same reason it never starts before it.

**Tapping a day opens what it was made of** — the feeds with their times and
amounts, the sleeps with how much of a night landed on that day, the changes
with the stool colour from the same card the diaper screen uses. One selected
day is shared by the three cards, because the question is "what happened on the
14th", not "what happened on the 14th, for nappies".

**Nappy bars stack into what each change actually was**: urine only, both, stool
only. Stacking "wet" and "dirty" as recorded would count every mixed change
twice and leave the bar disagreeing with the figure printed above it. The
overlap segment is a hatch of the other two rather than a third colour, because
that is what it is.

**Colour carries meaning, and is checked rather than chosen by eye.** On the
growth chart, colour is the *side of the median* — warm below, cool above, a
diverging pair — and *texture* is which reference, so that showing both does not
need four hues. That was not an aesthetic preference: every four-hue set tried
collapsed to a colour distance of 3–4 under deuteranopia, which is no
distinction at all for roughly one man in twelve. The same check picked the
nappy pair. A palette in this app is validated, not eyeballed.

**The reference is a view choice, never a stored one.** The growth card offers
"both / girls / boys"; picking one narrows the band and turns the span into a
single percentile. Nothing is written down, nothing is synced, and the app still
never asks — the wording under the chart says exactly that, so the choice cannot
be mistaken for a fact Sprout now knows.

**The wording never diagnoses.** Being outside the band reads as "worth a word at
the next appointment", not as an alarm; every growth card carries a line saying
that one measurement means little on its own, that what a doctor reads is the
shape of the line over months, and that this is not medical advice.

**Nothing new is collected or sent.** The statistics are computed on the phone
from entries the parent already made, and the WHO tables ship inside the app —
there is no lookup, and still no `INTERNET` permission.

## Consequences

- Reference data now lives in the app and has to be *right*. The LMS parameters
  are the published WHO tables, and the tests check the computed curves against
  the WHO's own printed z-score values rather than against whatever the code
  produces.
- Phones that have not been updated will refuse a replica from an updated one
  until they are updated too — the existing schema-version rule (ADR-0007), and
  the same thing that happened at 13 → 14.
- The growth screen keeps its own small weight chart. It is the log; this is the
  reading of it.
- Exporting a report for a doctor (PDF) and the raw data as a workbook, one
  sheet per kind of entry, are the natural next step and are deliberately *not*
  in this decision: they are a separate piece of work on top of the same
  per-day figures.
