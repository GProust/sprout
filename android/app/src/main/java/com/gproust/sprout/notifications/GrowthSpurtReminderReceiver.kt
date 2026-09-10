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
import com.gproust.sprout.ui.common.GrowthSpurtWindow
import com.gproust.sprout.ui.common.ageInDays
import com.gproust.sprout.ui.common.currentGrowthSpurt
import com.gproust.sprout.ui.common.growthSpurtAgeLabel
import com.gproust.sprout.ui.settings.GrowthSpurtSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a baby enters a typical growth spurt period (or after a reboot).
 * Re-checks the current state before posting — the setting may be off, or the
 * baby may be gone or archived — so a stale alarm never nags. After posting it
 * re-arms the alarm for the baby's next window.
 */
class GrowthSpurtReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SproutApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GrowthSpurtReminders.ensureChannel(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    GrowthSpurtReminders.rescheduleAll(context, app.repository)
                    return@launch
                }

                val babyId = intent.getLongExtra(GrowthSpurtReminders.EXTRA_BABY_ID, -1L)
                if (babyId < 0) return@launch
                if (!GrowthSpurtSettings.isEnabled(context)) return@launch

                val baby = app.repository.activeBaby(babyId) ?: return@launch
                // Inexact alarms can drift; only speak up while the window is open.
                val window = currentGrowthSpurt(ageInDays(baby.birthDate, System.currentTimeMillis()))
                if (window != null) notify(context, babyId, baby.name, window)

                // Arm the next window (rescheduleAll also covers siblings after a missed boot).
                GrowthSpurtReminders.rescheduleAll(context, app.repository)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, babyId: Long, babyName: String?, window: GrowthSpurtWindow) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = if (babyName.isNullOrBlank()) {
            context.getString(R.string.growth_spurt_notif_title)
        } else {
            context.getString(R.string.growth_spurt_notif_title_baby, babyName)
        }
        val text = context.getString(
            R.string.growth_spurt_notif_text,
            growthSpurtAgeLabel(context, window),
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, GrowthSpurtReminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(
            GrowthSpurtReminders.notificationId(babyId),
            notification,
        )
    }
}
