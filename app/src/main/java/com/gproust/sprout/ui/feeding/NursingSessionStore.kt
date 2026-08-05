package com.gproust.sprout.ui.feeding

import android.content.Context
import android.content.SharedPreferences
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.Converters
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Persists the live breastfeeding session (if any) to SharedPreferences, so
 * the home-screen widget can show it and the app can restore it after process
 * death. Written by [FeedingViewModel] on every start/switch, cleared when the
 * session is saved or discarded.
 */
object NursingSessionStore {
    private const val PREFS = "nursing_session"
    private const val KEY_SESSION_START = "sessionStart"
    private const val KEY_SIDE = "currentSide"
    private const val KEY_SEGMENT_START = "segmentStart"
    private const val KEY_SEGMENTS = "segments"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, session: NursingSession) {
        prefs(context).edit()
            .putLong(KEY_SESSION_START, session.sessionStart)
            .putString(KEY_SIDE, session.currentSide.name)
            .putLong(KEY_SEGMENT_START, session.segmentStart)
            .putString(KEY_SEGMENTS, Converters().nursingSegmentsToString(session.segments))
            .apply()
    }

    fun load(context: Context): NursingSession? {
        val p = prefs(context)
        val start = p.getLong(KEY_SESSION_START, -1L)
        val side = p.getString(KEY_SIDE, null)
            ?.let { runCatching { BreastSide.valueOf(it) }.getOrNull() }
        if (start <= 0L || side == null) return null
        return NursingSession(
            sessionStart = start,
            currentSide = side,
            segmentStart = p.getLong(KEY_SEGMENT_START, start),
            segments = Converters().stringToNursingSegments(p.getString(KEY_SEGMENTS, null)),
        )
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /**
     * The live session as a stream. The widget observes this inside its
     * composition, because update()/updateAll() don't restart provideGlance
     * while it is still running — so a session that starts, switches sides or
     * ends would otherwise never reach an already-composed widget.
     */
    fun observe(context: Context): Flow<NursingSession?> = callbackFlow {
        val prefs = prefs(context)
        // Every listener callback re-reads the whole session rather than
        // tracking single keys: a session is written key by key, and half of
        // one is worse than a slightly late whole one.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(load(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(load(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
