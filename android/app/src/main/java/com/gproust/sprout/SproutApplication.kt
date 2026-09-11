package com.gproust.sprout

import android.app.Application
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.sync.DeviceIdentity
import com.gproust.sprout.data.sync.HouseholdDevices
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SyncEngine
import com.gproust.sprout.notifications.FeedingReminders
import com.gproust.sprout.notifications.GrowthSpurtReminders
import com.gproust.sprout.notifications.TreatmentReminders
import com.gproust.sprout.widget.updateSproutWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Application entry point. Owns the database and repository for the
 * lifetime of the process (a lightweight manual dependency container).
 */
class SproutApplication : Application() {

    val repository: SproutRepository by lazy {
        SproutRepository(SproutDatabase.getInstance(this)) {
            updateSproutWidget(this)
        }
    }

    /** Builds and merges replicas for partner sync (ADR-0007). */
    val syncEngine: SyncEngine by lazy { SyncEngine(SproutDatabase.getInstance(this)) }

    /** Who this phone is paired with, and on what terms (ADR-0008). */
    val pairingStore: PairingStore by lazy { PairingStore(this) }

    /** The other phones in the household this one has heard from (ADR-0009). */
    val householdDevices: HouseholdDevices by lazy { HouseholdDevices(this) }

    override fun onCreate() {
        super.onCreate()
        // Give this install its own identity before anything writes data, so a
        // later pairing has something stable to attach to (ADR-0007).
        DeviceIdentity.id(this)
        TreatmentReminders.ensureChannel(this)
        FeedingReminders.ensureChannel(this)
        GrowthSpurtReminders.ensureChannel(this)
        // Alarms don't survive process death/reboot; re-arm them on launch.
        CoroutineScope(Dispatchers.IO).launch {
            TreatmentReminders.scheduleAll(this@SproutApplication, repository.treatmentsWithReminders())
            FeedingReminders.rescheduleAll(this@SproutApplication, repository)
            GrowthSpurtReminders.rescheduleAll(this@SproutApplication, repository)
            // Deleted entries are kept a while so the deletion can reach the
            // partner's phone (ADR-0007); this is where they finally go.
            repository.compactTombstones()
        }
    }
}
