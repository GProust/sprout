package com.gproust.sprout.ui.report

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.ui.stats.DayStats
import com.gproust.sprout.ui.stats.GrowthMeasure
import com.gproust.sprout.ui.stats.StatsAverages
import com.gproust.sprout.ui.stats.WhoPlacement
import com.gproust.sprout.ui.stats.WhoSex
import com.gproust.sprout.ui.stats.ageInMonths
import com.gproust.sprout.ui.stats.averagesOf
import com.gproust.sprout.ui.stats.dailyStats
import com.gproust.sprout.ui.stats.whoPlacement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Everything a report says, worked out once from the entries the family made.
 *
 * This is deliberately a pure function of the logs, the range and the clock:
 * the PDF and the workbook are two renderings of the same object, so a doctor
 * reading the document and a doctor pivoting the spreadsheet cannot be looking
 * at different numbers. It also means the arithmetic is testable on the JVM,
 * which is the only place this project can test anything (ADR-0006).
 *
 * Nothing here interprets. Every field is a count, a total or a reading of the
 * published WHO tables; there is no threshold, no flag and no assessment
 * (BDR-0012).
 */

/** What the parent asked for on the export screen. */
data class ReportOptions(
    val period: ReportPeriod = ReportPeriod.MONTH,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null,
    /**
     * Which WHO reference the growth pages are read against; null means both,
     * and both is the default. A view choice, never stored and never asked for
     * (BDR-0008) — it lives here for the length of one export and no longer.
     */
    val reference: WhoSex? = null,
    val includeDailyTable: Boolean = true,
    val includeTreatments: Boolean = true,
    /** Off by default: free text is where a parent writes what they meant for themselves. */
    val includeNotes: Boolean = false,
)

/** One measurement, and where it sits against the references. */
data class GrowthReading(
    val entry: GrowthEntity,
    val ageMonths: Double,
    val ageDays: Long,
    /** Null when that measure was not taken, or the age is past the WHO tables. */
    val weight: WhoPlacement?,
    val length: WhoPlacement?,
    val head: WhoPlacement?,
) {
    fun placement(measure: GrowthMeasure): WhoPlacement? = when (measure) {
        GrowthMeasure.WEIGHT -> weight
        GrowthMeasure.LENGTH -> length
        GrowthMeasure.HEAD -> head
    }
}

/**
 * A treatment course as it sits across the report's own days.
 *
 * [startIndex] and [endIndex] are day indices into the range, clamped to it, so
 * the timeline can be drawn without knowing any dates; [startsBefore] and
 * [runsPast] are what stop a clamped bar from claiming a start or an end the
 * course never had.
 */
data class TreatmentCourse(
    val treatment: TreatmentEntity,
    val startIndex: Int,
    /** Exclusive: a course covering only the first day is 0 to 1. */
    val endIndex: Int,
    val startsBefore: Boolean,
    val runsPast: Boolean,
    /**
     * Day indices on which a dose was scheduled, for courses given less often
     * than daily. Empty for a daily course, which is drawn as a solid bar —
     * marking every day of one would say nothing and draw the eye to nothing.
     */
    val doseDays: List<Int>,
)

/** The whole of a report, ready to be drawn or written out. */
data class ReportContent(
    val babyName: String,
    val birthDate: Long,
    val generatedAt: Long,
    val range: ReportRange,
    val options: ReportOptions,
    val days: List<DayStats>,
    val averages: StatsAverages,
    /** Days in the range that had at least one entry of any kind. */
    val daysWithEntries: Int,
    val bottleCount: Int,
    /** How many of those bottles had a volume written down. */
    val bottlesWithVolume: Int,
    val solidCount: Int,
    val solidsWithGrams: Int,
    val longestSleepMillis: Long,
    /** Stool colours seen in the range, commonest first; only where one was recorded. */
    val stoolColours: List<Pair<StoolColor, Int>>,
    /** Measurements over the whole history — a curve is only worth reading over months. */
    val growth: List<GrowthReading>,
    val treatments: List<TreatmentCourse>,
    /** The raw entries inside the range, oldest first, for the workbook. */
    val feedings: List<FeedingEntity>,
    val sleeps: List<SleepEntity>,
    val diapers: List<DiaperEntity>,
) {
    val feedTotal: Int get() = days.sumOf { it.feedCount }
    val breastTotal: Int get() = days.sumOf { it.breastCount }
    val breastMillisTotal: Long get() = days.sumOf { it.breastMillis }
    val bottleMlTotal: Int get() = days.sumOf { it.bottleMl }
    val solidGramsTotal: Int get() = days.sumOf { it.solidGrams }
    val sleepMillisTotal: Long get() = days.sumOf { it.sleepMillis }
    val sleepCountTotal: Int get() = days.sumOf { it.sleepCount }
    val diaperTotal: Int get() = days.sumOf { it.diaperCount }
    val wetTotal: Int get() = days.sumOf { it.wetCount }
    val dirtyTotal: Int get() = days.sumOf { it.dirtyCount }

    /** Whether anything at all was logged — an empty period says so once, plainly. */
    val hasEntries: Boolean get() = daysWithEntries > 0
}

/**
 * Assembles [ReportContent] from one baby's logs.
 *
 * [growth] and [treatments] are the baby's whole history; the feeds, sleeps and
 * changes may be too, and are filtered to the range here. A sleep counts as
 * being in the range when any part of it is: a night that began the evening
 * before belongs to the morning it woke into, which is the same rule the daily
 * figures are split by.
 */
@Suppress("LongParameterList")
fun buildReport(
    baby: BabyEntity,
    feedings: List<FeedingEntity>,
    sleeps: List<SleepEntity>,
    diapers: List<DiaperEntity>,
    growth: List<GrowthEntity>,
    treatments: List<TreatmentEntity>,
    options: ReportOptions,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): ReportContent {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val birthDay = Instant.ofEpochMilli(baby.birthDate).atZone(zone).toLocalDate()
    val range = reportRange(options.period, today, birthDay, options.customFrom, options.customTo)

    val startMillis = range.from.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val feedsInRange = feedings
        .filter { it.startTime in startMillis until endMillis }
        .sortedBy { it.startTime }
    val diapersInRange = diapers
        .filter { it.time in startMillis until endMillis }
        .sortedBy { it.time }
    val sleepsInRange = sleeps
        .filter { it.startTime < endMillis && (it.endTime ?: now) >= startMillis }
        .sortedBy { it.startTime }

    val days = dailyStats(feedsInRange, sleepsInRange, diapersInRange, range.from, range.to, now, zone)

    val bottles = feedsInRange.filter { it.type == FeedType.BOTTLE }
    val solids = feedsInRange.filter { it.type == FeedType.SOLID }

    val colours = diapersInRange
        .mapNotNull { it.stoolColor }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedWith(compareByDescending<Pair<StoolColor, Int>> { it.second }.thenBy { it.first.ordinal })

    return ReportContent(
        babyName = baby.name,
        birthDate = baby.birthDate,
        generatedAt = now,
        range = range,
        options = options,
        days = days,
        averages = averagesOf(days, today),
        daysWithEntries = days.count { it.feedCount > 0 || it.sleepCount > 0 || it.diaperCount > 0 },
        bottleCount = bottles.size,
        bottlesWithVolume = bottles.count { it.amountMl != null },
        solidCount = solids.size,
        solidsWithGrams = solids.count { it.amountGrams != null },
        longestSleepMillis = sleepsInRange.maxOfOrNull {
            ((it.endTime ?: now) - it.startTime).coerceAtLeast(0L)
        } ?: 0L,
        stoolColours = colours,
        growth = growthReadings(baby.birthDate, growth, options.reference),
        treatments = if (options.includeTreatments) {
            treatmentCourses(treatments, range, zone)
        } else {
            emptyList()
        },
        feedings = feedsInRange,
        sleeps = sleepsInRange,
        diapers = diapersInRange,
    )
}

/**
 * Every measurement read against the references, oldest first.
 *
 * A measure that was not taken stays absent rather than becoming a zero: a head
 * circumference nobody measured is a gap in the line, not a head of no size.
 */
fun growthReadings(
    birthDate: Long,
    growth: List<GrowthEntity>,
    reference: WhoSex?,
): List<GrowthReading> = growth.sortedBy { it.time }.map { entry ->
    val ageMonths = ageInMonths(birthDate, entry.time)
    fun read(measure: GrowthMeasure, value: Double?): WhoPlacement? =
        value?.let { whoPlacement(measure, ageMonths, it, reference) }

    GrowthReading(
        entry = entry,
        ageMonths = ageMonths,
        ageDays = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(birthDate).atZone(ZoneId.systemDefault()).toLocalDate(),
            Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()).toLocalDate(),
        ),
        weight = read(GrowthMeasure.WEIGHT, entry.weightGrams?.let { it / 1000.0 }),
        length = read(GrowthMeasure.LENGTH, entry.heightMm?.let { it / 10.0 }),
        head = read(GrowthMeasure.HEAD, entry.headMm?.let { it / 10.0 }),
    )
}

/**
 * The courses that were running at any point in [range], with their bars
 * clamped to it.
 *
 * A course is "running" between its start day and its end day inclusive — the
 * same inclusive end the treatments list uses to decide what is over — and one
 * with no end date runs on.
 */
fun treatmentCourses(
    treatments: List<TreatmentEntity>,
    range: ReportRange,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TreatmentCourse> {
    fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    return treatments
        .mapNotNull { treatment ->
            val start = dayOf(treatment.startDate)
            val end = treatment.endDate?.let { dayOf(it) }
            if (start.isAfter(range.to)) return@mapNotNull null
            if (end != null && end.isBefore(range.from)) return@mapNotNull null

            val firstShown = if (start.isBefore(range.from)) range.from else start
            val lastShown = if (end == null || end.isAfter(range.to)) range.to else end
            val startIndex = range.indexOf(firstShown) ?: 0
            val endIndex = (range.indexOf(lastShown) ?: (range.dayCount - 1)) + 1

            val interval = treatment.intervalDays.coerceAtLeast(1)
            val doseDays = if (interval == 1) {
                emptyList()
            } else {
                (startIndex until endIndex).filter { index ->
                    val day = range.from.plusDays(index.toLong())
                    ChronoUnit.DAYS.between(start, day) % interval == 0L
                }
            }

            TreatmentCourse(
                treatment = treatment,
                startIndex = startIndex,
                endIndex = endIndex,
                startsBefore = start.isBefore(range.from),
                runsPast = end == null || end.isAfter(range.to),
                doseDays = doseDays,
            )
        }
        .sortedWith(compareBy({ it.startIndex }, { it.treatment.name }))
}
