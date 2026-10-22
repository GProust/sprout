package com.gproust.sprout

import com.gproust.sprout.data.TourMark
import com.gproust.sprout.data.TourStep
import com.gproust.sprout.data.TourTrigger
import com.gproust.sprout.data.tourMark
import com.gproust.sprout.data.tourMarkAfterArrival
import com.gproust.sprout.data.tourStarts
import com.gproust.sprout.data.tourStepAfter
import com.gproust.sprout.data.tourSteps
import com.gproust.sprout.data.tourStepsAside
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the guided tour shows, and when it gets out of the way (BDR-27).
 *
 * The same cases are checked on iOS (`TourTests.swift`): one household with a
 * phone of each should not have one of them showing a newcomer round and the
 * other not.
 */
class TourTest {

    @Test
    fun aPhoneThatHasJustFinishedOnboardingGetsTheTour() {
        assertTrue(tourStarts(TourTrigger.ARRIVAL, TourMark.PENDING, openedForSomethingElse = false))
    }

    /** Set up before the tour existed: a profile, and nothing stored. */
    @Test
    fun anExistingUserNeverGetsIt() {
        assertFalse(tourStarts(TourTrigger.ARRIVAL, mark = null, openedForSomethingElse = false))
    }

    /**
     * A restored phone skips onboarding, because its profile came with it, and
     * carries whatever its backup held: the spent mark, or nothing at all.
     */
    @Test
    fun aRestoredPhoneNeverGetsIt() {
        assertFalse(tourStarts(TourTrigger.ARRIVAL, TourMark.DONE, openedForSomethingElse = false))
        assertFalse(tourStarts(TourTrigger.ARRIVAL, mark = null, openedForSomethingElse = false))
    }

    /** Finished, skipped, or killed half-way: the next launch is an ordinary one. */
    @Test
    fun onceOfferedItNeverComesBackOnItsOwn() {
        val spent = tourMarkAfterArrival(TourMark.PENDING)
        assertEquals(TourMark.DONE, spent)
        assertFalse(tourStarts(TourTrigger.ARRIVAL, spent, openedForSomethingElse = false))
    }

    @Test
    fun aReplayStartsWhateverIsStored() {
        assertTrue(tourStarts(TourTrigger.REPLAY, TourMark.DONE, openedForSomethingElse = false))
        // Including on a phone that was set up before the tour existed.
        assertTrue(tourStarts(TourTrigger.REPLAY, mark = null, openedForSomethingElse = false))
    }

    /** The tap or the file wins, and the tour does not wait for a later launch either. */
    @Test
    fun aLaunchFromAWidgetOrAFileDoesNotStartIt() {
        assertFalse(tourStarts(TourTrigger.ARRIVAL, TourMark.PENDING, openedForSomethingElse = true))
        val spent = tourMarkAfterArrival(TourMark.PENDING)
        assertFalse(tourStarts(TourTrigger.ARRIVAL, spent, openedForSomethingElse = false))
    }

    @Test
    fun arrivingLeavesAnythingButAnOwedTourAsItWas() {
        assertNull(tourMarkAfterArrival(null))
        assertEquals(TourMark.DONE, tourMarkAfterArrival(TourMark.DONE))
    }

    @Test
    fun aFeedStartingEndsIt() {
        assertTrue(tourStepsAside(onDashboard = true, feedWasRunning = false, feedIsRunning = true))
    }

    /** A replay asked for in the middle of a feed is not cut short by that feed. */
    @Test
    fun aFeedAlreadyRunningDoesNotEndIt() {
        assertFalse(tourStepsAside(onDashboard = true, feedWasRunning = true, feedIsRunning = true))
        assertFalse(tourStepsAside(onDashboard = true, feedWasRunning = true, feedIsRunning = false))
        assertFalse(tourStepsAside(onDashboard = true, feedWasRunning = false, feedIsRunning = false))
    }

    /** A tile, a tab, Settings, or a widget tap or a file arriving underneath it. */
    @Test
    fun leavingTheDashboardEndsIt() {
        assertTrue(tourStepsAside(onDashboard = false, feedWasRunning = false, feedIsRunning = false))
    }

    @Test
    fun oneBabyIsShownEveryStep() {
        assertEquals(
            listOf(TourStep.LOG_TILES, TourStep.BREASTFEED, TourStep.BOTTOM_BAR, TourStep.SETTINGS),
            tourSteps(1),
        )
    }

    /** No tiles and no feed buttons yet: the dashboard's own card asks for a baby. */
    @Test
    fun noBabyYetLeavesOutWhatIsNotThere() {
        assertEquals(listOf(TourStep.BOTTOM_BAR, TourStep.SETTINGS), tourSteps(0))
    }

    /** The tiles have moved to each baby's own tab, which the bottom bar's step names. */
    @Test
    fun twoBabiesLeaveOutTheTiles() {
        val expected = listOf(TourStep.BREASTFEED, TourStep.BOTTOM_BAR, TourStep.SETTINGS)
        assertEquals(expected, tourSteps(2))
        assertEquals(expected, tourSteps(3))
    }

    @Test
    fun nextWalksTheStepsAndEndsAfterTheLast() {
        val steps = tourSteps(1)
        assertEquals(TourStep.BREASTFEED, tourStepAfter(TourStep.LOG_TILES, steps))
        assertEquals(TourStep.BOTTOM_BAR, tourStepAfter(TourStep.BREASTFEED, steps))
        assertEquals(TourStep.SETTINGS, tourStepAfter(TourStep.BOTTOM_BAR, steps))
        assertNull(tourStepAfter(TourStep.SETTINGS, steps))
    }

    @Test
    fun aStepTheDashboardCannotShowEndsTheTour() {
        assertNull(tourStepAfter(TourStep.LOG_TILES, tourSteps(0)))
    }

    /** What is already in a parent's settings file has to keep reading the same. */
    @Test
    fun theStoredSpellingsArePinned() {
        assertEquals("pending", TourMark.PENDING.stored)
        assertEquals("done", TourMark.DONE.stored)
        assertEquals(TourMark.PENDING, tourMark("pending"))
        assertEquals(TourMark.DONE, tourMark("done"))
    }

    @Test
    fun anythingElseStoredReadsAsNothing() {
        assertNull(tourMark(null))
        assertNull(tourMark(""))
        assertNull(tourMark("PENDING"))
    }
}
