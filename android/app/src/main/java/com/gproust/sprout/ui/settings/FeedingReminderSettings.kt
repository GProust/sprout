package com.gproust.sprout.ui.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Device-local preferences for the feeding reminder: whether it's on, and the
 * maximum time allowed between feeds before we nudge. A single setting applies to
 * every tracked baby (each is reminded off its own last feed).
 *
 * Stored in SharedPreferences (no sync, no DB migration) alongside [AppLocale].
 */
object FeedingReminderSettings {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "feeding_reminders_enabled"
    private const val KEY_INTERVAL = "feeding_max_interval_minutes"

    /** Default max gap between feeds: 3 hours. */
    const val DEFAULT_INTERVAL_MINUTES = 180

    /** Selectable limits offered in Settings, in minutes (1h30 … 5h). */
    val INTERVAL_CHOICES = listOf(90, 120, 150, 180, 210, 240, 300)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MINUTES)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun setIntervalMinutes(context: Context, minutes: Int) =
        prefs(context).edit { putInt(KEY_INTERVAL, minutes) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
