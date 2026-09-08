# 13. Writing the PDF and the workbook by hand

Date: 2026-09-08

## Status

Accepted.

Implements [BDR-0012](../decisions/0012-a-record-for-the-doctor.md), which
decided what the report says. This decides how the two files get made.

## Context

The report needs two formats a stranger's computer can open: a PDF to print or
hand over, and an `.xlsx` workbook for whoever wants the rows. Neither is a
format we would choose; both are the formats a doctor's office already has.

The obvious route is a library, and the obvious libraries do not fit.

**For the workbook, Apache POI is not shippable on Android at all** — it is a
JVM library that reaches for `javax.xml` and AWT classes Android does not have,
and the ports that work do so by vendoring several megabytes of it. The lighter
alternatives are all *readers* as well as writers, and a reader is a parser: we
would be shipping code whose job is to consume untrusted files into an app whose
whole claim is that it does not open anything it did not write.

**For the PDF, Android already has a writer.** `android.graphics.pdf.PdfDocument`
has been in the platform since API 19 and hands out an ordinary `Canvas`.
Everything we want to draw — text, rules, bars, a band, a Gantt — is drawing.

There is a second constraint behind both. This project has no local Android
toolchain: CI is the build verifier ([ADR-0006](0006-ci-as-build-verifier-and-screenshots.md)),
and CI runs JVM unit tests. Anything whose correctness we can only find out about
on a user's phone is a bad bet, and a workbook that silently fails to open is
exactly that shape of bug — the file downloads, and then Excel refuses it.

## Decision

**No new dependency for either format.**

**The workbook is written by hand**, in
[`data/export/Xlsx.kt`](../../app/src/main/java/com/gproust/sprout/data/export/Xlsx.kt).
An `.xlsx` file is a zip of a handful of XML parts, and the subset we need —
typed cells, a bold frozen header row, three number formats, an autofilter — is
about two hundred lines against `java.util.zip`. It is a writer only; it cannot
read a workbook and has no parser in it.

The writer is deliberately narrow: no formulas, no shared-string table (cells
carry their text inline), no merged cells, no charts. Cells are typed — a date is
a serial number with a date format, a time is a fraction of a day, a millilitre
figure is a number — so nothing arrives as a string with a unit glued to it, and
a value nobody recorded is a genuinely empty cell rather than a zero.

**The PDF is drawn onto `PdfDocument`'s canvas**, in
[`ui/report/ReportPdf.kt`](../../app/src/main/java/com/gproust/sprout/ui/report/ReportPdf.kt).
The document is built as a list of blocks whose heights are known before
anything is drawn, laid onto pages, and only then rendered. That indirection
earns its keep twice: every page can say "3 of 5" because the total is known
before the first page is started, and a table that outgrows a page can be given
its header again at the top of the next one rather than discovering the break
afterwards.

Paper size follows the device's region — Letter where letter is the paper, A4
everywhere else. It is not a setting; a page silently scaled and clipped by the
printer is worse than either choice made on the user's behalf.

**What CI can prove, it proves.** The figures are pure functions over entities
and were already testable; the range arithmetic and the report assembly are the
same. The workbook is asserted by unzipping the bytes and reading the parts back,
which is the check that catches a file no reader will open. The PDF's *layout* is
not unit-tested — it is drawing, and drawing is what the screenshots in a pull
request are for — but everything it prints comes from the objects that are.

## Consequences

- The app gains no dependency, no permission and no parser, and the APK grows by
  the size of two source files.
- The workbook writer covers what this report needs and nothing else. A future
  export wanting a second header row or a formula will have to add it; that is
  a few lines against a documented format, and it is the price of not carrying a
  library for the ninety per cent of it we never call.
- `PdfDocument` renders with the device's own fonts. A phone missing a glyph
  prints what that phone would print anywhere else — acceptable, and the only
  alternative is embedding a font we would then have to license and ship.
- The block layout means a very long report is laid out in memory before it is
  written. For the ranges this offers — a year of days is a few hundred blocks —
  that is nothing; a report over a decade would want streaming, and there is no
  decade to report on in a newborn tracker.
