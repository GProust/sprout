package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.adoptingUnnamed
import com.gproust.sprout.ui.feeding.replacing
import com.gproust.sprout.ui.feeding.runningFor
import com.gproust.sprout.ui.feeding.sideForNextFeed
import com.gproust.sprout.ui.feeding.startingIfIdle
import com.gproust.sprout.ui.feeding.taking
import com.gproust.sprout.ui.feeding.toFeeding
import com.gproust.sprout.ui.feeding.updating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twins feed together, so the phone can time a feed for each baby at once — and
 * never two for one baby (BDR-23). These are the rules for what each tap does
 * to the list of running feeds.
 *
 * The same cases are checked on iOS (`NursingSessionsTests.swift`), because a
 * household with one phone of each has to agree on what a tap does.
 */
class NursingSessionsTest {

    private val start = 1_750_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private fun feed(babyUid: String?, side: BreastSide = BreastSide.LEFT, at: Long = start) =
        NursingSession(sessionStart = at, currentSide = side, segmentStart = at, babyUid = babyUid)

    private val lea = feed("baby-lea", BreastSide.LEFT, start)
    private val noah = feed("baby-noah", BreastSide.RIGHT, start + min(1))

    @Test
    fun startingWhileIdleStartsTheFeed() {
        val result = startingIfIdle(emptyList(), lea)

        assertEquals(listOf(lea), result.sessions)
        assertEquals(lea, result.running)
    }

    /** Two ways into the timer opening at once still time one baby once. */
    @Test
    fun startingForABabyAlreadyFeedingHandsBackTheRunningFeed() {
        val again = feed("baby-lea", BreastSide.RIGHT, start + min(3))

        val result = startingIfIdle(listOf(lea), again)

        assertEquals(listOf(lea), result.sessions)
        assertEquals(lea, result.running)
    }

    /** The twin case: Léa's feed is running, and Noah's starts beside it. */
    @Test
    fun startingForAnotherBabyStartsASecondFeed() {
        val result = startingIfIdle(listOf(lea), noah)

        assertEquals(listOf(lea, noah), result.sessions)
        assertEquals(noah, result.running)
    }

    @Test
    fun runningFeedsAreListedOldestFirst() {
        val result = startingIfIdle(listOf(noah), lea)

        assertEquals(listOf(lea, noah), result.sessions)
    }

    @Test
    fun eachBabyFindsItsOwnFeed() {
        val both = listOf(lea, noah)

        assertEquals(lea, both.runningFor("baby-lea"))
        assertEquals(noah, both.runningFor("baby-noah"))
        assertNull(both.runningFor("baby-zoe"))
    }

    /** Pausing one feed does not pause the other. */
    @Test
    fun pausingOneFeedLeavesTheOtherNursing() {
        val both = listOf(lea, noah)

        val after = updating(both, "baby-lea") { it.paused(start + min(8)) }

        assertTrue(after.runningFor("baby-lea")!!.isPaused)
        assertEquals(noah, after.runningFor("baby-noah"))
        // Léa's clock stopped at eight minutes; Noah's carried on.
        assertEquals(min(8), after.runningFor("baby-lea")!!.nursedMs(start + min(20)))
        assertEquals(min(19), after.runningFor("baby-noah")!!.nursedMs(start + min(20)))
    }

    /** And pausing twice still does not restart a break — per feed, as for one. */
    @Test
    fun pausingAFeedTwiceDoesNotRestartItsBreak() {
        val paused = updating(listOf(lea, noah), "baby-lea") { it.paused(start + min(8)) }

        val again = updating(paused, "baby-lea") { it.paused(start + min(11)) }

        assertEquals(paused, again)
    }

    @Test
    fun switchingOneFeedLeavesTheOtherOnItsBreast() {
        val after = updating(listOf(lea, noah), "baby-noah") { it.switched(start + min(6)) }

        assertEquals(BreastSide.LEFT, after.runningFor("baby-noah")!!.currentSide)
        assertEquals(BreastSide.LEFT, after.runningFor("baby-lea")!!.currentSide)
        assertEquals(lea, after.runningFor("baby-lea"))
    }

    @Test
    fun changingABabyWithNoFeedChangesNothing() {
        val both = listOf(lea, noah)

        assertSame(both, updating(both, "baby-zoe") { it.paused(start + min(8)) })
    }

    @Test
    fun replacingPutsTheFeedInPlaceOfItsBabysOwn() {
        val switched = lea.switched(start + min(4))

        assertEquals(listOf(switched, noah), replacing(listOf(lea, noah), switched))
    }

    /** Stop & save on one leaves the other running. */
    @Test
    fun stoppingOneFeedLeavesTheOtherRunning() {
        val take = taking(listOf(lea, noah), "baby-lea")

        assertEquals(lea, take.taken)
        assertEquals(listOf(noah), take.sessions)
    }

    /** The duplicate feeds: two screens stopping one timer. */
    @Test
    fun onlyTheFirstStopIsHandedTheFeed() {
        val first = taking(listOf(lea, noah), "baby-lea")
        val second = taking(first.sessions, "baby-lea")

        assertNull(second.taken)
        assertEquals(listOf(noah), second.sessions)
    }

    /** Each saved feed ends when its own last stretch ended. */
    @Test
    fun eachFeedSavesItsOwnStretches() {
        val both = updating(listOf(lea, noah), "baby-lea") { it.paused(start + min(10)) }
        val saveAt = start + min(30)

        val leaFeed = both.runningFor("baby-lea")!!.toFeeding(saveAt)
        val noahFeed = both.runningFor("baby-noah")!!.toFeeding(saveAt)

        assertEquals(start + min(10), leaFeed.endTime)
        assertEquals(min(10), leaFeed.leftDurationMs)
        assertEquals(saveAt, noahFeed.endTime)
        assertEquals(min(29), noahFeed.rightDurationMs)
    }

    @Test
    fun withNobodyElseFeedingTheBabysOwnAlternationDecides() {
        assertEquals(BreastSide.RIGHT, sideForNextFeed(emptyList(), "baby-noah", BreastSide.RIGHT))
        assertNull(sideForNextFeed(emptyList(), "baby-noah", null))
    }

    /** The second feed defaults to the breast the first is not on. */
    @Test
    fun aSecondFeedIsOfferedTheFreeBreast() {
        assertEquals(BreastSide.RIGHT, sideForNextFeed(listOf(lea), "baby-noah", BreastSide.LEFT))
        assertEquals(BreastSide.RIGHT, sideForNextFeed(listOf(lea), "baby-noah", null))
    }

    /** A baby's own running feed is not "another baby at that breast". */
    @Test
    fun aBabysOwnFeedDoesNotDecideItsSide() {
        assertEquals(BreastSide.LEFT, sideForNextFeed(listOf(lea), "baby-lea", BreastSide.LEFT))
    }

    /** A feed on a break is at no breast at all. */
    @Test
    fun aFeedOnABreakLeavesBothBreastsFree() {
        val paused = lea.paused(start + min(5))

        assertEquals(BreastSide.LEFT, sideForNextFeed(listOf(paused), "baby-noah", BreastSide.LEFT))
    }

    /** With both breasts taken there is nothing free to offer, so nothing is guessed. */
    @Test
    fun withBothBreastsTakenTheAlternationDecides() {
        val zoe = feed("baby-zoe", BreastSide.RIGHT)

        assertEquals(
            BreastSide.LEFT,
            sideForNextFeed(listOf(lea, zoe), "baby-noah", BreastSide.LEFT),
        )
    }

    /** A feed left running through the update keeps going, on the baby selected then. */
    @Test
    fun aFeedWithNoBabyIsGivenTheSelectedOne() {
        val unnamed = feed(null)

        val adopted = adoptingUnnamed(listOf(unnamed), "baby-lea")

        assertEquals(listOf(unnamed.copy(babyUid = "baby-lea")), adopted)
    }

    @Test
    fun withNoBabySelectedAFeedWithNoBabyStaysAsItIs() {
        val unnamed = feed(null)

        assertEquals(listOf(unnamed), adoptingUnnamed(listOf(unnamed), null))
    }

    @Test
    fun feedsThatNameTheirBabyAreLeftAlone() {
        val both = listOf(lea, noah)

        assertSame(both, adoptingUnnamed(both, "baby-lea"))
    }

    /** Never two feeds for one baby, even by adoption. */
    @Test
    fun aFeedWithNoBabyIsNotGivenABabyAlreadyFeeding() {
        val unnamed = feed(null, at = start - min(5))

        assertEquals(listOf(unnamed, lea), adoptingUnnamed(listOf(unnamed, lea), "baby-lea"))
    }
}
