package com.gproust.sprout.ui.feeding

import android.content.Context
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.Converters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one live breastfeeding session: held in SharedPreferences so it survives
 * process death and can be read by the home-screen widget, and mirrored in a
 * [StateFlow] so that everything showing it is looking at the *same* session.
 *
 * The flow is not a convenience. The timer is reachable from several places —
 * the feeding screen's bar, the dashboard's live card, a widget tap landing on
 * Feeding while the timer is already open — and each of those can end up with a
 * [FeedingViewModel] of its own. While each of them kept a private copy of the
 * session, stopping the feed in one left the others still holding it, and every
 * one of them would happily save it again: one breastfeed, logged three times.
 *
 * For the same reason, ending a session goes through [consume] rather than a
 * plain read-then-clear: whoever is handed the session is the one that saves
 * it, and there is nothing left for a second screen (or a second tap) to save.
 */
object NursingSessionStore {
    private const val PREFS = "nursing_session"
    private const val KEY_SESSION_START = "sessionStart"
    private const val KEY_SIDE = "currentSide"
    private const val KEY_SEGMENT_START = "segmentStart"
    private const val KEY_SEGMENTS = "segments"

    private val state = MutableStateFlow<NursingSession?>(null)
    private var seeded = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The live session, shared by every screen that shows or ends it. */
    @Synchronized
    fun sessions(context: Context): StateFlow<NursingSession?> {
        seed(context)
        return state.asStateFlow()
    }

    @Synchronized
    fun save(context: Context, session: NursingSession) = write(context, session)

    @Synchronized
    fun load(context: Context): NursingSession? {
        seed(context)
        return state.value
    }

    @Synchronized
    fun clear(context: Context) = write(context, null)

    /**
     * Starts [session] unless one is already running, so two entry points
     * racing to open the timer cannot end up timing two feeds at once. Returns
     * whichever session is running afterwards.
     */
    @Synchronized
    fun startIfIdle(context: Context, session: NursingSession): NursingSession {
        seed(context)
        state.value?.let { return it }
        write(context, session)
        return session
    }

    /**
     * Takes the running session away and hands it to the caller — the only way
     * a session ends. Returns null when there was nothing left to take, which
     * is exactly what a second screen still showing the finished timer sees.
     */
    @Synchronized
    fun consume(context: Context): NursingSession? {
        seed(context)
        return state.value?.also { write(context, null) }
    }

    /** Reads the stored session once, the first time anyone asks for it. */
    private fun seed(context: Context) {
        if (seeded) return
        state.value = read(context)
        seeded = true
    }

    private fun write(context: Context, session: NursingSession?) {
        if (session == null) {
            prefs(context).edit().clear().apply()
        } else {
            prefs(context).edit()
                .putLong(KEY_SESSION_START, session.sessionStart)
                .putString(KEY_SIDE, session.currentSide.name)
                .putLong(KEY_SEGMENT_START, session.segmentStart)
                .putString(KEY_SEGMENTS, Converters().nursingSegmentsToString(session.segments))
                .apply()
        }
        seeded = true
        state.value = session
    }

    private fun read(context: Context): NursingSession? {
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
}
