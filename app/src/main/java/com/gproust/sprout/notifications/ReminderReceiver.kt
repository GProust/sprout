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
import com.gproust.sprout.data.local.TreatmentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when a treatment reminder is due (or after a reboot). Posts a
 * notification and schedules the next occurrence. Reboot wipes alarms, so a
 * BOOT_COMPLETED reschedules everything.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SproutApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TreatmentReminders.ensureChannel(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    TreatmentReminders.scheduleAll(context, app.repository.treatmentsWithReminders())
                    return@launch
                }

                val id = intent.getLongExtra(TreatmentReminders.EXTRA_TREATMENT_ID, -1L)
                val slot = intent.getIntExtra(TreatmentReminders.EXTRA_SLOT, -1)
                if (id < 0 || slot < 0) return@launch

                val treatment = app.repository.getTreatment(id) ?: return@launch
                if (!treatment.active || !treatment.remindersEnabled) return@launch
                if (slot >= treatment.timesOfDay.size) return@launch

                val babyName = app.repository.babyName(treatment.babyId)
                notify(context, treatment, babyName)

                // Set up the following occurrences (this one-shot alarm is now spent).
                TreatmentReminders.schedule(context, treatment)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, treatment: TreatmentEntity, babyName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = if (treatment.dose.isNullOrBlank()) {
            treatment.name
        } else {
            context.getString(R.string.treatment_title_dose, treatment.name, treatment.dose)
        }
        val text = if (babyName.isNullOrBlank()) {
            context.getString(R.string.treatment_notif_text)
        } else {
            context.getString(R.string.treatment_notif_text_baby, babyName)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, TreatmentReminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(treatment.id.toInt(), notification)
    }
}
