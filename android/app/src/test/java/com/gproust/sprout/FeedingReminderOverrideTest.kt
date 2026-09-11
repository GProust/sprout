package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.notifications.effectiveFeedingReminder
import com.gproust.sprout.ui.settings.FeedingReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The per-baby override resolves each field as `baby override ?? device-global default`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class FeedingReminderOverrideTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun baby(enabled: Boolean? = null, interval: Int? = null) =
        BabyEntity(
            id = 1L,
            name = "Léa",
            birthDate = 0L,
            feedingReminderEnabled = enabled,
            feedingReminderIntervalMinutes = interval,
        )

    @Before
    fun setGlobalDefault() {
        // Global default: on, 3h.
        FeedingReminderSettings.setEnabled(context, true)
        FeedingReminderSettings.setIntervalMinutes(context, 180)
    }

    @Test
    fun noOverride_followsGlobal() {
        val eff = effectiveFeedingReminder(context, baby())
        assertEquals(true, eff.enabled)
        assertEquals(180, eff.intervalMinutes)
    }

    @Test
    fun fullOverride_winsOverGlobal() {
        val eff = effectiveFeedingReminder(context, baby(enabled = false, interval = 120))
        assertEquals(false, eff.enabled)
        assertEquals(120, eff.intervalMinutes)
    }

    @Test
    fun intervalOnlyOverride_keepsGlobalEnabled() {
        val eff = effectiveFeedingReminder(context, baby(interval = 90))
        assertEquals(true, eff.enabled)
        assertEquals(90, eff.intervalMinutes)
    }

    @Test
    fun enabledOnlyOverride_keepsGlobalInterval() {
        FeedingReminderSettings.setEnabled(context, false)
        val eff = effectiveFeedingReminder(context, baby(enabled = true))
        assertEquals(true, eff.enabled)
        assertEquals(180, eff.intervalMinutes)
    }
}
