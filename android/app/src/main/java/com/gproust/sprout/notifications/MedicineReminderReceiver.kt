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
import com.gproust.sprout.data.MEDICINE_DAY_MS
import com.gproust.sprout.data.MedicineLevel
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.medicineReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when an as-needed medicine's wait is over — or after a reboot, which
 * wipes every alarm and so re-arms all of them.
 *
 * **The decision is taken here, not when the alarm was set**, which is the whole
 * point of Android's receiver (ADR-0019 explains why iOS cannot do the same).
 * Between arming and firing, a dose may have been given on the other phone and
 * merged in, the intervals may have been edited, or the medicine may have been
 * removed. So the database is read again and the state recomputed; more often
 * than not the right answer is to say nothing and re-arm for the new moment.
 */
class MedicineReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SproutApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MedicineReminders.ensureChannel(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    rearmAll(context, app.repository)
                    return@launch
                }

                val id = intent.getLongExtra(MedicineReminders.EXTRA_MEDICINE_ID, -1L)
                if (id < 0) return@launch
                val medicine = app.repository.getMedicine(id) ?: return@launch
                if (!medicine.active || !medicine.remindWhenDue) return@launch

                val doses = app.repository.recentDosesOf(medicine.uid, MEDICINE_DAY_MS)
                val readiness = medicineReadiness(medicine, doses, System.currentTimeMillis())

                // Still too soon: something moved after the alarm was armed.
                // Re-arm for the new moment and stay quiet — a notification
                // saying "you can give it" while the screen would say otherwise
                // is worse than no notification at all.
                if (readiness.level == MedicineLevel.TOO_SOON) {
                    MedicineReminders.schedule(context, medicine, doses)
                    return@launch
                }

                val babyName = app.repository.babyName(medicine.babyId)
                notify(context, medicine, babyName)
            } finally {
                pending.finish()
            }
        }
    }

    /** Every medicine that wants a reminder, re-armed from its own last dose. */
    private suspend fun rearmAll(context: Context, repository: SproutRepository) {
        for (medicine in repository.medicinesWithReminders()) {
            MedicineReminders.schedule(
                context,
                medicine,
                repository.recentDosesOf(medicine.uid, MEDICINE_DAY_MS),
            )
        }
    }

    private fun notify(context: Context, medicine: MedicineEntity, babyName: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val title = if (medicine.dose.isNullOrBlank()) {
            medicine.name
        } else {
            context.getString(R.string.treatment_title_dose, medicine.name, medicine.dose)
        }
        val text = if (babyName.isNullOrBlank()) {
            context.getString(R.string.medicine_notif_text)
        } else {
            context.getString(R.string.medicine_notif_text_baby, babyName)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, MedicineReminders.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context)
            .notify(MedicineReminders.notificationId(medicine.id), notification)
    }
}
