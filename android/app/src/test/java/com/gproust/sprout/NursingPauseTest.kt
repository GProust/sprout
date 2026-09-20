package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.breastfeedPausedMillis
import com.gproust.sprout.ui.stats.breastfeedMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A feed can stop for a burp and carry on (BDR-17), and the whole feature is
 * one rule: **a break is not time at the breast**. Every clock in the app reads
 * the session through these functions, so this is where that rule is held.
 *
 * The same cases are checked on iOS, because a household with one phone of each
 * has to get the same answer to "how long did they feed for".
 */
class NursingPauseTest {

    private val start = 1_750_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private val session = NursingSession(
        sessionStart = start,
        currentSide = BreastSide.LEFT,
        segmentStart = start,
    )

    @Test
    fun aRunningSessionCountsTheBreastItIsOn() {
        assertFalse(session.isPaused)
        assertEquals(min(8), session.nursedMs(start + min(8)))
        assertEquals(min(8), session.nursedMs(BreastSide.LEFT, start + min(8)))
        assertEquals(0L, session.nursedMs(BreastSide.RIGHT, start + min(8)))
        assertEquals(0L, session.pausedMs(start + min(8)))
    }

    @Test
    fun pausingBanksTheStretchAndStopsTheClock() {
        val paused = session.paused(start + min(8))

        assertTrue(paused.isPaused)
        assertEquals(listOf(NursingSegment(BreastSide.LEFT, start, start + min(8))), paused.segments)
        // Five minutes of burping later, still eight minutes of feeding.
        assertEquals(min(8), paused.nursedMs(start + min(13)))
        assertEquals(min(5), paused.pausedMs(start + min(13)))
    }

    @Test
    fun pausingAgainDoesNotRestartTheBreak() {
        val paused = session.paused(start + min(8))

        val again = paused.paused(start + min(11))

        assertEquals(paused, again)
        assertEquals(min(5), again.pausedMs(start + min(13)))
    }

    @Test
    fun resumingOnTheOtherBreastLeavesTheBreakAsAGap() {
        val resumed = session.paused(start + min(8)).resumed(BreastSide.RIGHT, start + min(13))

        assertFalse(resumed.isPaused)
        assertEquals(BreastSide.RIGHT, resumed.currentSide)
        assertEquals(min(8), resumed.nursedMs(BreastSide.LEFT, start + min(19)))
        assertEquals(min(6), resumed.nursedMs(BreastSide.RIGHT, start + min(19)))
        assertEquals(min(14), resumed.nursedMs(start + min(19)))

        val all = resumed.segmentsAt(start + min(19))
        assertEquals(min(5), breastfeedPausedMillis(all))
    }

    @Test
    fun resumingOnTheSameBreastIsAllowed() {
        // The nappy halfway through the left side: the feed carries on where it
        // was, and the two stretches are still two stretches.
        val resumed = session.paused(start + min(4)).resumed(BreastSide.LEFT, start + min(9))

        assertEquals(BreastSide.LEFT, resumed.currentSide)
        assertEquals(2, resumed.segmentsAt(start + min(12)).size)
        assertEquals(min(7), resumed.nursedMs(BreastSide.LEFT, start + min(12)))
    }

    @Test
    fun switchingSidesLeavesNoBreakBehind() {
        val switched = session.switched(start + min(8))

        assertFalse(switched.isPaused)
        assertEquals(BreastSide.RIGHT, switched.currentSide)
        assertEquals(0L, breastfeedPausedMillis(switched.segmentsAt(start + min(14))))
        assertEquals(min(14), switched.nursedMs(start + min(14)))
    }

    @Test
    fun aFeedThatRanStraightThroughHasNoBreaks() {
        val segments = listOf(
            NursingSegment(BreastSide.LEFT, start, start + min(8)),
            NursingSegment(BreastSide.RIGHT, start + min(8), start + min(14)),
        )

        assertEquals(0L, breastfeedPausedMillis(segments))
    }

    @Test
    fun theStatisticsCountTheBreastAndNotTheBurping() {
        // What the session above would be saved as: 14 minutes of feeding
        // across a 19-minute wall clock.
        val entry = FeedingEntity(
            type = FeedType.BREAST,
            side = BreastSide.BOTH,
            startTime = start,
            endTime = start + min(19),
            leftDurationMs = min(8),
            rightDurationMs = min(6),
            segments = listOf(
                NursingSegment(BreastSide.LEFT, start, start + min(8)),
                NursingSegment(BreastSide.RIGHT, start + min(13), start + min(19)),
            ),
        )

        assertEquals(min(14), breastfeedMillis(entry))
        assertEquals(min(5), breastfeedPausedMillis(entry.segments))
    }
}
