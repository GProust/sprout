package com.gproust.sprout.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("MMM d")
private val dateTimeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private fun zone(): ZoneId = ZoneId.systemDefault()

fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).format(timeFmt)

fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).format(dateFmt)

fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).format(dateTimeFmt)

/** Returns the start-of-day epoch millis for the day containing [epochMillis]. */
fun startOfDay(epochMillis: Long): Long =
    Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()
        .atStartOfDay(zone()).toInstant().toEpochMilli()

/** Human readable elapsed time, e.g. "just now", "5m ago", "2h ago", "3d ago". */
fun formatRelative(epochMillis: Long, now: Long): String {
    val deltaMin = (now - epochMillis) / 60_000L
    return when {
        deltaMin < 1 -> "just now"
        deltaMin < 60 -> "${deltaMin}m ago"
        deltaMin < 60 * 24 -> "${deltaMin / 60}h ago"
        else -> "${deltaMin / (60 * 24)}d ago"
    }
}

/** Formats a duration in millis as "1h 20m" / "45m" / "30s". */
fun formatDuration(millis: Long): String {
    val totalMin = abs(millis) / 60_000L
    val hours = totalMin / 60
    val minutes = totalMin % 60
    return when {
        totalMin == 0L -> "${abs(millis) / 1000L}s"
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

/**
 * Baby age relative to [now], e.g. "5 days", "3 weeks, 2 days", "4 months".
 */
fun babyAge(birthDateMillis: Long, now: Long): String {
    val birth = Instant.ofEpochMilli(birthDateMillis).atZone(zone()).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
    if (today.isBefore(birth)) return "not born yet"

    val totalDays = ChronoUnit.DAYS.between(birth, today)
    return when {
        totalDays < 14 -> "$totalDays ${plural(totalDays, "day")}"
        totalDays < 60 -> {
            val weeks = totalDays / 7
            val days = totalDays % 7
            if (days == 0L) "$weeks ${plural(weeks, "week")}"
            else "$weeks ${plural(weeks, "week")}, $days ${plural(days, "day")}"
        }
        else -> {
            val months = monthsBetween(birth, today)
            "$months ${plural(months.toLong(), "month")}"
        }
    }
}

private fun monthsBetween(start: LocalDate, end: LocalDate): Int =
    ChronoUnit.MONTHS.between(start, end).toInt()

private fun plural(value: Long, unit: String): String =
    if (value == 1L) unit else "${unit}s"
