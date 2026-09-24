package com.gproust.sprout

import com.gproust.sprout.data.BREASTFEED_JOIN_MAX_GAP_MS
import com.gproust.sprout.data.breastfeedJoinGap
import com.gproust.sprout.data.breastfeedJoinOffers
import com.gproust.sprout.data.joinedBreastfeed
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.ui.feeding.breastfeedPausedMillis
import com.gproust.sprout.ui.stats.breastfeedMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two breastfeeds saved apart can be joined into the one feed they were
 * (BDR-19), and the whole feature is one rule: **a join is a pause that
 * happened after the fact**. The gap between the two becomes a break, and a
 * break is not time at the breast.
 *
 * The same cases are checked on iOS (`BreastfeedJoinTests`), because a
 * household with one phone of each has to offer the same join on the same two
 * feeds, and get the same feed out of it.
 */
class BreastfeedJoinTest {

    private val start = 1_750_000_000_000L
    private fun min(n: Long): Long = n * 60_000L

    /** A breastfeed timed stretch by stretch, the way the timer saves one. */
    private fun feed(
        vararg segments: NursingSegment,
        babyId: Long = 1L,
        notes: String? = null,
    ): FeedingEntity = FeedingEntity(
        babyId = babyId,
        type = FeedType.BREAST,
        startTime = segments.first().startTime,
        endTime = segments.last().endTime,
        segments = segments.toList(),
        notes = notes,
    )

    private fun left(from: Long, to: Long): NursingSegment = NursingSegment(BreastSide.LEFT, start + min(from), start + min(to))
    private fun right(from: Long, to: Long): NursingSegment = NursingSegment(BreastSide.RIGHT, start + min(from), start + min(to))

    // The example the feature was asked for: the left side, a burp, the right.
    private val first = feed(left(0, 9))
    private val second = feed(right(14, 20))

    @Test
    fun twoFeedsFiveMinutesApartBecomeOneWithTheGapAsABreak() {
        assertEquals(min(5), breastfeedJoinGap(first, second))

        val joined = joinedBreastfeed(first, second)

        assertEquals(start, joined.startTime)
        assertEquals(start + min(20), joined.endTime)
        assertEquals(listOf(left(0, 9), right(14, 20)), joined.segments)
        assertEquals(BreastSide.BOTH, joined.side)
        assertEquals(min(9), joined.leftDurationMs)
        assertEquals(min(6), joined.rightDurationMs)
        // Fifteen minutes at the breast, not twenty: the burp is not feeding.
        assertEquals(min(15), breastfeedMillis(joined))
        assertEquals(min(5), breastfeedPausedMillis(joined.segments))
    }

    @Test
    fun theEarlierFeedIsTheOneThatStays() {
        val joined = joinedBreastfeed(first, second)

        assertEquals(first.uid, joined.uid)
        assertEquals(first.id, joined.id)
        assertEquals(FeedType.BREAST, joined.type)
    }

    @Test
    fun feedsSavedBackToBackJoinWithNoBreak() {
        val next = feed(right(9, 15))

        assertEquals(0L, breastfeedJoinGap(first, next))
        assertEquals(0L, breastfeedPausedMillis(joinedBreastfeed(first, next).segments))
    }

    @Test
    fun theOfferReachesHalfAnHourAndNoFurther() {
        assertEquals(min(30), BREASTFEED_JOIN_MAX_GAP_MS)
        assertEquals(min(30), breastfeedJoinGap(first, feed(right(39, 45))))
        assertNull(breastfeedJoinGap(first, feed(NursingSegment(BreastSide.RIGHT, start + min(39) + 1, start + min(45)))))
    }

    @Test
    fun feedsThatOverlapCannotBeJoined() {
        assertNull(breastfeedJoinGap(first, feed(right(8, 12))))
    }

    @Test
    fun theOrderMatters() {
        assertNull(breastfeedJoinGap(second, first))
    }

    @Test
    fun onlyBreastfeedsJoin() {
        val bottle = FeedingEntity(type = FeedType.BOTTLE, babyId = 1L, amountMl = 90, startTime = start + min(14))

        assertNull(breastfeedJoinGap(first, bottle))
        assertNull(breastfeedJoinGap(bottle.copy(startTime = start - min(5)), first))
    }

    @Test
    fun aFeedWithNoStretchesHasNothingToLineUp() {
        // Logged with a start and a length, before per-side timing existed.
        val untimed = FeedingEntity(
            babyId = 1L,
            type = FeedType.BREAST,
            side = BreastSide.LEFT,
            startTime = start,
            endTime = start + min(9),
        )

        assertNull(breastfeedJoinGap(untimed, second))
        assertNull(breastfeedJoinGap(first, untimed.copy(startTime = start + min(14), endTime = start + min(20))))
    }

    @Test
    fun twinsFeedsAreNeverJoined() {
        assertNull(breastfeedJoinGap(first, feed(right(14, 20), babyId = 2L)))
    }

    @Test
    fun aFeedCannotBeJoinedToItself() {
        assertNull(breastfeedJoinGap(first, first))
    }

    @Test
    fun theSameSideTwiceStaysThatSide() {
        val joined = joinedBreastfeed(first, feed(left(14, 20)))

        assertEquals(BreastSide.LEFT, joined.side)
        assertEquals(min(15), joined.leftDurationMs)
        assertNull(joined.rightDurationMs)
    }

    @Test
    fun breaksAlreadyInEitherFeedAreKept() {
        // The first feed was paused once already, for three minutes.
        val paused = feed(left(0, 5), left(8, 12))
        val next = feed(right(17, 23))

        val joined = joinedBreastfeed(paused, next)

        assertEquals(min(3 + 5), breastfeedPausedMillis(joined.segments))
        assertEquals(min(15), breastfeedMillis(joined))
    }

    @Test
    fun notesFromBothAreKept() {
        assertEquals(
            "Sleepy\nHiccups",
            joinedBreastfeed(first.copy(notes = "Sleepy"), second.copy(notes = " Hiccups ")).notes,
        )
        assertEquals("Hiccups", joinedBreastfeed(first.copy(notes = " "), second.copy(notes = "Hiccups")).notes)
        assertEquals("Sleepy", joinedBreastfeed(first.copy(notes = "Sleepy"), second.copy(notes = "Sleepy")).notes)
        assertNull(joinedBreastfeed(first, second).notes)
    }

    @Test
    fun theOfferIsOnTheLaterFeedAndNamesTheEarlierOne() {
        // Newest first, the way the history holds them.
        val offers = breastfeedJoinOffers(listOf(second, first))

        assertEquals(mapOf(second.uid to first), offers)
    }

    @Test
    fun anythingLoggedBetweenMeansTheyWereTwoFeeds() {
        val bottle = FeedingEntity(type = FeedType.BOTTLE, babyId = 1L, amountMl = 60, startTime = start + min(11))

        assertTrue(breastfeedJoinOffers(listOf(second, bottle, first)).isEmpty())
    }

    @Test
    fun aRunOfFeedsIsOfferedPairByPair() {
        val third = feed(left(25, 31))

        val offers = breastfeedJoinOffers(listOf(third, second, first))

        assertEquals(mapOf(second.uid to first, third.uid to second), offers)
    }

    @Test
    fun feedsFarApartAreNotOffered() {
        val later = feed(right(120, 130))

        assertTrue(breastfeedJoinOffers(listOf(later, first)).isEmpty())
    }
}
