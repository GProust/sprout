package com.gproust.sprout.ui.common

import android.content.Context
import com.gproust.sprout.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun zone(): ZoneId = ZoneId.systemDefault()

/** Time-of-day is locale-neutral (24h), so this needs no Context. */
fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).format(timeFmt)

fun formatDate(context: Context, epochMillis: Long): String {
    val fmt = DateTimeFormatter.ofPattern(context.getString(R.string.fmt_date), Locale.getDefault())
    return Instant.ofEpochMilli(epochMillis).atZone(zone()).format(fmt)
}

fun formatDateTime(context: Context, epochMillis: Long): String {
    val fmt = DateTimeFormatter.ofPattern(context.getString(R.string.fmt_datetime), Locale.getDefault())
    return Instant.ofEpochMilli(epochMillis).atZone(zone()).format(fmt)
}

/** Returns the start-of-day epoch millis for the day containing [epochMillis]. */
fun startOfDay(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()
        .atStartOfDay(zone()).toInstant().toEpochMilli()

/** True when both timestamps fall on the same calendar day (local time). */
fun isSameDay(a: Long, b: Long): Boolean {
    val dayA = Instant.ofEpochMilli(a).atZone(zone()).toLocalDate()
    val dayB = Instant.ofEpochMilli(b).atZone(zone()).toLocalDate()
    return dayA == dayB
}

/** A time-of-day greeting such as "Good morning" / "Good afternoon" / "Good evening". */
fun greetingForHour(context: Context, hour: Int): String = context.getString(
    when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..17 -> R.string.greeting_afternoon
        in 18..21 -> R.string.greeting_evening
        else -> R.string.greeting_hello
    },
)

/** A time-of-day greeting for the given instant. */
fun greetingFor(context: Context, epochMillis: Long): String =
    greetingForHour(context, Instant.ofEpochMilli(epochMillis).atZone(zone()).hour)

/** Human readable elapsed time, e.g. "just now", "5m ago", "2h ago", "3d ago". */
fun formatRelative(context: Context, epochMillis: Long, now: Long): String {
    val deltaMin = (now - epochMillis) / 60_000L
    return when {
        deltaMin < 1 -> context.getString(R.string.relative_just_now)
        deltaMin < 60 -> context.getString(R.string.relative_minutes, deltaMin.toInt())
        deltaMin < 60 * 24 -> context.getString(R.string.relative_hours, (deltaMin / 60).toInt())
        else -> context.getString(R.string.relative_days, (deltaMin / (60 * 24)).toInt())
    }
}

/** Formats a duration in millis as "1h 20m" / "45m" / "30s". */
fun formatDuration(context: Context, millis: Long): String {
    val totalMin = abs(millis) / 60_000L
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return when {
        totalMin == 0L -> context.getString(R.string.duration_seconds, (abs(millis) / 1000L).toInt())
        hours == 0L -> context.getString(R.string.duration_minutes, minutes.toInt())
        minutes == 0L -> context.getString(R.string.duration_hours, hours.toInt())
        else -> context.getString(R.string.duration_hours_minutes, hours.toInt(), minutes.toInt())
    }
}

/**
 * Formats a running timer as "M:SS", or "H:MM:SS" once it passes an hour.
 * Digits only, so it's locale-neutral and needs no Context.
 */
fun formatClock(millis: Long): String {
    val totalSec = abs(millis) / 1000L
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Baby age relative to [now], e.g. "5 days", "3 weeks, 2 days", "4 months".
 */
fun babyAge(context: Context, birthDateMillis: Long, now: Long): String {
    val birth = Instant.ofEpochMilli(birthDateMillis).atZone(zone()).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
    if (today.isBefore(birth)) return context.getString(R.string.age_not_born)

    val res = context.resources
    val totalDays = ChronoUnit.DAYS.between(birth, today)
    return when {
        totalDays < 14 ->
            res.getQuantityString(R.plurals.age_days, totalDays.toInt(), totalDays.toInt())
        totalDays < 60 -> {
            val weeks = (totalDays / 7).toInt()
            val days = (totalDays % 7).toInt()
            val weeksStr = res.getQuantityString(R.plurals.age_weeks, weeks, weeks)
            if (days == 0) weeksStr
            else context.getString(
                R.string.age_weeks_days,
                weeksStr,
                res.getQuantityString(R.plurals.age_days, days, days),
            )
        }
        else -> {
            val months = monthsBetween(birth, today)
            res.getQuantityString(R.plurals.age_months, months, months)
        }
    }
}

private fun monthsBetween(start: LocalDate, end: LocalDate): Int =
    ChronoUnit.MONTHS.between(start, end).toInt()
