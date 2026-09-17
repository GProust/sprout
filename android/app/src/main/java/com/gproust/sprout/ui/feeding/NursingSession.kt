package com.gproust.sprout.ui.feeding

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.NursingSegment

/**
 * A breastfeeding session being timed live but not yet saved. [segments] holds
 * the *completed* back-and-forth stretches; the breast currently nursing runs
 * from [segmentStart] until the next switch, break or stop, ticking off the
 * clock.
 *
 * A session on a break — winding for a burp between the sides, a nappy in the
 * middle — has already banked the stretch it was on and is nursing nothing:
 * [pausedAt] is when the break started and [segmentStart] means nothing until
 * it resumes. That is why every clock is read through [nursedMs] and never from
 * `now - segmentStart`: a reader that forgets the break counts the burping as
 * time at the breast, which is the one number this screen exists to get right.
 */
data class NursingSession(
    val sessionStart: Long,
    val currentSide: BreastSide,
    val segmentStart: Long,
    val segments: List<NursingSegment> = emptyList(),
    /** When the current break began; null while a breast is actually nursing. */
    val pausedAt: Long? = null,
) {
    val isPaused: Boolean get() = pausedAt != null

    /**
     * Every stretch so far, the one in progress included (it ends at [now]).
     * On a break there is no stretch in progress — it was banked when the break
     * began — so this is exactly what has been nursed.
     */
    fun segmentsAt(now: Long): List<NursingSegment> =
        if (isPaused) segments else segments + NursingSegment(currentSide, segmentStart, now)

    /** Time actually spent at the breast so far, breaks excluded. */
    fun nursedMs(now: Long): Long =
        segmentsAt(now).sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }

    /** Time spent on [side] so far, breaks excluded. */
    fun nursedMs(side: BreastSide, now: Long): Long = segmentsAt(now)
        .filter { it.side == side }
        .sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }

    /** How long the current break has been running; zero while nursing. */
    fun pausedMs(now: Long): Long = pausedAt?.let { (now - it).coerceAtLeast(0L) } ?: 0L

    /**
     * Bank the breast being nursed and start a break. Pausing an already paused
     * session changes nothing — a second tap must not restart the break, or the
     * minutes spent burping would quietly reset.
     */
    fun paused(at: Long): NursingSession =
        if (isPaused) this else copy(segments = segmentsAt(at), pausedAt = at)

    /** Come back from a break, on [side] — the same one or the other. */
    fun resumed(side: BreastSide, at: Long): NursingSession =
        copy(currentSide = side, segmentStart = at, pausedAt = null)

    /**
     * Bank the current breast and carry straight on with the other one: a
     * break that begins and ends at the same instant, which is what switching
     * sides has always been.
     */
    fun switched(at: Long): NursingSession = paused(at).resumed(currentSide.other(), at)
}

/** The other breast. [BreastSide.BOTH] is a property of a saved feed, never of a live one. */
internal fun BreastSide.other(): BreastSide =
    if (this == BreastSide.LEFT) BreastSide.RIGHT else BreastSide.LEFT

/**
 * The breaks inside a finished breastfeed: the time between one stretch ending
 * and the next beginning. Nothing records a break of its own — it is the gap
 * the segments leave, which is why a feed logged before breaks existed reads
 * back as a feed with none.
 */
fun breastfeedPausedMillis(segments: List<NursingSegment>): Long = segments
    .zipWithNext { a, b -> (b.startTime - a.endTime).coerceAtLeast(0L) }
    .sum()
