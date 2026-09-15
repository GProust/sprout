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
    const val EXTRA_MEDICINE_ID = "medicine_id"

    /**
     * Keeps medicine request codes clear of [TreatmentReminders], which spaces
     * its own by 100 from zero. Both are `PendingIntent`s on the same app; two
     * that collided would cancel each other.
     */
    private const val REQUEST_CODE_BASE = 1_000_000

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
