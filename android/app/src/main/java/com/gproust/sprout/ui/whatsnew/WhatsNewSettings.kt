package com.gproust.sprout.ui.whatsnew

import android.content.Context
import androidx.core.content.edit
import com.gproust.sprout.data.ANNOUNCEMENT_IDS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The newest "what's new" announcement this phone has shown (BDR-22).
 *
 * In the ordinary settings, not `device.xml`: "I have read this" is about a
 * person, not about this handset's identity, so a restored phone carries it and
 * does not replay what was already read ([ADR-0011][1]). It is not synced:
 * the other parent has not read anything on this phone.
 *
 * [1]: docs/adr/0011-what-survives-a-new-phone.md
 */
object WhatsNewSettings {
    private const val PREFS = "settings"
    private const val KEY = "whats_new_last_seen"

    private val flow = MutableStateFlow<Int?>(null)
    private var loaded = false

    /** The newest id shown so far, or null if none ever was. Reads the store once, then stays live. */
    @Synchronized
    fun lastSeen(context: Context): StateFlow<Int?> {
        if (!loaded) {
            flow.value = read(context)
            loaded = true
        }
        return flow.asStateFlow()
    }

    /**
     * Every announcement this version carries counts as seen: the card was read
     * or put away, or onboarding has just finished and there is nothing to
     * catch up on.
     */
    @Synchronized
    fun markAllSeen(context: Context) {
        val newest = ANNOUNCEMENT_IDS.maxOrNull() ?: return
        prefs(context).edit { putInt(KEY, newest) }
        flow.value = newest
        loaded = true
    }

    private fun read(context: Context): Int? =
        prefs(context).takeIf { it.contains(KEY) }?.getInt(KEY, 0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
