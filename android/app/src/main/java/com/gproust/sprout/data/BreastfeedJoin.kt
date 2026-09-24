package com.gproust.sprout.data

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity

/**
 * Two breastfeeds saved apart that were really one feed — *Stop & save* after
 * the first side, the baby winded, *Start right* five minutes later — joined
 * back into it (BDR-19).
 *
 * **A join is a pause that happened after the fact.** The minutes between the
 * two feeds become a break: the gap between two stretches, which is all a break
 * ever was (BDR-17). So the joined feed needs no field of its own, and every
 * screen that already reads a break reads this one.
 *
 * Arithmetic only, so it can be unit-tested without a device. iOS computes the
 * same answer in `SproutData/BreastfeedJoin.swift`, and the two share their
 * test cases: a household with one phone of each has to offer the same join on
 * the same two feeds.
 */

/**
 * How far apart two breastfeeds can be and still be offered as one.
 *
 * Where the offer appears, not how long a break may be — a paused session can
 * still run all night. Past half an hour, two feeds side by side are far more
 * likely to be two feeds (cluster feeding is often an hour apart, start to
 * start) than a burp saved in the middle, and a *Join* on nearly every card
 * would be the opposite of quiet.
 */
const val BREASTFEED_JOIN_MAX_GAP_MS: Long = 30L * 60 * 1000

/**
 * The break joining [earlier] to [later] would leave, or null when the two
 * cannot be joined.
 *
 * Both have to be breastfeeds of the same baby, both timed stretch by stretch —
 * a feed with only a start, or only per-side totals from before stretches were
 * recorded, has no end to line up against — and [later] has to begin once
 * [earlier] has ended, at most [BREASTFEED_JOIN_MAX_GAP_MS] afterwards. Zero is
 * a join too: a switch of sides saved as two feeds.
 */
fun breastfeedJoinGap(earlier: FeedingEntity, later: FeedingEntity): Long? {
    if (earlier.type != FeedType.BREAST || later.type != FeedType.BREAST) return null
    if (earlier.babyId != later.babyId || earlier.uid == later.uid) return null
    val ended = earlier.segments.lastOrNull()?.endTime ?: return null
    val resumed = later.segments.firstOrNull()?.startTime ?: return null
    if (later.startTime < ended) return null
    val gap = resumed - ended
    return gap.takeIf { it in 0L..BREASTFEED_JOIN_MAX_GAP_MS }
}

/**
 * Which feeds on a history can be joined to the one before them: each later
 * feed's uid, mapped to the earlier feed it would join.
 *
 * Only neighbours: if anything else was logged between two breastfeeds — a
 * bottle, solids — they were not one feed, and joining them would wrap the
 * joined feed around it.
 */
fun breastfeedJoinOffers(feeds: List<FeedingEntity>): Map<String, FeedingEntity> =
    feeds.sortedBy { it.startTime }
        .zipWithNext()
        .filter { (earlier, later) -> breastfeedJoinGap(earlier, later) != null }
        .associate { (earlier, later) -> later.uid to earlier }

/**
 * [earlier] and [later] as the one feed they were.
 *
 * The earlier feed is the one that stays: it keeps its row, its uid and its
 * start, gains the later feed's stretches, and ends when the last of them did.
 * The per-side times are re-totalled from the stretches, so the time at the
 * breast is exactly what the two feeds said between them and the gap is not in
 * it. Notes from both are kept.
 *
 * Callers check [breastfeedJoinGap] first; this does not.
 */
fun joinedBreastfeed(earlier: FeedingEntity, later: FeedingEntity): FeedingEntity {
    val segments = earlier.segments + later.segments
    val left = segments.filter { it.side == BreastSide.LEFT }
        .sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
    val right = segments.filter { it.side == BreastSide.RIGHT }
        .sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
    val notes = listOfNotNull(earlier.notes, later.notes)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
    return earlier.copy(
        side = when {
            left > 0 && right > 0 -> BreastSide.BOTH
            right > 0 -> BreastSide.RIGHT
            left > 0 -> BreastSide.LEFT
            else -> earlier.side
        },
        endTime = segments.last().endTime,
        leftDurationMs = left.takeIf { it > 0 },
        rightDurationMs = right.takeIf { it > 0 },
        segments = segments,
        notes = notes.ifEmpty { null },
    )
}
