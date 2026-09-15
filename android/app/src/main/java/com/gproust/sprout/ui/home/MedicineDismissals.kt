package com.gproust.sprout.ui.home

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The medicines a parent has put away from the dashboard, until the next dose
 * (BDR-16).
 *
 * **A dismissal names the dose it was made against**, not just the medicine, so
 * it expires by itself: give another dose and the medicine's last dose is no
 * longer the one that was dismissed, and the line comes back. Nothing has to
 * clear it, which means nothing can forget to.
 *
 * Device-local, in the ordinary settings rather than `device.xml`: "I have seen
 * this" is a fact about a person looking at a screen, not about this handset's
 * identity ([ADR-0011][1]), and it is not synced — the other phone's parent has
 * not seen anything and should still be told.
 *
 * [1]: docs/adr/0011-what-survives-a-new-phone.md
 */
object MedicineDismissals {
    private const val PREFS = "settings"
    private const val KEY = "medicine_dismissed"

    /**
     * `uid=millis`, split on the *last* separator.
     *
     * A uid is a UUID on both platforms, but one can also arrive from a merge
     * written by something else, and the timestamp is the part whose shape is
     * known. Splitting from the right means a uid containing an `=` costs that
     * one entry rather than the parse.
     */
    private const val SEPARATOR = '='

    private val flow = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var loaded = false

    /** What is currently put away. Reads the store once, then stays live. */
    @Synchronized
    fun dismissals(context: Context): StateFlow<Map<String, Long>> {
        if (!loaded) {
            flow.value = read(context)
            loaded = true
        }
        return flow.asStateFlow()
    }

    /** Puts [uid] away until it has been given at something other than [doseAt]. */
    @Synchronized
    fun dismiss(context: Context, uid: String, doseAt: Long) {
        val updated = read(context) + (uid to doseAt)
        write(context, updated)
        flow.value = updated
        loaded = true
    }

    /** Forgets everything — the screenshot run starts from the same state each time. */
    @Synchronized
    fun clear(context: Context) {
        write(context, emptyMap())
        flow.value = emptyMap()
        loaded = true
    }

    private fun read(context: Context): Map<String, Long> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val at = entry.substringAfterLast(SEPARATOR).toLongOrNull() ?: return@mapNotNull null
                val uid = entry.substringBeforeLast(SEPARATOR)
                if (uid.isEmpty()) null else uid to at
            }
            .toMap()

    private fun write(context: Context, value: Map<String, Long>) = prefs(context).edit {
        putStringSet(KEY, value.map { (uid, at) -> "$uid$SEPARATOR$at" }.toSet())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
