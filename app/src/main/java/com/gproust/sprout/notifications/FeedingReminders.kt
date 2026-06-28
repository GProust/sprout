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
import com.gproust.sprout.ui.settings.FeedingReminderSettings

/** Epoch millis at which a baby becomes "overdue" given its last feed. */
fun feedingReminderTrigger(lastFeedTime: Long, intervalMinutes: Int): Long =
    lastFeedTime + intervalMinutes * 60_000L

/** True once [now] is at least [intervalMinutes] past [lastFeedTime]. */
fun feedingReminderOverdue(lastFeedTime: Long, now: Long, intervalMinutes: Int): Boolean =
    now - lastFeedTime >= intervalMinutes * 60_000L

/**
 * Schedules "it's been a while since the last feed" reminders with [AlarmManager].
 *
 * One inexact `setAndAllowWhileIdle` alarm per baby, armed at `lastFeed + maxGap`.
 * Logging a feed re-arms it (so a fed baby is never nudged); the alarm is a single
 * nudge — it does not repeat. A single device-local setting
 * ([FeedingReminderSettings]) turns it on/off and sets the gap for all babies.
 */
object FeedingReminders {
    const val CHANNEL_ID = "feeding_reminders"
    const val ACTION_FIRE = "com.gproust.sprout.FEEDING_REMINDER"
    const val EXTRA_BABY_ID = "baby_id"

    // Offsets to keep our request codes / notification ids clear of treatments'.
    private const val REQUEST_CODE_BASE = 2_000_000
    private const val NOTIFICATION_ID_BASE = 2_000_000

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.feeding_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.feeding_channel_desc) },
            )
        }
    }

    fun notificationId(babyId: Long): Int = (NOTIFICATION_ID_BASE + babyId).toInt()

    /** Cancel then re-arm reminders for every tracked baby based on the current setting. */
    suspend fun rescheduleAll(context: Context, repository: SproutRepository) {
        val enabled = FeedingReminderSettings.isEnabled(context)
        val interval = FeedingReminderSettings.intervalMinutes(context)
        repository.activeBabies().forEach { baby ->
            cancel(context, baby.id)
            if (enabled) armFromLastFeed(context, repository, baby.id, interval)
        }
    }

    /** Re-arm a single baby's reminder after its feeds change (a feed logged or removed). */
    suspend fun rescheduleForBaby(context: Context, repository: SproutRepository, babyId: Long) {
        cancel(context, babyId)
        if (!FeedingReminderSettings.isEnabled(context)) return
        armFromLastFeed(context, repository, babyId, FeedingReminderSettings.intervalMinutes(context))
    }

    /** Convenience for the feeding screen: re-arm whichever baby is currently active. */
    suspend fun rescheduleActiveBaby(context: Context, repository: SproutRepository) {
        val babyId = repository.activeBabyIdNow() ?: return
        rescheduleForBaby(context, repository, babyId)
    }

    private suspend fun armFromLastFeed(
        context: Context,
        repository: SproutRepository,
        babyId: Long,
        intervalMinutes: Int,
    ) {
        val last = repository.lastFeedTime(babyId) ?: return // no feeds yet → nothing to remind about
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            feedingReminderTrigger(last, intervalMinutes),
            alarmIntent(context, babyId),
        )
    }

    fun cancel(context: Context, babyId: Long) {
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.cancel(alarmIntent(context, babyId))
    }

    private fun alarmIntent(context: Context, babyId: Long): PendingIntent {
        val intent = Intent(context, FeedingReminderReceiver::class.java).apply {
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
