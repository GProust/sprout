package com.gproust.sprout.ui.feeding

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.Converters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live breastfeeding sessions, at most one per baby (BDR-23): held in
 * SharedPreferences so they survive process death and can be read by the
 * home-screen widget, and mirrored in a [StateFlow] so that everything showing
 * one is looking at the *same* session.
 *
 * The flow is not a convenience. A timer is reachable from several places —
 * the feeding screen's bar, the dashboard's live card, a widget tap landing on
 * Feeding while the timer is already open — and each of those can end up with a
 * [FeedingViewModel] of its own. While each of them kept a private copy of the
 * session, stopping the feed in one left the others still holding it, and every
 * one of them would happily save it again: one breastfeed, logged three times.
 *
 * For the same reason, ending a session goes through [consume] rather than a
 * plain read-then-clear: whoever is handed the session is the one that saves
 * it, and there is nothing left for a second screen (or a second tap) to save.
 * What each act does to the list is decided in `NursingSessions.kt`; this only
 * keeps it.
 */
object NursingSessionStore {
    private const val PREFS = "nursing_session"
    private const val KEY_SESSIONS = "sessions"

    // The one session a version before BDR-23 kept, as flat keys. Read once,
    // and replaced by [KEY_SESSIONS] on the next write.
    private const val KEY_SESSION_START = "sessionStart"
    private const val KEY_SIDE = "currentSide"
    private const val KEY_SEGMENT_START = "segmentStart"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_PAUSED_AT = "pausedAt"
    private const val KEY_BABY_UID = "babyUid"

    private val state = MutableStateFlow<List<NursingSession>>(emptyList())
    private var seeded = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every running feed, oldest first, shared by every screen that shows or ends one. */
    @Synchronized
    fun sessions(context: Context): StateFlow<List<NursingSession>> {
        seed(context)
        return state.asStateFlow()
    }

    /** Every running feed, oldest first. */
    @Synchronized
    fun loadAll(context: Context): List<NursingSession> {
        seed(context)
        return state.value
    }

    /** The feed running for [babyUid], or null. */
    @Synchronized
    fun load(context: Context, babyUid: String?): NursingSession? {
        seed(context)
        return state.value.runningFor(babyUid)
    }

    /** Puts [session] in place of whatever its baby had running. */
    @Synchronized
    fun save(context: Context, session: NursingSession) {
        seed(context)
        write(context, replacing(state.value, session))
    }

    /**
     * Changes [babyUid]'s running feed, and only that one. Returns it as it is
     * afterwards, or null when that baby had none to change.
     */
    @Synchronized
    fun update(
        context: Context,
        babyUid: String?,
        change: (NursingSession) -> NursingSession,
    ): NursingSession? {
        seed(context)
        val next = updating(state.value, babyUid, change)
        if (next != state.value) write(context, next)
        return next.runningFor(babyUid)
    }

    /** Ends every running feed without saving any. For tests and nothing else. */
    @Synchronized
    fun clear(context: Context) = write(context, emptyList())

    /**
     * Starts [session] unless its baby already has one running, so two entry
     * points racing to open the timer cannot end up timing one baby twice.
     * Another baby's feed is no obstacle. Returns whichever session is running
     * for that baby afterwards.
     */
    @Synchronized
    fun startIfIdle(context: Context, session: NursingSession): NursingSession {
        seed(context)
        val start = startingIfIdle(state.value, session)
        if (start.sessions != state.value) write(context, start.sessions)
        return start.running
    }

    /**
     * Takes [babyUid]'s running session away and hands it to the caller — the
     * only way a session ends. Every other baby's feed carries on. Returns null
     * when there was nothing left to take, which is exactly what a second screen
     * still showing the finished timer sees.
     */
    @Synchronized
    fun consume(context: Context, babyUid: String?): NursingSession? {
        seed(context)
        val take = taking(state.value, babyUid)
        if (take.taken != null) write(context, take.sessions)
        return take.taken
    }

    /**
     * Gives a feed left running by a version that did not record its baby to
     * [activeBabyUid], the baby it would have been saved to (BDR-23). Called once
     * the app is up; does nothing when there is no such feed.
     */
    @Synchronized
    fun adoptUnnamed(context: Context, activeBabyUid: String?) {
        seed(context)
        val next = adoptingUnnamed(state.value, activeBabyUid)
        if (next != state.value) write(context, next)
    }

    /**
     * Drops the in-memory copy so the next read comes back from storage — what
     * the app coming up after being killed mid-feed does. Nothing in the app
     * has any use for it; a test of what actually survives does.
     */
    @VisibleForTesting
    @Synchronized
    fun forgetInMemory() {
        seeded = false
        state.value = emptyList()
    }

    /** Reads the stored sessions once, the first time anyone asks for them. */
    private fun seed(context: Context) {
        if (seeded) return
        state.value = read(context)
        seeded = true
    }

    private fun write(context: Context, sessions: List<NursingSession>) {
        val editor = prefs(context).edit().clear()
        if (sessions.isNotEmpty()) {
            editor.putString(KEY_SESSIONS, JSONArray(sessions.map(::toJson)).toString())
        }
        editor.apply()
        seeded = true
        state.value = sessions
    }

    private fun read(context: Context): List<NursingSession> {
        val p = prefs(context)
        p.getString(KEY_SESSIONS, null)?.let { stored ->
            // A file that will not parse is a feed nobody can be shown; dropping
            // it is the one answer that does not leave the timer stuck open.
            return runCatching {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        return listOfNotNull(readSingle(context))
    }

    private fun toJson(session: NursingSession): JSONObject = JSONObject()
        .put(KEY_SESSION_START, session.sessionStart)
        .put(KEY_SIDE, session.currentSide.name)
        .put(KEY_SEGMENT_START, session.segmentStart)
        .put(KEY_SEGMENTS, Converters().nursingSegmentsToString(session.segments))
        // Zero is "not on a break", the way the flat keys always read it.
        .put(KEY_PAUSED_AT, session.pausedAt ?: 0L)
        .apply { session.babyUid?.let { put(KEY_BABY_UID, it) } }

    private fun fromJson(json: JSONObject): NursingSession? {
        val start = json.optLong(KEY_SESSION_START, -1L)
        val side = json.optString(KEY_SIDE).let { name ->
            runCatching { BreastSide.valueOf(name) }.getOrNull()
        }
        if (start <= 0L || side == null) return null
        return NursingSession(
            sessionStart = start,
            currentSide = side,
            segmentStart = json.optLong(KEY_SEGMENT_START, start),
            segments = Converters().stringToNursingSegments(json.optString(KEY_SEGMENTS, "")),
            pausedAt = json.optLong(KEY_PAUSED_AT, 0L).takeIf { it > 0L },
            babyUid = if (json.has(KEY_BABY_UID)) json.getString(KEY_BABY_UID) else null,
        )
    }

    /** The one session a version before BDR-23 kept, if it left one running. */
    private fun readSingle(context: Context): NursingSession? {
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
            // Absent (0) is "not on a break": a session stored before breaks
            // existed reads back as one that is nursing, which it was.
            pausedAt = p.getLong(KEY_PAUSED_AT, 0L).takeIf { it > 0L },
            // Absent for a session stored before the baby was recorded: it is
            // given to the selected baby when the app comes up (adoptUnnamed).
            babyUid = p.getString(KEY_BABY_UID, null),
        )
    }
}
