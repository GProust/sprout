package com.gproust.sprout.ui.medicines

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.gproust.sprout.R
import com.gproust.sprout.data.MedicineLevel
import com.gproust.sprout.data.MedicineReadiness
import com.gproust.sprout.data.TooSoonReason
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.ui.common.formatDecimal
import com.gproust.sprout.ui.common.formatDuration

/*
 * How a medicine's state is drawn and worded.
 *
 * Their own file because two screens say the same things about the same
 * medicine: the as-needed list, and the dashboard card that tells a parent a
 * wait is running without making them go and look (BDR-16). Two copies of
 * "Too soon — 4h to wait" would eventually disagree, and the screen that was
 * wrong would be the one nobody was looking at.
 */

/**
 * The three state colours.
 *
 * Fixed values rather than the colour scheme's, because dynamic colour follows
 * the wallpaper (see `SproutTheme`) and a traffic light whose red is whatever
 * the phone's accent happens to be is not a traffic light. The warm middle is
 * the one the statistics already use, which was chosen by running a
 * colour-vision check rather than by eye.
 */
@Composable
internal fun levelColor(level: MedicineLevel): Color {
    val dark = isSystemInDarkTheme()
    return when (level) {
        MedicineLevel.TOO_SOON -> if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
        MedicineLevel.SOONER_THAN_IDEAL -> if (dark) Color(0xFFCF8949) else Color(0xFFB26A2B)
        MedicineLevel.READY -> if (dark) Color(0xFF7FD0A6) else Color(0xFF1B5E3F)
    }
}

/** A different shape per state, so the three differ by more than their hue. */
internal fun levelIcon(level: MedicineLevel): ImageVector = when (level) {
    MedicineLevel.TOO_SOON -> Icons.Filled.HourglassTop
    MedicineLevel.SOONER_THAN_IDEAL -> Icons.Filled.Schedule
    MedicineLevel.READY -> Icons.Filled.CheckCircle
}

/** The sentence the card leads with — the state in words, never colour alone. */
internal fun stateSentence(context: Context, readiness: MedicineReadiness, now: Long): String =
    when (readiness.level) {
        MedicineLevel.TOO_SOON -> {
            val left = formatDuration(context, (readiness.nextAllowedAt ?: now) - now)
            when (readiness.reason) {
                TooSoonReason.DAILY_MAXIMUM ->
                    context.getString(R.string.medicine_state_daily_max, left)
                // Named apart from the dose count, because "you have used the
                // day's 1.5 cm" and "that would be the seventh today" are two
                // different things to have run out of.
                TooSoonReason.DAILY_AMOUNT ->
                    context.getString(R.string.medicine_state_daily_amount, left)
                else -> context.getString(R.string.medicine_state_too_soon, left)
            }
        }
        MedicineLevel.SOONER_THAN_IDEAL -> context.getString(
            R.string.medicine_state_early,
            formatDuration(context, (readiness.comfortableAt ?: now) - now),
        )
        MedicineLevel.READY -> context.getString(R.string.medicine_state_ready)
    }

/**
 * The state in as few words as a line can carry: "4 h 12 m to wait", "1 h
 * early", or nothing at all once the full wait has passed.
 *
 * The dashboard's line has room for a name and a number and no more, and a bare
 * duration would not say whether it is a wait or a head start — so the number
 * keeps the one word that says which. Green has no number, because there is
 * nothing left to count: the name's colour and the tick beside it are the whole
 * message, and [stateSentence] is still what a screen reader is given.
 */
internal fun shortState(context: Context, readiness: MedicineReadiness, now: Long): String? =
    when (readiness.level) {
        MedicineLevel.TOO_SOON -> context.getString(
            R.string.medicine_short_wait,
            formatDuration(context, (readiness.nextAllowedAt ?: now) - now),
        )
        MedicineLevel.SOONER_THAN_IDEAL -> context.getString(
            R.string.medicine_short_early,
            formatDuration(context, (readiness.comfortableAt ?: now) - now),
        )
        MedicineLevel.READY -> null
    }

/**
 * "Last dose 03:20 · 2 of 4 in the last 24 h · 0.5 of 1.5 cm", or that it has
 * never been given.
 *
 * The quantity is only there when the parent gave a daily one to count against:
 * a medicine measured in whole doses says nothing about millilitres, and a
 * running total nobody set a limit for is a number with no question behind it.
 */
internal fun lastDoseLine(context: Context, readiness: MedicineReadiness): String {
    val last = readiness.lastDoseAt ?: return context.getString(R.string.medicine_never_given)
    val count = readiness.maxPerDay
        ?.let { context.getString(R.string.medicine_day_count, readiness.dosesInLastDay, it) }
        ?: context.getString(R.string.medicine_day_count_plain, readiness.dosesInLastDay)
    val separator = context.getString(R.string.feeding_detail_separator)
    val amount = readiness.maxAmountPerDay?.let { max ->
        context.getString(
            R.string.medicine_day_amount,
            formatDecimal(readiness.amountInLastDay),
            formatDecimal(max),
            readiness.unit.orEmpty(),
        )
    }
    return listOfNotNull(
        context.getString(R.string.medicine_last_dose, formatDateTime(context, last)),
        count,
        amount,
    ).joinToString(separator)
}

/** "0.25 cm" — an amount in the medicine's own unit, or null when it has none. */
internal fun amountLabel(context: Context, amount: Double?, unit: String?): String? {
    if (amount == null) return null
    val value = formatDecimal(amount)
    return unit?.takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.medicine_amount_value, value, it) }
        ?: value
}

/**
 * "Every 6 h to 8 h", "Every 6 h" when only a minimum was set, or "No set gap"
 * for a medicine whose leaflet gave none at all (BDR-18).
 */
internal fun intervalSummary(context: Context, medicine: MedicineEntity): String {
    if (medicine.minIntervalMinutes <= 0) return context.getString(R.string.medicine_no_gap)
    val min = formatDuration(context, medicine.minIntervalMinutes * 60_000L)
    val comfort = medicine.comfortIntervalMinutes
        ?.takeIf { it > medicine.minIntervalMinutes }
        ?.let { formatDuration(context, it * 60_000L) }
    return if (comfort == null) {
        context.getString(R.string.medicine_every, min)
    } else {
        context.getString(R.string.medicine_every_range, min, comfort)
    }
}
