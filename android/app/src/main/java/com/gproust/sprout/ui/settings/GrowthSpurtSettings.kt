package com.gproust.sprout.ui.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Device-local preference for growth spurt alerts: an optional heads-up
 * notification when a baby enters a typical growth spurt period. Off by
 * default — the home screen shows the period either way; the notification
 * is opt-in, like feeding reminders.
 *
 * Stored in SharedPreferences (no sync, no DB migration) alongside
 * [FeedingReminderSettings] and [AppLocale].
 */
object GrowthSpurtSettings {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "growth_spurt_alerts_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
