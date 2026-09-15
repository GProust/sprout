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

                // "Dismiss": take the notification away and change nothing. The
                // wait really is over, so there is nothing to snooze and nothing
                // to record — the parent has seen it, which is all the button
                // claims (BDR-16).
                if (intent.action == MedicineReminders.ACTION_DISMISS) {
                    NotificationManagerCompat.from(context)
                        .cancel(MedicineReminders.notificationId(id))
                    return@launch
                }

                val medicine = app.repository.getMedicine(id)
                    ?.takeIf { it.deletedAt == null }
                    ?: return@launch

                // "Give a dose": the same write the screen makes, without the
                // screen. A dose given at 3 a.m. and logged in the morning is
                // logged at the wrong time; one logged from the notification is
                // logged when it happened.
                if (intent.action == MedicineReminders.ACTION_GIVE) {
                    app.repository.giveMedicineDose(medicine, System.currentTimeMillis())
                    NotificationManagerCompat.from(context)
                        .cancel(MedicineReminders.notificationId(id))
                    MedicineReminders.schedule(
                        context,
                        medicine,
                        app.repository.recentDosesOf(medicine.uid, MEDICINE_DAY_MS),
                    )
                    return@launch
                }

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
            // No icons: Android has not drawn action icons since Nougat, and
            // `NotificationCompat` takes 0 for exactly this case.
            .addAction(
                0,
                context.getString(R.string.medicine_give),
                MedicineReminders.buttonIntent(
                    context,
                    MedicineReminders.ACTION_GIVE,
                    medicine.id,
                ),
            )
            .addAction(
                0,
                context.getString(R.string.medicine_dismiss),
                MedicineReminders.buttonIntent(
                    context,
                    MedicineReminders.ACTION_DISMISS,
                    medicine.id,
                ),
            )
            .build()

        NotificationManagerCompat.from(context)
            .notify(MedicineReminders.notificationId(medicine.id), notification)
    }
}
