# BDR-15. Medicine given when it is needed, and the wait between doses

Date: 2026-09-15
Type: Product / Domain

## Status

Accepted

## Context

Sprout already has *treatments*
([the `treatment` table since schema 6](../adr/0002-local-first-on-device-storage.md)):
a course on a calendar — vitamin D every morning, an antibiotic twice a day for
ten days. It answers *what is scheduled*, and the doctor's record says so in as
many words: "Sprout does not record whether an individual dose was given, so
this is the plan rather than an administration record"
([BDR-12](0012-a-record-for-the-doctor.md)).

That is the wrong shape for the other kind of medicine entirely. Paracetamol for
a feverish baby is not on a calendar. It is given when it is needed, and the only
thing that matters is the one thing the plan model has no room for: **when the
last dose was, and how long the wait is before another one is allowed.**

The question a parent actually asks at 3 a.m. is not "what is the schedule". It
is *can I give her another one yet?* — asked while holding a hot, crying baby,
usually by the parent who did not give the last dose, often in the dark, and
frequently about a bottle whose leaflet is downstairs. Getting it wrong in one
direction means a child in avoidable pain for two more hours; in the other, a
double dose.

Three things make this hard to model honestly.

**The wait is not one number.** A leaflet says "every 6 to 8 hours". Six is the
floor — before it, don't. Eight is the interval the prescriber actually wants
kept. Between them is a real, ordinary answer: *allowed, sooner than ideal*.
Collapsing that to a single boundary throws away the distinction the parent is
trying to make.

**The numbers belong to the child, not to the drug.** The gap, the dose, the
daily maximum: all of them depend on the child's weight, age and what else they
are taking, and all of them come from a prescriber or a leaflet. A gap that is
right for a 9 kg one-year-old is wrong for a newborn.

**Anything Sprout says here is read as medical advice.** A colour that means
"fine" is an assessment, on a screen used by someone frightened and tired. The
app has no idea what the medicine is, what the child weighs, or what else they
have had.

## Decision

**Sprout records doses and counts the hours. It supplies no medical knowledge of
any kind.**

**Every medicine is set up by the parent, one at a time, and Sprout ships no
list of any.** There is no drug database, no autocomplete of brand names, no
default dose, and no suggested interval for any named substance. The parent
types the name, and types the gap they were told to keep. The new medicine form
opens with *Paracetamol* and *6 / 8 hours* filled in as an editable starting
point, because it is the example nearly everyone comes to this screen for — and
it sits under a line saying where the right numbers come from, which is the
prescriber or the leaflet and never this app. Sprout is not asserting that six
hours is correct for your child; it is saving you a keystroke on a field you are
expected to check.

**The traffic light restates the parent's own numbers back to them, and nothing
else.**

| | |
|---|---|
| **Red** | Less than the minimum wait has passed. Sprout says how long is left. |
| **Amber** | Past the minimum, not yet past the comfortable interval. Allowed, sooner than ideal. |
| **Green** | Past the comfortable interval — or past the minimum, when no comfortable one was set. |

The **comfortable interval is optional**: a medicine with only a minimum has no
amber band, and goes red to green at the one boundary the parent gave. A medicine
that has never been given is green, not red, because no wait is running.

**A daily maximum, when the parent sets one, also holds the light at red.** Four
doses in twenty-four hours is a limit an interval alone cannot express — the
sixth hour can come round while the day's allowance is spent. When it is, Sprout
says so in those words and says when the count drops again, which is twenty-four
hours after the dose that has to age out.

**The colour is never the only channel.** Each state carries its own icon and its
own sentence, and the sentence is what the screen leads with. Red/amber/green is
the palette a deuteranope reads worst, and this is not a chart that can be
studied at leisure — so it is written down as well as coloured, every time.
Nothing here is conveyed by hue alone.

**No sentence in this feature is an assessment.** Amber is "sooner than ideal",
which is arithmetic against the parent's own second number, not a judgement
about the child. There is no warning, no advice, no "are you sure", no note
about what the medicine is for, and no threshold Sprout chose. If the parent
gives a dose while the light is red, Sprout records it and moves on: it is a log,
and a log that argues is a log that gets falsified.

**A reminder is one switch per medicine, off until asked for, and it fires when
the wait is over.** The parent chooses which wait — the minimum, or the
comfortable interval — and the default is the minimum, because the moment a
medicine *may* be given is the actionable one and is what a parent watching the
clock is waiting for. Like every other notification in Sprout it is opt-in, and
it is scheduled by the platform rules already written down
([ADR-0019](../adr/0019-a-reminder-decided-when-it-is-scheduled.md)).

**Doses are logged per baby, and they sync.** They are ordinary syncable rows,
which is the point: the parent who is awake needs to know what the parent who is
asleep already gave. A dose carries the time it was given and an optional note,
and can be corrected or deleted like any other entry — a mis-tap at 3 a.m. must
be fixable, and the traffic light has to follow the correction.

**A dose points at its medicine by `uid`, not by a local id.** Every other
child row in Sprout points at its baby that way for the same reason: `id` is a
per-device counter and means something else on the other phone. Here the uid is
the stored column rather than something resolved at merge time, so a dose cannot
be attached to the wrong medicine by a merge that ran in an unexpected order.

## Consequences

- **This is a second, separate kind of medicine, and the app says so.**
  Scheduled courses stay where they are, unchanged. The new screen is reached on
  its own tile and holds only as-needed medicines, because the two are asked
  about at different moments and a screen that mixes them serves neither.
- **Sprout now holds an administration record for these medicines**, which is
  exactly what the treatments note in the doctor's export says it does not have.
  That export is unchanged by this decision and still covers scheduled courses
  only; extending it is a separate change with its own thinking to do about what
  a printed page of doses implies.
- **A wrong number entered is a wrong light shown.** Sprout cannot validate the
  interval against anything, because it does not know the drug, the child's
  weight, or the prescription. The form says where the numbers come from; it
  cannot do more than that, and pretending otherwise would be worse.
- **The light can be stale by a merge.** The doses are the other parent's too,
  and they arrive when the phones next exchange (ADR-0010). A dose given in the
  next room seconds ago is not on this phone yet — which is the same property
  every other log in Sprout has, and why the screen shows *when* the last dose
  was rather than only the colour.
- **Two more tables to migrate and to keep in step across the two apps.** They
  are ordinary syncable rows and cost the usual: a hand-written migration on each
  side, a schema bump, and their vectors in the same commit
  ([spec](../../spec/README.md)).

## Alternatives considered

- **Extending `treatment` with an "as needed" mode** instead of new tables. It
  would have put two schedules that share no fields in one row — a course has a
  start date, an end date, an every-N-days grid and times of day, none of which
  an as-needed medicine has; and an as-needed medicine has a minimum gap, a
  comfortable gap and a daily cap, none of which a course has. Every screen and
  every renderer would then branch on the mode, which is two models wearing one
  table.
- **Shipping a small list of common infant medicines** with their usual
  intervals, to save typing. This is the version that gets someone hurt. The
  usual interval is not the interval for *this* child, the list would be
  wrong in some countries on the day it shipped, and a prefilled number carries
  authority the app has not earned. The one example in the form is deliberately
  the field the parent is told to check, not a recommendation about a drug.
- **Warning before a dose given while red.** Tempting, and wrong twice over: it
  makes the app an authority on a decision it cannot see the inputs to (a
  prescriber may well have said otherwise), and a parent who has already decided
  will dismiss the dialog or, worse, not log the dose. An unlogged dose is the
  one outcome this feature exists to prevent.
- **A countdown notification per medicine, repeating.** Nagging about a dose
  that may no longer be wanted — the fever broke, the baby is asleep. One
  notification at the moment the wait ends, opt-in, is the whole of it.
- **Storing a "next dose due at" timestamp** rather than computing from the last
  dose. It would be a second source of truth that a correction, a deletion or a
  merge could leave pointing at the wrong moment. The doses are the record; the
  moment is derived, every time it is asked for.
