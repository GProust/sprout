package com.gproust.sprout.ui.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Device-local preference for showing a running breastfeed in the
 * notifications, with its buttons (BDR-24). Off by default, like the feeding
 * reminders and the growth-spurt alerts: turning it on is where the permission
 * is asked for, and a feed starting is never the moment to ask.
 *
 * Stored in SharedPreferences (no sync, no DB migration) alongside
 * [FeedingReminderSettings] and [GrowthSpurtSettings], so a new phone keeps the
 * choice (ADR-0011) and the other parent's phone is told nothing.
 */
object NursingNotificationSettings {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "nursing_notification_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
