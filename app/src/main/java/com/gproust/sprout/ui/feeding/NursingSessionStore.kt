package com.gproust.sprout.ui.feeding

import android.content.Context
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.Converters

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
}
