package com.gproust.sprout.ui.common

import android.content.Context
import com.gproust.sprout.R
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The ages at which babies commonly go through a growth spurt. These are
 * tendencies, not a schedule: every baby is different, so windows are shown
 * as "around this age" periods and worded gently throughout the app.
 *
 * [startDay] and [endDay] are the baby's age in days, both inclusive. The
 * label is the commonly quoted age ("around 3 weeks" / "around 6 months"),
 * carried as a number so each locale can decline it correctly.
 */
data class GrowthSpurtWindow(
    val startDay: Int,
    val endDay: Int,
    val approxWeeks: Int? = null,
    val approxMonths: Int? = null,
)

/** Commonly cited growth spurt periods over the first year, in age order. */
val GROWTH_SPURT_WINDOWS = listOf(
    GrowthSpurtWindow(7, 10, approxWeeks = 1),
    GrowthSpurtWindow(14, 21, approxWeeks = 3),
    GrowthSpurtWindow(40, 48, approxWeeks = 6),
    GrowthSpurtWindow(85, 95, approxMonths = 3),
    GrowthSpurtWindow(175, 186, approxMonths = 6),
    GrowthSpurtWindow(265, 277, approxMonths = 9),
)

/** How many days ahead of a window we start saying "one may be coming". */
const val GROWTH_SPURT_HEADS_UP_DAYS = 3

/** A baby's age in whole days at [now] (calendar days, local time). */
fun ageInDays(birthDateMillis: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val birth = Instant.ofEpochMilli(birthDateMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(birth, today)
}

/** The typical spurt window the baby is currently in, if any. */
fun currentGrowthSpurt(ageDays: Long): GrowthSpurtWindow? =
    GROWTH_SPURT_WINDOWS.firstOrNull { ageDays in it.startDay..it.endDay }

/** The next typical spurt window that hasn't started yet, if any remain. */
fun nextGrowthSpurt(ageDays: Long): GrowthSpurtWindow? =
    GROWTH_SPURT_WINDOWS.firstOrNull { ageDays < it.startDay }

/** The next window, only when it starts within [withinDays] days. */
fun upcomingGrowthSpurt(ageDays: Long, withinDays: Int = GROWTH_SPURT_HEADS_UP_DAYS): GrowthSpurtWindow? =
    nextGrowthSpurt(ageDays)?.takeIf { it.startDay - ageDays <= withinDays }

/** Localized "around 3 weeks" / "around 6 months" phrase for a window. */
fun growthSpurtAgeLabel(context: Context, window: GrowthSpurtWindow): String {
    val res = context.resources
    return when {
        window.approxWeeks != null ->
            res.getQuantityString(R.plurals.growth_spurt_weeks, window.approxWeeks, window.approxWeeks)
        window.approxMonths != null ->
            res.getQuantityString(R.plurals.growth_spurt_months, window.approxMonths, window.approxMonths)
        else -> ""
    }
}
