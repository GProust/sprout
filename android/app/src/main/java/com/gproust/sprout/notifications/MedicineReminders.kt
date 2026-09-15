package com.gproust.sprout.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.gproust.sprout.R
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.nextMedicineReminder

/**
 * Arming the "you can give it again" alarm for an as-needed medicine (BDR-15).
 *
 * One alarm per medicine, because there is only ever one moment to wait for: the
 * wait is counted from the last dose, so logging a dose *replaces* the alarm
 * rather than adding to it. `setAndAllowWhileIdle` for the same reason
 * [TreatmentReminders] uses it — it fires in Doze, needs no special permission,
 * and a few minutes' drift on a six-hour wait is not worth an exact alarm and
 * the permission that goes with it.
 *
 * Nothing here decides *what* to say. The receiver re-reads the database when
 * the alarm fires and works the answer out then, so a dose logged on the other
 * phone in the meantime is taken into account.
 */
object MedicineReminders {
    const val CHANNEL_ID = "medicine_reminders"
    const val ACTION_FIRE = "com.gproust.sprout.MEDICINE_REMINDER"

    /** The notification's own buttons, back to this same receiver (BDR-16). */
    const val ACTION_GIVE = "com.gproust.sprout.MEDICINE_GIVE"
    const val ACTION_DISMISS = "com.gproust.sprout.MEDICINE_DISMISS"
    const val EXTRA_MEDICINE_ID = "medicine_id"

    /**
     * Keeps medicine request codes clear of [TreatmentReminders], which spaces
     * its own by 100 from zero. Both are `PendingIntent`s on the same app; two
     * that collided would cancel each other.
     *
     * The three bases are a million apart for the same reason the first one is
     * clear of the treatments': a medicine's alarm and its two buttons are three
     * `PendingIntent`s for one id, and `FLAG_UPDATE_CURRENT` on a shared request
     * code would quietly rewrite one into another.
     */
    private const val REQUEST_CODE_BASE = 1_000_000
    private const val REQUEST_CODE_GIVE = 2_000_000
    private const val REQUEST_CODE_DISMISS = 3_000_000

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.medicine_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.medicine_channel_desc) },
            )
        }
    }

    /**
     * Cancels then re-arms [medicine]'s alarm from [doses].
     *
     * Called after every write that can move the answer: a dose given, a dose
     * corrected or deleted, the intervals edited, the switch turned off.
     */
    fun schedule(
        context: Context,
        medicine: MedicineEntity,
        doses: List<MedicineDoseEntity>,
        now: Long = System.currentTimeMillis(),
    ) {
        cancel(context, medicine)
        val trigger = nextMedicineReminder(medicine, doses, now) ?: return
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(context, medicine.id))
    }

    fun cancel(context: Context, medicine: MedicineEntity) {
        val alarm = context.getSystemService<AlarmManager>() ?: return
        alarm.cancel(alarmIntent(context, medicine.id))
    }

    /**
     * The intent behind one of the notification's buttons.
     *
     * A broadcast to this app's own receiver rather than an activity: logging a
     * dose from the notification must not require unlocking the phone and
     * waiting for a screen to open, which is most of what makes a parent leave
     * it for later and then lose it.
     */
    fun buttonIntent(context: Context, action: String, medicineId: Long): PendingIntent {
        val intent = Intent(context, MedicineReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MEDICINE_ID, medicineId)
        }
        val base = if (action == ACTION_GIVE) REQUEST_CODE_GIVE else REQUEST_CODE_DISMISS
        return PendingIntent.getBroadcast(
            context,
            (base + medicineId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun alarmIntent(context: Context, medicineId: Long): PendingIntent {
        val intent = Intent(context, MedicineReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_MEDICINE_ID, medicineId)
        }
        return PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_BASE + medicineId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The notification id an alarm posts under; distinct from the treatments'. */
    fun notificationId(medicineId: Long): Int = (REQUEST_CODE_BASE + medicineId).toInt()
}
