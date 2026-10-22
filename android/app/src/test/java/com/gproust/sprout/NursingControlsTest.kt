package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.feeding.NursingControl
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.after
import com.gproust.sprout.ui.feeding.nursingControls
import com.gproust.sprout.ui.feeding.taking
import com.gproust.sprout.ui.feeding.toFeeding
import com.gproust.sprout.ui.feeding.updating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The controls a running feed offers outside the app — the notification, the
 * Live Activity — are the timer's own (BDR-24). These are the cases that make
 * a tap there the same act as the same tap on the timer screen.
 *
 * The same cases are checked on iOS (`NursingControlsTests.swift`).
 */
class NursingControlsTest {

    private val start = 1_750_000_000_000L
    private fun min(n: Long) = n * 60_000L

    private val nursing = NursingSession(
        sessionStart = start,
        currentSide = BreastSide.LEFT,
        segmentStart = start,
        babyUid = "baby-lea",
    )

    @Test
    fun aFeedAtTheBreastOffersPauseSwitchAndStop() {
        assertEquals(
            listOf(NursingControl.PAUSE, NursingControl.SWITCH, NursingControl.STOP),
            nursingControls(nursing),
        )
    }

    /** Coming back names the breast. */
    @Test
    fun aFeedOnABreakOffersEachBreastAndStop() {
        assertEquals(
            listOf(NursingControl.RESUME_LEFT, NursingControl.RESUME_RIGHT, NursingControl.STOP),
            nursingControls(nursing.paused(start + min(8))),
        )
    }

    @Test
    fun eachControlIsTheTimersOwnAct() {
        val at = start + min(8)
        val paused = nursing.paused(at)

        assertEquals(nursing.paused(at), nursing.after(NursingControl.PAUSE, at))
        assertEquals(nursing.switched(at), nursing.after(NursingControl.SWITCH, at))
        assertEquals(paused.resumed(BreastSide.LEFT, at + min(2)), paused.after(NursingControl.RESUME_LEFT, at + min(2)))
        assertEquals(paused.resumed(BreastSide.RIGHT, at + min(2)), paused.after(NursingControl.RESUME_RIGHT, at + min(2)))
    }

    /** A double tap on Pause is a no-op the second time: the break's clock is not restarted. */
    @Test
    fun pausingTwiceKeepsTheFirstBreak() {
        val once = nursing.after(NursingControl.PAUSE, start + min(8))

        val twice = once.after(NursingControl.PAUSE, start + min(9))

        assertEquals(once, twice)
        assertEquals(min(3), twice.pausedMs(start + min(11)))
    }

    /** A stale Switch, tapped once the feed went on a break, does not resume it. */
    @Test
    fun aControlThatNoLongerFitsChangesNothing() {
        val paused = nursing.paused(start + min(8))

        assertEquals(paused, paused.after(NursingControl.SWITCH, start + min(9)))
        assertEquals(nursing, nursing.after(NursingControl.RESUME_RIGHT, start + min(9)))
        assertEquals(nursing, nursing.after(NursingControl.STOP, start + min(9)))
    }

    /** Stop & save twice saves one feed: the second is handed nothing. */
    @Test
    fun stoppingTwiceSavesOnce() {
        val first = taking(listOf(nursing), "baby-lea")
        val second = taking(first.sessions, "baby-lea")

        assertEquals(nursing, first.taken)
        assertNull(second.taken)
    }

    /**
     * The feed saved after taps outside the app is the feed the same taps on the
     * timer save, and it ends when its last stretch ended — not when Stop was
     * tapped.
     */
    @Test
    fun theSavedFeedEndsWithItsLastStretch() {
        var sessions = listOf(nursing)
        sessions = updating(sessions, "baby-lea") { it.after(NursingControl.SWITCH, start + min(6)) }
        sessions = updating(sessions, "baby-lea") { it.after(NursingControl.PAUSE, start + min(10)) }
        val taken = taking(sessions, "baby-lea").taken!!

        val feed = taken.toFeeding(start + min(40))

        assertEquals(BreastSide.BOTH, feed.side)
        assertEquals(start + min(10), feed.endTime)
        assertEquals(min(6), feed.leftDurationMs)
        assertEquals(min(4), feed.rightDurationMs)
    }
}
