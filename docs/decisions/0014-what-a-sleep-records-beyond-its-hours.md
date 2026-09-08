# BDR-14. A sleep can record where it happened and how the baby was lying — and Sprout does not grade the answer

Date: 2026-09-08
Type: Product / Domain

## Status

Accepted

## Context

A sleep entry has so far been a start, an end and a note. That answers *how
much*, which is what the statistics were built on
([BDR-8](0008-statistics-and-the-who-growth-curves.md)), but not the question
parents actually ask each other at the end of a hard fortnight: *what were we
doing on the nights that went well?*

Two facts about a sleep are worth recording because they change from one sleep
to the next and a parent can act on them:

- **Where the baby slept** — their own bed, the bedside cot, the parents' bed,
  on a parent, at the breast, or somewhere the app has never heard of.
- **How they were lying** — on their back, on their side, on their tummy.

Both bring their own problems.

**A closed list of places is always wrong somewhere.** Babies sleep in prams, in
car seats, in carriers, at their grandparents'. A list long enough to cover
that would be unusable, and a list short enough to use would file half of a
parent's naps under "other".

**The position is not a neutral field.** Health services worldwide recommend
putting a baby down on their back, and an app that shows "42% tummy" is one
short step from an app that tells a parent off for it. Sprout has no idea
whether the tummy naps were supervised contact naps on a parent's chest, or
whether a paediatrician asked for them; it only knows what was typed.

**Nothing may become mandatory.** Logging a nap is done one-handed, often at
3 a.m., and the two-tap entry that exists today has to stay a two-tap entry.

## Decision

**Both fields are optional, and "not recorded" is a first-class answer.**

- The chips are unselected by default and tapping the chosen one again clears
  it. A sleep logged without touching them is exactly the sleep that was logged
  before this existed, and no screen asks for the fields again afterwards.
- The columns are nullable and backfill to `NULL`: no default is invented for
  the sleeps already in the database, because nobody can say afterwards how a
  nap logged last month was taken.

**"Somewhere else" is named by the parent, and grouped by that name.** The sixth
place carries free text, and the statistics group those sleeps by what was
typed — case and surrounding spaces ignored — so a parent who logs "pram" a
dozen times sees *pram*, not a dozen sleeps in a bucket called "other". Left
empty it stays plain "somewhere else", which is a true statement about a sleep
nobody wanted to describe further.

**The statistics show shares of the sleep already on the card, unrecorded
sleeps included.** Each breakdown line carries the sleeps counted and the time
they came to, and the sleeps that said nothing are the last line rather than
being dropped — otherwise two logged naps out of ten would be reported as
"80% in their own bed". A line's bar is its share of the window's sleep time,
and the same day rules apply as everywhere else
([BDR-8](0008-statistics-and-the-who-growth-curves.md)): a night begun before
the window brings its hours in without adding to the times-settled tally.

**Sprout records the position and says nothing about it.** No warning, no
colour-coding, no ordering that puts one position above another (longest first
is a fact about the period, not a ranking), no "did you know" note under the
chart, in the app or in the report. The app is a record of what happened, not a monitor
of what should have; a parent living with a reflux baby, a paediatrician's
advice, or an unplanned nap on a chest does not need their own log arguing with
them. Safe-sleep guidance comes from a midwife or a health service, which is
where a parent can ask the follow-up question an app cannot answer.

**No palette.** The breakdown draws one bar colour, sized by share, rather than
a hue per place — a categorical palette would need a colour-vision check of its
own ([BDR-8](0008-statistics-and-the-who-growth-curves.md)) and would suggest
the places differ in kind when the only thing being compared is how much of the
window each one holds.

## Consequences

- The two fields are set when a sleep is logged and not afterwards: there is no
  edit screen for a sleep today, only *woke up* and *delete*. A parent who
  wants to add the place to last night's entry cannot, and that is a gap worth
  filling when sleeps become editable — not a reason to build an editor here.
- The named places are text, so two spellings ("pram", "the pram") are two
  lines. Grouping is deliberately dumb — case and spaces only — because
  anything cleverer would merge places a parent meant to keep apart.
- The names of the six offered places are translated, so the breakdown reads in
  the app's language while a named place stays in whatever language it was
  typed. That is the right way round: the parent's own words are theirs.
- The fields travel with everything else between the phones of a household
  ([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)).
  A replica written before they existed merges as sleeps that record nothing,
  and a phone still on the older schema refuses a newer replica outright, as it
  already did.
**The record a parent hands to a doctor carries both**
([BDR-12](0012-a-record-for-the-doctor.md)).

- The **workbook**'s sleep sheet gains `position`, `place` and `place_name` —
  keys (`back`, `bedside_cot`) beside the parent's own words for a place they
  named, the same shape `stool_colour` already set. A sheet of raw rows that
  quietly drops a recorded field is a worse record than one that has never
  heard of it.
- The **PDF** prints them under the sleep chart, as two lines of the same shape
  as the stool-colour line: each place and each position with the time it came
  to and the number of sleeps, longest first, and the sleeps that recorded
  nothing kept whatever else is trimmed. Where a baby sleeps and how they are
  put down is something a parent gets asked at an appointment, and answering it
  from the log beats answering it from memory.
- Printing them is not the app taking a view, and the line between the two is
  worth keeping in sight: the document repeats what was logged, in the order of
  how much of the period each thing accounts for, and attaches no threshold, no
  flag, no colour and no sentence to any of it — which is
  [BDR-12](0012-a-record-for-the-doctor.md)'s rule, not an exception to it. What
  the reader makes of "on their tummy: 6 h 10 (4 ×)" is between them and the
  clinician in front of them.
- Choosing not to advise is a decision, not an oversight. If Sprout ever does
  carry safe-sleep guidance, it should be a deliberate record of its own — with
  a source, a tone and a place to put it — and not a warning bolted onto a
  statistics row.
