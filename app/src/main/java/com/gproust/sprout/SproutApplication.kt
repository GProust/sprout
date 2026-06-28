package com.gproust.sprout

import android.app.Application
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.notifications.TreatmentReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the database and repository for the
 * lifetime of the process (a lightweight manual dependency container).
 */
class SproutApplication : Application() {

    val repository: SproutRepository by lazy {
        SproutRepository(SproutDatabase.getInstance(this))
    }

    override fun onCreate() {
        super.onCreate()
        TreatmentReminders.ensureChannel(this)
        FeedingReminders.ensureChannel(this)
        // Alarms don't survive process death/reboot; re-arm them on launch.
        CoroutineScope(Dispatchers.IO).launch {
            TreatmentReminders.scheduleAll(this@SproutApplication, repository.treatmentsWithReminders())
            FeedingReminders.rescheduleAll(this@SproutApplication, repository)
        }
    }
}
