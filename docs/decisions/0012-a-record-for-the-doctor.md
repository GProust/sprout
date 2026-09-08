# BDR-12. A record for the doctor: the figures, and no interpretation

Date: 2026-09-08
Type: Product / Domain rule

## Status

Accepted.

Builds on [BDR-0008](0008-statistics-and-the-who-growth-curves.md), whose
consequences named exporting "a report for a doctor (PDF) and the raw data as a
workbook" as the natural next step and deliberately left it out. This is that
step.

## Context

"How many feeds a day, how many wet nappies, what's the weight doing" is the
opening of most paediatric appointments in the first months. Sprout has been
able to answer that on screen since the Statistics screen shipped — but a phone
held out across a desk is not how that conversation goes. The parent scrolls,
the doctor squints, the numbers are read out loud and written down again by
hand, and half of what was useful (what the week looked like, not just its
average) never makes it across.

There is a second audience the screen cannot serve at all: whoever wants the
rows. A dietitian working out intake, a lactation consultant looking at feed
intervals, a paediatrician who wants to put the weights in their own system.
They do not want a picture of the data.

And there is a risk that shapes everything below. A document is quoted, filed,
forwarded and read months later by someone who never met the family. A screen
that over-reads a centile is a bad moment; a *printed page* that over-reads one
is a bad moment that gets photocopied. The same care BDR-0008 took over wording
has to be taken again, harder, because paper travels.

## Decision

**Two files, made on the phone, over a range the parent picks.** A **PDF** to
hand over or print, and an **`.xlsx` workbook** with one sheet per kind of
entry. Both are produced from one assembly of the figures, so the document and
the spreadsheet cannot disagree.

**It is reached by a share action on the baby's own page.** Not from Statistics,
and not from Settings: the baby whose record it is has then already been chosen
by the page it was started from, and there is no menu left on the wrong name to
export the wrong child's record from. One baby per file; twins mean two exports,
and two files that can go to two different people.

**The range is four presets and a custom pair of dates** — 7 days, 30 days,
90 days, since birth, or two dates picked by hand. The presets are rolling
windows ending today, the same arithmetic the Statistics chips do. Both ends are
clamped: never after today, never before the birth. The screen shows the range
that will actually be covered *before* the file is made, and the document prints
it on its front page — a range that was silently shortened is a set of averages
divided by the wrong number of days.

**Every rule the Statistics screen counts by is kept**, because the report is a
second reading of the same numbers and not a second opinion about them: empty
days are zeroes, averages cover completed days only, a sleep is split across the
days it covers while its count stays on the evening it began, and an amount
nobody recorded is absent rather than zero. Where that last one would otherwise
mislead — a millilitre average over bottles that were never measured — the
document says how many of them carried a figure.

**The document interprets nothing.** No thresholds, no flags, no colour that
means "low", no range labelled normal, and no sentence a reader could take as an
assessment. It states what was logged, and where it reads the WHO tables it says
so in the same breath. Its own footer says it is a log and not a medical record.
This is the whole point of the format: a parent handing over facts is helping,
and a parent handing over an app's opinion is not.

**Growth is drawn for all three measurements** — weight, length and head
circumference — each against the WHO band, with a centile beside every figure in
the table. A measurement nobody took is a gap in the line and a dash in the
table, never a zero and never an interpolation.

**The reader may pick a WHO reference, and Sprout still never asks.** The export
offers the same both / girls / boys choice the Statistics screen has; picking one
narrows the band and prints a single centile instead of a span. Two things follow
from it, and both are the decision rather than the implementation:

- **The default is both.** Nobody has to answer a question about their baby's sex
  to get a report out, and a report made without touching the control must not
  imply that one was answered.
- **The choice is never stored.** The other switches on the screen persist
  between exports; this one resets every time. Remembering it would be keeping a
  baby's sex on the device by the back door — the exact field BDR-0008 decided
  not to have — and the claim in that record has to stay literally true. Whichever
  reference was used is printed in the document, twice: beside the figures, and in
  the sentence under them.

**Treatments are drawn as a timeline over the report's own days**, one bar per
course. A course that began before the range carries a chevron at that end and
one still running carries the other, so a clamped bar cannot claim a start or an
end the course never had; a course given less often than daily is a faint span
with a mark on each scheduled dose, because a solid bar would say "every day".
The note under it says what Sprout does not know: it records the *schedule*, not
whether an individual dose was given. That is a plan, not an administration
record, and a doctor reading a bar as compliance would be reading something that
was never written down.

**Notes are off by default.** The free text on entries is where a parent writes
things they meant for themselves, and an export is a one-way door. It is one
switch, and when it is off the column is absent from the workbook rather than
present and empty.

**The parent's own data never appears.** Pumping belongs to the parent rather
than to a baby ([BDR-0007](0007-pumping-belongs-to-the-parent.md)) and the
wellbeing check-ins never leave the device at all — they are not even part of
household sync. A postpartum report for the parent's own appointment is a
different document for a different audience; worth doing, worth doing separately.

**The day-by-day appendix switches to weeks above 92 days.** "Since birth" on a
one-year-old is 365 rows: nine pages of table behind three pages of report. A
quarter still reads as days; a year does not.

**The workbook's column names are English and stay English**, alone among
everything the app shows. They are the schema of a data file rather than prose: a
pivot table written against `bottle_ml` keeps working when the phone's language
changes, and the file handed over next year has the same header row as the one
handed over today. The document — the thing a person reads — is translated like
the rest of the app.

**Nothing is sent.** The file is written into a cache directory and handed to
Android's share sheet; where it goes is the parent's choice, made after the file
exists. Sprout has no `INTERNET` permission and could not send it if it wanted
to. Each export replaces the last: a report is a courier, not an archive, and a
health record left lying in a cache is a health record left lying about.

## Consequences

- A file leaves the phone now, deliberately, and `PRIVACY.md` says so — including
  that the moment it reaches a messaging app or a mail client it lives under
  *that* app's policy and no longer under this one.
- The wording of the document is a maintenance burden in seven languages. It is
  worth it: a report a French family hands to a French paediatrician in English
  is a report that gets read out loud and translated on the spot.
- Anything added to the Statistics screen now has a second home to consider. That
  is the right pressure — a figure worth showing a parent is usually worth
  handing a doctor — but it is not automatic, and a new number is not in the
  report until someone decides where on the page it goes.
- The centile columns put the WHO tables in front of clinicians, who will
  recognise them. That raises the cost of an error in
  [`WhoGrowth.kt`](../../app/src/main/java/com/gproust/sprout/ui/stats/WhoGrowth.kt)
  from "a wrong screen" to "a wrong document in a medical file", and is one more
  reason those tables are reference data that is re-derived, never tidied.
