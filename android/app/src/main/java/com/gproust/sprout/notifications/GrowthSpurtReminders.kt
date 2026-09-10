package com.gproust.sprout.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.gproust.sprout.R
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.ui.common.GROWTH_SPURT_WINDOWS
import com.gproust.sprout.ui.settings.GrowthSpurtSettings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Time of day at which a growth spurt heads-up fires (gentle, not a night wake). */
val GROWTH_SPURT_NOTIFY_AT: LocalTime = LocalTime.of(9, 0)

/**
 * Epoch millis of the next growth spurt heads-up for a baby: 09:00 local time
 * on the first day of the next typical spurt window that starts after [now].
 * Null once the baby has outgrown the known windows.
 */
fun nextGrowthSpurtTrigger(
    birthDateMillis: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    val birth = Instant.ofEpochMilli(birthDateMillis).atZone(zone).toLocalDate()
    return GROWTH_SPURT_WINDOWS.asSequence()
        .map { window ->
            birth.plusDays(window.startDay.toLong())
                .atTime(GROWTH_SPURT_NOTIFY_AT).atZone(zone)
                .toInstant().toEpochMilli()
        }
        .firstOrNull { it > now }
}

/**
 * Schedules a heads-up notification when a baby enters a typical growth spurt
 * period, with [AlarmManager].
 *
 * One inexact `setAndAllowWhileIdle` alarm per baby, armed at 09:00 on the
 * first day of the baby's next spurt window; when it fires, the receiver posts
 * the notification and arms the following window. Opt-in via a device-local
 * setting ([GrowthSpurtSettings]), off by default.
 */
object GrowthSpurtReminders {
    const val CHANNEL_ID = "growth_spurt_alerts"
    const val ACTION_FIRE = "com.gproust.sprout.GROWTH_SPURT_ALERT"
    const val EXTRA_BABY_ID = "baby_id"

    // Offsets to keep request codes / notification ids clear of treatments' and feeding's.
    private const val REQUEST_CODE_BASE = 3_000_000
    private const val NOTIFICATION_ID_BASE = 3_000_000

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.growth_spurt_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.growth_spurt_channel_desc) },
            )
        }
    }

    fun notificationId(babyId: Long): Int = (NOTIFICATION_ID_BASE + babyId).toInt()

    /** Cancel then re-arm the next spurt heads-up for every tracked baby. */
    suspend fun rescheduleAll(context: Context, repository: SproutRepository) {
        val enabled = GrowthSpurtSettings.isEnabled(context)
        repository.activeBabies().forEach { baby ->
            cancel(context, baby.id)
            if (enabled) arm(context, baby.id, baby.birthDate)
        }
    }

    private fun arm(context: Context, babyId: Long, birthDateMillis: Long) {
        val trigger = nextGrowthSpurtTrigger(birthDateMillis, System.currentTimeMillis()) ?: return
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(context, babyId))
    }

    fun cancel(context: Context, babyId: Long) {
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.cancel(alarmIntent(context, babyId))
    }

    private fun alarmIntent(context: Context, babyId: Long): PendingIntent {
        val intent = Intent(context, GrowthSpurtReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_BABY_ID, babyId)
        }
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_BASE + babyId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
