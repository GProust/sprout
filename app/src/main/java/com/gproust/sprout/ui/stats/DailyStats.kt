package com.gproust.sprout.ui.stats

import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.SleepEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turning the logs into "what did a day actually look like".
 *
 * Everything here is a pure function over entities the caller has already
 * filtered to one baby, so it can be unit-tested without a database, a clock
 * or a device — which matters, because CI is the only place this project ever
 * builds (ADR-0006).
 *
 * Two rules decide which day an entry lands on, and they differ on purpose:
 *
 * - **A feed or a change belongs to the day it started.** They are short; a
 *   feed at 23:55 counting entirely towards that evening is what a parent
 *   would say happened.
 * - **A sleep is split across the days it covers.** They are not short: a
 *   night from 20:00 to 06:00 is ten hours, and putting all ten on the evening
 *   would leave the following day looking sleepless. So each day gets the part
 *   of the night that fell inside it, while the *count* of sleeps still goes
 *   to the day the baby dropped off — "three naps" means three times settled,
 *   not three fragments of clock.
 */

/**
 * One calendar day's totals, in local time.
 *
 * Amounts are only ever the recorded ones: a bottle logged without its
 * millilitres, or a purée without its grams, adds to the *count* and leaves
 * the quantity alone. A day where nothing was weighed reads as 0 g because
 * nothing was weighed, not because the baby ate nothing — which is why the
 * screen shows counts and quantities side by side rather than one from the
 * other.
 */
data class DayStats(
    val date: LocalDate,
    val breastCount: Int = 0,
    /** Time at the breast, summed over the day's sessions. */
    val breastMillis: Long = 0L,
    val bottleCount: Int = 0,
    val bottleMl: Int = 0,
    val solidCount: Int = 0,
    val solidGrams: Int = 0,
    /** How many times the baby went to sleep — counted on the day they dropped off. */
    val sleepCount: Int = 0,
    /** How much of the day was spent asleep; a night is split across its two days. */
    val sleepMillis: Long = 0L,
    /** Changes with urine present. A change can be both wet and dirty. */
    val wetCount: Int = 0,
    /** Changes with stool present. */
    val dirtyCount: Int = 0,
    /** Changes logged, however they were made up. */
    val diaperCount: Int = 0,
) {
    /** Feeds of every kind — the "how many times did they eat today" number. */
    val feedCount: Int get() = breastCount + bottleCount + solidCount
}

/**
 * How long a breastfeed lasted, in millis.
 *
 * Three generations of the entity have to answer this: sessions timed stretch
 * by stretch (the segments), the per-side totals that came before them, and
 * the plain start/end of a feed entered by hand. Anything that would come out
 * negative — a session still running, an end time typed before its start —
 * counts as nothing rather than as a subtraction.
 */
fun breastfeedMillis(entry: FeedingEntity): Long = when {
    entry.segments.isNotEmpty() ->
        entry.segments.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }

    entry.leftDurationMs != null || entry.rightDurationMs != null ->
        (entry.leftDurationMs ?: 0L).coerceAtLeast(0L) +
            (entry.rightDurationMs ?: 0L).coerceAtLeast(0L)

    entry.endTime != null -> (entry.endTime - entry.startTime).coerceAtLeast(0L)
    else -> 0L
}

/**
 * The first day a window of [days] ending on [today] covers.
 *
 * The window ends today and starts `days - 1` days earlier, so "7 days" is
 * this week including today rather than eight columns.
 *
 * It never reaches back past [birthDay], and that is not cosmetic: the days in
 * the window are what the averages divide by, so a twelve-day-old read over
 * thirty days would have every figure halved by eighteen days they did not
 * live — a newborn feeding ten times a day would be reported as feeding four.
 * The window shrinking is the honest answer, and the screen says how many days
 * it actually averaged over.
 *
 * A birth date in the future — typed ahead of a due date — still leaves today,
 * rather than a window that ends before it starts.
 */
fun statsWindowStart(today: LocalDate, days: Int, birthDay: LocalDate?): LocalDate {
    val start = today.minusDays((days - 1).coerceAtLeast(0).toLong())
    val notBeforeBirth = if (birthDay != null && birthDay.isAfter(start)) birthDay else start
    return if (notBeforeBirth.isAfter(today)) today else notBeforeBirth
}

/**
 * One [DayStats] per calendar day from [from] to [to] inclusive, oldest first.
 *
 * Days with nothing logged are present and empty rather than missing: a gap in
 * a bar chart is information, and an average over "the days that had entries"
 * would quietly flatter a day the baby was in hospital and nobody was logging.
 *
 * [now] closes a sleep that is still running, so an ongoing nap counts up to
 * the moment the screen is looking, and no further.
 */
fun dailyStats(
    feedings: List<FeedingEntity>,
    sleeps: List<SleepEntity>,
    diapers: List<DiaperEntity>,
    from: LocalDate,
    to: LocalDate,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DayStats> {
    if (to.isBefore(from)) return emptyList()

    val days = generateSequence(from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(to) }
        .toList()
    val building = days.associateWith { Builder() }

    for (entry in feedings) {
        val day = building[entry.startTime.toLocalDate(zone)] ?: continue
        when (entry.type) {
            FeedType.BREAST -> {
                day.breastCount++
                day.breastMillis += breastfeedMillis(entry)
            }

            FeedType.BOTTLE -> {
                day.bottleCount++
                day.bottleMl += entry.amountMl ?: 0
            }

            FeedType.SOLID -> {
                day.solidCount++
                day.solidGrams += entry.amountGrams ?: 0
            }
        }
    }

    for (entry in sleeps) {
        building[entry.startTime.toLocalDate(zone)]?.let { it.sleepCount++ }
        // An unfinished sleep runs up to `now`; one whose end predates its
        // start (a typo, or a clock that moved) contributes no time at all.
        val end = (entry.endTime ?: now).coerceAtLeast(entry.startTime)
        for ((day, millis) in splitByDay(entry.startTime, end, zone)) {
            building[day]?.let { it.sleepMillis += millis }
        }
    }

    for (entry in diapers) {
        val day = building[entry.time.toLocalDate(zone)] ?: continue
        day.diaperCount++
        if (entry.wet) day.wetCount++
        if (entry.dirty) day.dirtyCount++
    }

    return days.map { building.getValue(it).build(it) }
}

/**
 * Per-day averages over the window.
 *
 * Today is left out: it is still being lived, and a morning's worth of feeds
 * averaged in with whole days would drag every figure down and make a normal
 * week look like a declining one. When today is all there is — a fresh
 * install, or a one-day window — it is used rather than showing nothing.
 */
data class StatsAverages(
    /** How many days the averages are actually over. */
    val dayCount: Int = 0,
    val feedsPerDay: Double = 0.0,
    val breastfeedsPerDay: Double = 0.0,
    val breastMillisPerDay: Long = 0L,
    val bottlesPerDay: Double = 0.0,
    val bottleMlPerDay: Int = 0,
    val solidsPerDay: Double = 0.0,
    val solidGramsPerDay: Int = 0,
    val sleepsPerDay: Double = 0.0,
    val sleepMillisPerDay: Long = 0L,
    val diapersPerDay: Double = 0.0,
    val wetPerDay: Double = 0.0,
    val dirtyPerDay: Double = 0.0,
)

/** The averages of [days], counting only the days before [today]. See [StatsAverages]. */
fun averagesOf(days: List<DayStats>, today: LocalDate): StatsAverages {
    val complete = days.filter { it.date.isBefore(today) }
    val counted = complete.ifEmpty { days }
    if (counted.isEmpty()) return StatsAverages()

    val n = counted.size
    fun mean(of: (DayStats) -> Long): Double = counted.sumOf(of).toDouble() / n

    return StatsAverages(
        dayCount = n,
        feedsPerDay = mean { it.feedCount.toLong() },
        breastfeedsPerDay = mean { it.breastCount.toLong() },
        breastMillisPerDay = mean { it.breastMillis }.toLong(),
        bottlesPerDay = mean { it.bottleCount.toLong() },
        bottleMlPerDay = mean { it.bottleMl.toLong() }.toInt(),
        solidsPerDay = mean { it.solidCount.toLong() },
        solidGramsPerDay = mean { it.solidGrams.toLong() }.toInt(),
        sleepsPerDay = mean { it.sleepCount.toLong() },
        sleepMillisPerDay = mean { it.sleepMillis }.toLong(),
        diapersPerDay = mean { it.diaperCount.toLong() },
        wetPerDay = mean { it.wetCount.toLong() },
        dirtyPerDay = mean { it.dirtyCount.toLong() },
    )
}

/**
 * How a stretch from [start] to [end] divides between the calendar days it
 * touches, in millis. A stretch inside one day yields that one day.
 *
 * Day boundaries are asked of the calendar rather than counted in 24-hour
 * steps, so the short and long days either side of a daylight-saving change
 * come out at 23 and 25 hours, as they were actually lived.
 */
private fun splitByDay(start: Long, end: Long, zone: ZoneId): Map<LocalDate, Long> {
    if (end <= start) return emptyMap()
    val result = LinkedHashMap<LocalDate, Long>()
    var day = start.toLocalDate(zone)
    val lastDay = end.toLocalDate(zone)
    while (!day.isAfter(lastDay)) {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val overlap = minOf(end, dayEnd) - maxOf(start, dayStart)
        if (overlap > 0) result[day] = overlap
        day = day.plusDays(1)
    }
    return result
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** Mutable scratch for [dailyStats]; one per day, frozen into a [DayStats] at the end. */
private class Builder {
    var breastCount = 0
    var breastMillis = 0L
    var bottleCount = 0
    var bottleMl = 0
    var solidCount = 0
    var solidGrams = 0
    var sleepCount = 0
    var sleepMillis = 0L
    var wetCount = 0
    var dirtyCount = 0
    var diaperCount = 0

    fun build(date: LocalDate) = DayStats(
        date = date,
        breastCount = breastCount,
        breastMillis = breastMillis,
        bottleCount = bottleCount,
        bottleMl = bottleMl,
        solidCount = solidCount,
        solidGrams = solidGrams,
        sleepCount = sleepCount,
        sleepMillis = sleepMillis,
        wetCount = wetCount,
        dirtyCount = dirtyCount,
        diaperCount = diaperCount,
    )
}
