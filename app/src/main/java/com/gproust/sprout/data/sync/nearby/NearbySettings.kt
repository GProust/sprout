package com.gproust.sprout.data.sync.nearby

import android.content.Context
import androidx.core.content.edit

/**
 * Whether this phone looks for the household when the app opens (ADR-0010).
 *
 * **Off by default.** Turning it on is what triggers the permission request, so
 * a parent who never wants it is never asked, and the manual exchange keeps
 * working untouched either way.
 */
object NearbySettings {

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "sync_nearby_enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
