package com.gproust.sprout.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.gproust.sprout.R
import com.gproust.sprout.data.local.TreatmentEntity
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Schedules treatment reminders with [AlarmManager]. We use inexact
 * `setAndAllowWhileIdle` alarms (one per reminder time), which fire even in Doze
 * and need no special permission — appropriate for medication reminders where a
 * few minutes' drift is fine. Each fired alarm reschedules the next occurrence
 * (see [ReminderReceiver]).
 */
object TreatmentReminders {
    const val CHANNEL_ID = "treatment_reminders"
    const val ACTION_FIRE = "com.gproust.sprout.TREATMENT_REMINDER"
    const val EXTRA_TREATMENT_ID = "treatment_id"
    const val EXTRA_SLOT = "slot"

    /** Upper bound of reminder times per treatment; sets the request-code spacing. */
    private const val SLOTS_PER_TREATMENT = 100

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.treatment_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.treatment_channel_desc) },
            )
        }
    }

    fun scheduleAll(context: Context, treatments: List<TreatmentEntity>) {
        treatments.forEach { schedule(context, it) }
    }

    /** Cancel then (re)schedule every reminder time for [treatment]. */
    fun schedule(context: Context, treatment: TreatmentEntity) {
        cancel(context, treatment)
        if (!treatment.remindersEnabled || !treatment.active) return
        val alarm = context.getSystemService<AlarmManager>() ?: return
        val now = System.currentTimeMillis()
        treatment.timesOfDay.forEachIndexed { index, minute ->
            val trigger = nextTrigger(treatment, minute, now) ?: return@forEachIndexed
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger,
                alarmIntent(context, treatment.id, index),
            )
        }
    }

    /** Cancel all of a treatment's alarms (a fixed slot range, so removed times don't orphan). */
    fun cancel(context: Context, treatment: TreatmentEntity) {
        val alarm = context.getSystemService<AlarmManager>() ?: return
        for (index in 0 until SLOTS_PER_TREATMENT) {
            alarm.cancel(alarmIntent(context, treatment.id, index))
        }
    }

    private fun alarmIntent(context: Context, treatmentId: Long, slot: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TREATMENT_ID, treatmentId)
            putExtra(EXTRA_SLOT, slot)
        }
        val requestCode = (treatmentId * SLOTS_PER_TREATMENT + slot).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Next epoch-millis the treatment is due at [minuteOfDay], strictly after
     * [after]; null once the course's end date has passed.
     */
    fun nextTrigger(treatment: TreatmentEntity, minuteOfDay: Int, after: Long): Long? {
        val zone = ZoneId.systemDefault()
        val startDay = Instant.ofEpochMilli(treatment.startDate).atZone(zone).toLocalDate()
        val afterDate = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        val interval = treatment.intervalDays.coerceAtLeast(1)

        // First dosing-grid day that is on/after both the start and the cutoff day.
        var day = maxOf(startDay, afterDate)
        val remainder = ((ChronoUnit.DAYS.between(startDay, day) % interval) + interval) % interval
        if (remainder != 0L) day = day.plusDays(interval - remainder)

        var trigger = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        while (trigger <= after) {
            day = day.plusDays(interval.toLong())
            trigger = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }

        treatment.endDate?.let { end ->
            val endDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
            if (day.isAfter(endDay)) return null
        }
        return trigger
    }
}
