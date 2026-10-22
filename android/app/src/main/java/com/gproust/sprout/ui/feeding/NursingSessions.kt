package com.gproust.sprout.ui.feeding

import com.gproust.sprout.data.local.BreastSide

/*
 * Every breastfeed being timed on this phone, **at most one per baby** (BDR-23).
 *
 * Twins feed together — one at each breast is the everyday case — so the phone
 * can time more than one feed at once. A baby has one mouth, though, so it can
 * never time two for the same child: a second start for a baby whose feed is
 * running hands back the running one, which is the race guard the single
 * session always had, kept per baby.
 *
 * These are the rules as pure functions of the list. [NursingSessionStore]
 * holds the list and applies them; the same cases are tested on iOS
 * (`NursingSessions.swift`), because a household with one phone of each has to
 * agree on what a tap does.
 */

/** The feed running for [babyUid], or null when that baby is not feeding. */
fun List<NursingSession>.runningFor(babyUid: String?): NursingSession? =
    firstOrNull { it.babyUid == babyUid }

/** What a start leaves behind: every running feed, and the one for that baby. */
data class NursingStart(val sessions: List<NursingSession>, val running: NursingSession)

/**
 * Starts [candidate] unless its baby already has a feed running, in which case
 * that one is handed back untouched. Another baby's feed is no obstacle.
 */
fun startingIfIdle(sessions: List<NursingSession>, candidate: NursingSession): NursingStart {
    sessions.runningFor(candidate.babyUid)?.let { return NursingStart(sessions, it) }
    return NursingStart(inOrder(sessions + candidate), candidate)
}

/** [session] in place of whatever its baby had running. */
fun replacing(sessions: List<NursingSession>, session: NursingSession): List<NursingSession> =
    inOrder(sessions.filterNot { it.babyUid == session.babyUid } + session)

/**
 * The running feed for [babyUid], changed by [change]; nothing at all when that
 * baby has none. Pause, resume and switch act on one feed only — pausing one
 * twin's feed is not a reason to stop the other's clock.
 */
fun updating(
    sessions: List<NursingSession>,
    babyUid: String?,
    change: (NursingSession) -> NursingSession,
): List<NursingSession> {
    val current = sessions.runningFor(babyUid) ?: return sessions
    return replacing(sessions, change(current))
}

/** What a stop leaves behind: the other feeds, and the one taken (or null). */
data class NursingTake(val sessions: List<NursingSession>, val taken: NursingSession?)

/**
 * Takes [babyUid]'s feed away, leaving every other baby's running. Null when
 * there was nothing to take — what a second tap on *Save*, or a second screen
 * still showing the finished timer, is handed.
 */
fun taking(sessions: List<NursingSession>, babyUid: String?): NursingTake {
    val taken = sessions.runningFor(babyUid) ?: return NursingTake(sessions, null)
    return NursingTake(sessions.filterNot { it.babyUid == babyUid }, taken)
}

/**
 * Which breast to offer a baby whose feed is about to start.
 *
 * With another baby already at one breast, the free one: that is what tandem
 * feeding is. A default and never a rule — nothing stops two feeds on one side,
 * because the parent model assumes no single breastfeeding parent (BDR-1). With
 * nobody else nursing, or with both breasts taken, it is [fromHistory]: the
 * alternation the baby's own last feed suggests, or null to offer both evenly.
 */
fun sideForNextFeed(
    sessions: List<NursingSession>,
    babyUid: String?,
    fromHistory: BreastSide?,
): BreastSide? {
    val taken = sessions
        .filter { it.babyUid != babyUid && !it.isPaused }
        .map { it.currentSide }
        .toSet()
    return taken.singleOrNull()?.other() ?: fromHistory
}

/**
 * A feed left running by a version that did not record its baby, given one.
 *
 * It belonged to the baby selected at the time, since that is where it would
 * have been saved; so it is read once as the baby selected now, at the moment
 * of the update, and from then on it is that baby's like any other. With no
 * baby to give it, or one that somehow has a feed of its own already, it is left
 * as it is — and saved, in the end, to whoever is selected.
 */
fun adoptingUnnamed(sessions: List<NursingSession>, activeBabyUid: String?): List<NursingSession> {
    if (activeBabyUid == null) return sessions
    val unnamed = sessions.runningFor(null) ?: return sessions
    if (sessions.runningFor(activeBabyUid) != null) return sessions
    return replacing(sessions.filterNot { it.babyUid == null }, unnamed.copy(babyUid = activeBabyUid))
}

/** Oldest first: the order the dashboard lists them in, and a stable one. */
private fun inOrder(sessions: List<NursingSession>): List<NursingSession> =
    sessions.sortedWith(compareBy({ it.sessionStart }, { it.babyUid.orEmpty() }))
