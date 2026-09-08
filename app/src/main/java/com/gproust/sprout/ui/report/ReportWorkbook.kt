package com.gproust.sprout.ui.report

import com.gproust.sprout.data.export.Xlsx
import com.gproust.sprout.data.export.Xlsx.Cell
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.ui.stats.GrowthMeasure
import com.gproust.sprout.ui.stats.WhoPlacement
import com.gproust.sprout.ui.stats.breastfeedMillis
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The report as a workbook: one sheet per kind of entry, plus the figures the
 * PDF prints, so the two files can never disagree.
 *
 * **The column names are English and stay English**, unlike the PDF, which is
 * translated like the rest of the app. They are the schema of a data file
 * rather than prose: a pivot table or a formula written against `bottle_ml`
 * keeps working when the phone's language changes, and a doctor who is handed
 * the file next year gets the same header row as the one who was handed it
 * today. The sheet the numbers are *read* from is the PDF.
 */
object ReportWorkbook {

    /** The file's sheets, in the order they open. */
    fun build(report: ReportContent, zone: ZoneId = ZoneId.systemDefault()): List<Xlsx.Sheet> =
        buildList {
            add(summary(report, zone))
            add(daily(report))
            add(feeding(report, zone))
            add(sleep(report, zone))
            add(nappies(report, zone))
            add(growth(report, zone))
            if (report.options.includeTreatments) add(treatments(report, zone))
        }

    fun bytes(report: ReportContent, zone: ZoneId = ZoneId.systemDefault()): ByteArray =
        Xlsx.write(build(report, zone))

    // --- the sheets ------------------------------------------------------

    /**
     * A cover sheet, so the file explains itself six months later: whose record
     * it is, what it covers and the same per-day figures the report's front
     * page prints.
     */
    private fun summary(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val rows = mutableListOf<List<Cell>>()
        fun row(field: String, value: Cell, note: String = "") {
            rows += listOf(Cell.Text(field), value, Cell.Text(note))
        }

        row("baby", Cell.Text(report.babyName))
        row("date_of_birth", Cell.Day(report.birthDate.day(zone)))
        row("period_from", Cell.Day(report.range.from))
        row("period_to", Cell.Day(report.range.to))
        row("days_in_period", Cell.Whole(report.range.dayCount.toLong()), "days the baby lived")
        row("days_with_entries", Cell.Whole(report.daysWithEntries.toLong()))
        row("days_averaged", Cell.Whole(report.averages.dayCount.toLong()), "completed days only")
        row("exported_at", Cell.Day(report.generatedAt.day(zone)))
        row("feeds_per_day", Cell.Decimal(report.averages.feedsPerDay))
        row("breastfeeds_per_day", Cell.Decimal(report.averages.breastfeedsPerDay))
        row("breast_minutes_per_day", Cell.Whole(report.averages.breastMillisPerDay.minutes()))
        row("bottles_per_day", Cell.Decimal(report.averages.bottlesPerDay))
        row("bottle_ml_per_day", Cell.Whole(report.averages.bottleMlPerDay.toLong()))
        row(
            "bottles_with_volume",
            Cell.Whole(report.bottlesWithVolume.toLong()),
            "of ${report.bottleCount} bottles logged",
        )
        row("solids_per_day", Cell.Decimal(report.averages.solidsPerDay))
        row("solid_grams_per_day", Cell.Whole(report.averages.solidGramsPerDay.toLong()))
        row(
            "solids_with_weight",
            Cell.Whole(report.solidsWithGrams.toLong()),
            "of ${report.solidCount} solid feeds logged",
        )
        row("sleep_minutes_per_day", Cell.Whole(report.averages.sleepMillisPerDay.minutes()))
        row("sleeps_per_day", Cell.Decimal(report.averages.sleepsPerDay))
        row("longest_sleep_minutes", Cell.Whole(report.longestSleepMillis.minutes()))
        row("nappies_per_day", Cell.Decimal(report.averages.diapersPerDay))
        row("wet_per_day", Cell.Decimal(report.averages.wetPerDay))
        row("dirty_per_day", Cell.Decimal(report.averages.dirtyPerDay))
        row("who_reference", Cell.Text(report.options.reference?.name?.lowercase() ?: "both"))
        row(
            "about",
            Cell.Text("Counts and totals of entries logged in Sprout. Nothing here is an assessment."),
        )

        return Xlsx.Sheet(
            name = "Summary",
            headers = listOf("field", "value", "note"),
            rows = rows,
            widths = listOf(24, 22, 34),
            autoFilter = false,
        )
    }

    /** One row per calendar day, including the days nothing was logged on. */
    private fun daily(report: ReportContent) = Xlsx.Sheet(
        name = "Day by day",
        headers = listOf(
            "date", "feeds", "breastfeeds", "breast_minutes", "bottles", "bottle_ml",
            "solids", "solid_grams", "sleep_minutes", "sleeps", "nappies", "wet", "dirty", "both",
        ),
        rows = report.days.map { day ->
            listOf(
                Cell.Day(day.date),
                Cell.Whole(day.feedCount.toLong()),
                Cell.Whole(day.breastCount.toLong()),
                Cell.Whole(day.breastMillis.minutes()),
                Cell.Whole(day.bottleCount.toLong()),
                Cell.Whole(day.bottleMl.toLong()),
                Cell.Whole(day.solidCount.toLong()),
                Cell.Whole(day.solidGrams.toLong()),
                Cell.Whole(day.sleepMillis.minutes()),
                Cell.Whole(day.sleepCount.toLong()),
                Cell.Whole(day.diaperCount.toLong()),
                Cell.Whole(day.wetCount.toLong()),
                Cell.Whole(day.dirtyCount.toLong()),
                Cell.Whole(day.bothCount.toLong()),
            )
        },
        widths = listOf(12) + List(13) { 13 },
    )

    private fun feeding(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val headers = mutableListOf(
            "date", "start_time", "end_time", "type", "side",
            "duration_minutes", "left_minutes", "right_minutes", "amount_ml", "amount_g",
        )
        if (report.options.includeNotes) headers += "notes"

        return Xlsx.Sheet(
            name = "Feeding",
            headers = headers,
            rows = report.feedings.map { feed ->
                val row = mutableListOf(
                    Cell.Day(feed.startTime.day(zone)),
                    Cell.Clock(feed.startTime.clock(zone)),
                    feed.endTime?.let { Cell.Clock(it.clock(zone)) } ?: Cell.Blank,
                    Cell.Text(feed.type.name.lowercase()),
                    feed.side?.let { Cell.Text(it.name.lowercase()) } ?: Cell.Blank,
                    Cell.Whole(feedDurationMillis(feed).minutes()),
                    feed.leftDurationMs?.let { Cell.Whole(it.minutes()) } ?: Cell.Blank,
                    feed.rightDurationMs?.let { Cell.Whole(it.minutes()) } ?: Cell.Blank,
                    feed.amountMl?.let { Cell.Whole(it.toLong()) } ?: Cell.Blank,
                    feed.amountGrams?.let { Cell.Whole(it.toLong()) } ?: Cell.Blank,
                )
                if (report.options.includeNotes) row += Cell.Text(feed.notes.orEmpty())
                row
            },
            widths = listOf(12, 11, 11, 10, 8, 16, 13, 14, 11, 10, 40),
        )
    }

    private fun sleep(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val headers = mutableListOf(
            "date_started", "start_time", "end_time", "duration_minutes",
            "minutes_before_midnight", "minutes_after_midnight",
        )
        if (report.options.includeNotes) headers += "notes"

        return Xlsx.Sheet(
            name = "Sleep",
            headers = headers,
            rows = report.sleeps.map { sleep ->
                val end = (sleep.endTime ?: report.generatedAt).coerceAtLeast(sleep.startTime)
                val total = end - sleep.startTime
                val midnight = sleep.startTime.day(zone).plusDays(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                val before = (minOf(end, midnight) - sleep.startTime).coerceAtLeast(0L)
                val row = mutableListOf(
                    Cell.Day(sleep.startTime.day(zone)),
                    Cell.Clock(sleep.startTime.clock(zone)),
                    sleep.endTime?.let { Cell.Clock(it.clock(zone)) } ?: Cell.Blank,
                    Cell.Whole(total.minutes()),
                    Cell.Whole(before.minutes()),
                    Cell.Whole((total - before).minutes()),
                )
                if (report.options.includeNotes) row += Cell.Text(sleep.notes.orEmpty())
                row
            },
            widths = listOf(13, 11, 11, 16, 22, 21, 40),
        )
    }

    private fun nappies(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val headers = mutableListOf("date", "time", "wet", "dirty", "stool_colour")
        if (report.options.includeNotes) headers += "notes"

        return Xlsx.Sheet(
            name = "Nappies",
            headers = headers,
            rows = report.diapers.map { change ->
                val row = mutableListOf(
                    Cell.Day(change.time.day(zone)),
                    Cell.Clock(change.time.clock(zone)),
                    Cell.Flag(change.wet),
                    Cell.Flag(change.dirty),
                    change.stoolColor?.let { Cell.Text(it.name.lowercase()) } ?: Cell.Blank,
                )
                if (report.options.includeNotes) row += Cell.Text(change.notes.orEmpty())
                row
            },
            widths = listOf(12, 10, 8, 8, 14, 40),
        )
    }

    /**
     * Every measurement ever taken, not only those inside the range: a curve is
     * read over months, and a fortnight of it is two dots and no shape.
     */
    private fun growth(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val headers = mutableListOf(
            "date", "age_days", "age_months", "weight_g", "length_mm", "head_mm",
            "weight_centile_low", "weight_centile_high",
            "length_centile_low", "length_centile_high",
            "head_centile_low", "head_centile_high",
        )
        if (report.options.includeNotes) headers += "notes"

        fun low(placement: WhoPlacement?) =
            placement?.let { Cell.Decimal(it.lowPercentile) } ?: Cell.Blank

        fun high(placement: WhoPlacement?) =
            placement?.let { Cell.Decimal(it.highPercentile) } ?: Cell.Blank

        return Xlsx.Sheet(
            name = "Growth",
            headers = headers,
            rows = report.growth.map { reading ->
                val row = mutableListOf(
                    Cell.Day(reading.entry.time.day(zone)),
                    Cell.Whole(reading.ageDays),
                    Cell.Decimal(reading.ageMonths),
                    reading.entry.weightGrams?.let { Cell.Whole(it.toLong()) } ?: Cell.Blank,
                    reading.entry.heightMm?.let { Cell.Whole(it.toLong()) } ?: Cell.Blank,
                    reading.entry.headMm?.let { Cell.Whole(it.toLong()) } ?: Cell.Blank,
                    low(reading.placement(GrowthMeasure.WEIGHT)),
                    high(reading.placement(GrowthMeasure.WEIGHT)),
                    low(reading.placement(GrowthMeasure.LENGTH)),
                    high(reading.placement(GrowthMeasure.LENGTH)),
                    low(reading.placement(GrowthMeasure.HEAD)),
                    high(reading.placement(GrowthMeasure.HEAD)),
                )
                if (report.options.includeNotes) row += Cell.Text(reading.entry.notes.orEmpty())
                row
            },
            widths = listOf(12, 10, 11) + List(9) { 14 } + listOf(40),
        )
    }

    /** Courses running at any point in the range — the plan, not an administration record. */
    private fun treatments(report: ReportContent, zone: ZoneId): Xlsx.Sheet {
        val headers = mutableListOf(
            "name", "dose", "interval_days", "times_of_day", "start_date", "end_date", "status",
        )
        if (report.options.includeNotes) headers += "notes"

        return Xlsx.Sheet(
            name = "Treatments",
            headers = headers,
            rows = report.treatments.map { course ->
                val treatment = course.treatment
                val row = mutableListOf(
                    Cell.Text(treatment.name),
                    Cell.Text(treatment.dose.orEmpty()),
                    Cell.Whole(treatment.intervalDays.toLong()),
                    Cell.Text(treatment.timesOfDay.joinToString(", ") { minutes ->
                        "%02d:%02d".format(minutes / 60, minutes % 60)
                    }),
                    Cell.Day(treatment.startDate.day(zone)),
                    treatment.endDate?.let { Cell.Day(it.day(zone)) } ?: Cell.Blank,
                    Cell.Text(if (course.runsPast) "running" else "finished"),
                )
                if (report.options.includeNotes) row += Cell.Text(treatment.notes.orEmpty())
                row
            },
            widths = listOf(20, 16, 14, 18, 12, 12, 11, 40),
        )
    }

    // --- small conversions ------------------------------------------------

    private fun Long.day(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private fun Long.clock(zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(this).atZone(zone).toLocalTime()

    private fun Long.minutes(): Long = this / 60_000L
}

/**
 * How long a feed lasted: the breastfeed rules for a breastfeed, and plain
 * start-to-end for anything else that recorded an end. Never negative.
 */
fun feedDurationMillis(feed: FeedingEntity): Long = when (feed.type) {
    FeedType.BREAST -> breastfeedMillis(feed)
    else -> feed.endTime?.let { (it - feed.startTime).coerceAtLeast(0L) } ?: 0L
}
