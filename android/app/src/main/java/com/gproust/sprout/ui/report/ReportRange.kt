package com.gproust.sprout.ui.report

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How wide a report is, and where it sits.
 *
 * The presets are rolling windows ending today — "30 days" is the last thirty
 * days, not last month's calendar page — which is the arithmetic the Statistics
 * chips already do, so the paper and the screen cannot disagree.
 *
 * [CUSTOM] is the pair of dates the parent picked; [SINCE_BIRTH] reaches back
 * as far as there is anything to reach back to.
 */
enum class ReportPeriod(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    SINCE_BIRTH(null),
    CUSTOM(null),
}

/** The days a report covers, [from] through [to] inclusive. */
data class ReportRange(val from: LocalDate, val to: LocalDate) {

    /** How many calendar days that is; always at least one. */
    val dayCount: Int
        get() = (ChronoUnit.DAYS.between(from, to).toInt() + 1).coerceAtLeast(1)

    /** Every day in the range, oldest first. */
    fun days(): List<LocalDate> = generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(to) }
        .toList()

    /** The index of [day] in the range, or null when it falls outside. */
    fun indexOf(day: LocalDate): Int? {
        if (day.isBefore(from) || day.isAfter(to)) return null
        return ChronoUnit.DAYS.between(from, day).toInt()
    }
}

/**
 * The range a [period] means today, for a baby born on [birthDay].
 *
 * Two clamps do all the work here, and both matter for the same reason the
 * Statistics window has them (BDR-0008): the range never ends after today,
 * because tomorrow has not been lived, and it never begins before the birth,
 * because days the baby did not live are not days — a twelve-day-old asked for
 * "30 days" would otherwise have every average divided by eighteen days of
 * nothing. The report prints the range it actually covered rather than the one
 * that was asked for.
 *
 * A birth date typed ahead of a due date still leaves a range of one day rather
 * than one that ends before it starts.
 */
fun reportRange(
    period: ReportPeriod,
    today: LocalDate,
    birthDay: LocalDate?,
    customFrom: LocalDate? = null,
    customTo: LocalDate? = null,
): ReportRange {
    val end = when (period) {
        ReportPeriod.CUSTOM -> (customTo ?: today).coerceAtMost(today)
        else -> today
    }
    val start = when (period) {
        ReportPeriod.CUSTOM -> customFrom ?: end
        ReportPeriod.SINCE_BIRTH -> birthDay ?: end
        else -> end.minusDays((period.days!! - 1).toLong())
    }

    var from = if (birthDay != null && birthDay.isAfter(start)) birthDay else start
    var to = end
    if (from.isAfter(to)) {
        // A custom range typed back to front, or a birth date in the future:
        // fall back to the single day that is certainly real.
        from = to
    }
    if (to.isBefore(from)) to = from
    return ReportRange(from, to)
}
