# BDR-21. A baby's allergies, as the household comes to know them

Date: 2026-10-17
Type: Product / Domain

## Status

Accepted

## Context

[BDR-20](0020-a-solid-meal-says-what-was-in-it.md) gave a solid meal its list of
foods and refused an allergy *field* on it: a box ticked on a meal would be a
clinical sign the app then had to act on — warn, colour, remember — when the
only right response to a rash after the first egg is "ask someone qualified".

That refusal was about the meal. It left a real need unanswered, and the parent
who asked for the First foods page asked for this next: once an allergy is
*known* — a doctor said so, or the family saw enough to stop offering the
food — it is a fact about the baby that the household has to carry around. It
has to be in front of whoever is holding the spoon, it has to reach the other
parent's phone, and it has to be handed on: to the doctor, and just as often to
a nursery, a childminder or a grandparent, who need that list and nothing else.
Today that list lives on a sticky note.

Three things make it easy to get wrong.

**The app could start deciding.** A food refused three times, a note that says
"red cheeks", a food on an "allergens" shelf: each is one step from Sprout
suggesting, flagging or inferring an allergy. Any of them is a diagnosis made by
software that has never seen the child — and the reverse is worse, because a
list that looks complete invites reading the absence of a food as safe.

**A warning is the wrong shape.** An allergy marked in red, or a dialog that
asks "are you sure?" when the food is added to a meal, reads as protection. It
is not: the parent may be logging the very meal that showed it, or a supervised
reintroduction the doctor asked for. A log that argues is a log that stops being
filled in, and the meal that was not logged is the one the doctor needed.

**Two phones, one list.** Allergies are noted rarely and matter a great deal. If
they lived on the baby's row, two parents noting one each on the same day would
be two edits of one row, and the merge keeps the later
([ADR-0007](../adr/0007-partner-sync-by-direct-device-to-device-exchange.md)) —
one allergy silently lost.

## Decision

**A baby has a list of allergies, kept by the household as they come to know
them.** Each is what the baby is allergic to and, optionally, the day it was
noticed, whether a health professional confirmed it, and notes in the parent's
words. Nothing else: no severity scale, no reaction type, no symptom checklist.

**What it is to is a food, chosen the First foods way.** The same search as the
meal form: a food from Sprout's list carries its id (`ref`), so an allergy to
the carrot noted in French is the carrot logged in English (BDR-20); anything
else is the parent's own words, which can be anything — cow's milk protein, egg,
peanut, a medicine — because Sprout's list is plain names and carries no
allergens, and this does not change that.

**"Confirmed by a health professional" is the parent's statement, said back.**
Off is "noted by the family", not "doubtful". Every place that shows an allergy
says which it is, in words, because a nursery reading the list and a doctor
reading it want to know, and only the parent can say.

**Sprout never proposes, infers or grades one.** No allergy is suggested from a
refusal, a note or a list; nothing ships that marks a food as an allergen; and
the app says, where an allergy is noted, that it does not diagnose and that a
doctor is who to ask.

**It is shown where the food is, and never stops anything.** The First foods page
opens on the baby's allergies. A food the baby is noted as allergic to carries
"Allergy noted" — an icon and the words, in the ordinary ink, never a colour
alone and never a warning colour — on its line on the page and on the meal form
when it is added to a meal. There is no alert, no confirmation and no disabled
button: the meal is logged like any other.

**Each allergy is its own synced row.** A table of its own, so two phones noting
one each keep both; edited and deleted like any entry, erased with its baby, and
following a food when the parent corrects it (BDR-20) — an allergy to "carottes"
is an allergy to the carrot once "carottes" turns out to be the carrot. It
ignores the pairing cut-off ("share from the pairing forward"), as the baby
itself does: it is about who the baby is, not an entry logged before, and the
parent who joins later is the one who most needs it.

**It leaves the phone two ways.** In the doctor's record
([BDR-12](0012-a-record-for-the-doctor.md)), as a section straight after whose
record it is and as an `Allergies` sheet, behind a switch that is on by default,
and not limited to the period — an allergy noted in March is still one in June.
And on its own, as a one-page PDF shared from the First foods page: whose list
it is, each allergy with its status, date and notes, and a line saying it is the
family's record and not a medical document. The notes go on that page — the form
says so where they are typed — because what was seen is what a nursery needs;
in the doctor's record they follow the notes switch, as every other note does.

## Consequences

- **One new table and a format addition.** `allergy` (Room 19 → 20 with
  `20.json`, iOS `v20-allergies`), with the sync columns from the start, as the
  medicine tables had. On the wire an `allergies` array of baby-scoped rows; a
  replica without it is a household that noted none. Its vectors land with it
  ([spec](../../spec/README.md)), and `allergy` joins the tombstone names.
- **The list is the family's, and the documents say so.** Both the report
  section and the shared page carry a sentence that the list is as noted by the
  family, that "confirmed" is their record of a professional's confirmation,
  and that Sprout does not diagnose. That sentence is the price of printing a
  medical-sounding word at all.
- **The shared page is not password-protected.** The report can be
  ([BDR-13](0013-protecting-the-file-that-leaves.md)); this page is made to be
  read by several people and pinned to a wall, and a password would defeat it.
  It goes out through the share sheet, staged and replaced like the report, and
  never kept.
- **An absent food is not a safe one.** Nothing in the app says or implies "no
  known allergy to X"; the list says what was noted. An empty list reads "None
  noted", which is all that can honestly be said.
- **The marker is information, not a guard.** A parent can log a meal with the
  allergen in it without being asked twice. If households tell us they want
  more friction there, that is a decision of its own, with this record's
  reasoning to answer.

## Alternatives considered

- **A flag on the meal** ("reaction" ticked on a food) — BDR-20's refused field.
  It makes each meal a clinical observation and leaves the family's actual
  knowledge — "she is allergic to egg" — spread across meals.
- **A list on the baby's row.** Simpler to store, and one row: two phones noting
  one each would lose one to the merge.
- **A shelf of common allergens on the food list**, or allergens flagged on it.
  That is the advice BDR-20 refused, now pointing the other way: a list that
  marks peanut invites reading an unmarked food as safe.
- **Suggesting an allergy after repeated refusals or a worrying note.** Refusal
  is not allergy, and inference from a free-text note is diagnosis by keyword.
- **Warning, or asking for confirmation, when an allergen is added to a meal.**
  See above: it argues with the person who knows, and costs the very log the
  doctor will want.
- **A severity scale or a reaction-type list.** Each would be a clinical
  classification the parent is asked to make; the notes hold what they saw, in
  their words.
- **Only in the doctor's record, not on its own.** The list is most often wanted
  by someone who should not receive the rest of the record.
