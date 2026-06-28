package com.gproust.sprout.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gproust.sprout.MainActivity
import com.gproust.sprout.R
import com.gproust.sprout.SproutApplication
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.settings.FeedingReminderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a baby has gone too long without a feed (or after a reboot). Re-checks
 * the current state before posting — the setting may be off, the baby may be gone or
 * archived, or a feed may have just been logged — so a stale alarm never nags.
 */
class FeedingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SproutApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FeedingReminders.ensureChannel(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    FeedingReminders.rescheduleAll(context, app.repository)
                    return@launch
                }

                val babyId = intent.getLongExtra(FeedingReminders.EXTRA_BABY_ID, -1L)
                if (babyId < 0) return@launch
                if (!FeedingReminderSettings.isEnabled(context)) return@launch

                val baby = app.repository.activeBaby(babyId) ?: return@launch
                val last = app.repository.lastFeedTime(babyId) ?: return@launch
                val interval = FeedingReminderSettings.intervalMinutes(context)
                val now = System.currentTimeMillis()
                // A feed logged after this alarm was armed would have re-armed it; guard anyway.
                if (!feedingReminderOverdue(last, now, interval)) return@launch

                notify(context, babyId, baby.name, now - last)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, babyId: Long, babyName: String?, sinceMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = if (babyName.isNullOrBlank()) {
            context.getString(R.string.feeding_notif_title)
        } else {
            context.getString(R.string.feeding_notif_title_baby, babyName)
        }
        val text = context.getString(R.string.feeding_notif_text, formatDuration(context, sinceMs))

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, FeedingReminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(
            FeedingReminders.notificationId(babyId),
            notification,
        )
    }
}
