package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.ui.feeding.NursingSession
import com.gproust.sprout.ui.feeding.NursingSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget reads this on every render, so a session that round-trips wrong is
 * a widget that shows the wrong side mid-feed.
 *
 * It is also the app's only copy of the running feeds, one per baby (BDR-23).
 * A timer can be showing in several places at once — the feeding screen's bar,
 * the dashboard's live card, a widget tap landing on Feeding while the timer is
 * already open — and while each of them kept a copy of its own, stopping the
 * feed in one left the others still holding it, and each of them could save the
 * same breastfeed again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NursingSessionStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val session = NursingSession(
        sessionStart = 1_750_000_000_000L,
        currentSide = BreastSide.RIGHT,
        segmentStart = 1_750_000_000_000L,
        babyUid = "baby-lea",
    )

    /** Noah's feed, beside Léa's: twins feeding together (BDR-23). */
    private val twin = NursingSession(
        sessionStart = 1_750_000_060_000L,
        currentSide = BreastSide.LEFT,
        segmentStart = 1_750_000_060_000L,
        babyUid = "baby-noah",
    )

    @Before
    fun clear() = NursingSessionStore.clear(context)

    @Test
    fun loadRoundTripsASavedSession() {
        NursingSessionStore.save(context, session)

        val loaded = NursingSessionStore.load(context, "baby-lea")

        assertEquals(session.currentSide, loaded?.currentSide)
        assertEquals(session.segmentStart, loaded?.segmentStart)
    }

    @Test
    fun clearEndsEverySession() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.save(context, twin)
        NursingSessionStore.clear(context)

        assertEquals(emptyList<NursingSession>(), NursingSessionStore.loadAll(context))
    }

    @Test
    fun everyReaderSeesTheSameSessions() {
        val first = NursingSessionStore.sessions(context)
        val second = NursingSessionStore.sessions(context)

        NursingSessionStore.save(context, session)

        assertEquals(session.sessionStart, first.value.single().sessionStart)
        assertEquals(session.sessionStart, second.value.single().sessionStart)
    }

    @Test
    fun endingASessionIsVisibleToEveryReader() {
        val watcher = NursingSessionStore.sessions(context)
        NursingSessionStore.save(context, session)

        NursingSessionStore.consume(context, "baby-lea")

        assertEquals(emptyList<NursingSession>(), watcher.value)
    }

    /** The duplicate feeds: two screens stopping the one timer. */
    @Test
    fun onlyOneCallerIsGivenTheSessionToSave() {
        NursingSessionStore.save(context, session)

        val first = NursingSessionStore.consume(context, "baby-lea")
        val second = NursingSessionStore.consume(context, "baby-lea")

        assertEquals(session.sessionStart, first?.sessionStart)
        assertNull(second)
    }

    /** And two ways into the timer opening at once, for one baby. */
    @Test
    fun startingWhileOneRunsForThatBabyKeepsTheRunningSession() {
        NursingSessionStore.save(context, session)
        val later = session.copy(
            sessionStart = session.sessionStart + 60_000L,
            currentSide = BreastSide.LEFT,
        )

        val running = NursingSessionStore.startIfIdle(context, later)

        assertEquals(session.sessionStart, running.sessionStart)
        assertEquals(session.sessionStart, NursingSessionStore.load(context, "baby-lea")?.sessionStart)
    }

    /** The twin case: Léa's feed runs, Noah's starts beside it, and both are kept. */
    @Test
    fun startingForAnotherBabyRunsBothFeeds() {
        NursingSessionStore.startIfIdle(context, session)

        val running = NursingSessionStore.startIfIdle(context, twin)

        assertEquals(twin, running)
        assertEquals(listOf(session, twin), NursingSessionStore.loadAll(context))
    }

    /** Stop & save on one leaves the other running. */
    @Test
    fun savingOneFeedLeavesTheOtherRunning() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.save(context, twin)

        val taken = NursingSessionStore.consume(context, "baby-lea")

        assertEquals(session, taken)
        assertEquals(listOf(twin), NursingSessionStore.loadAll(context))
    }

    /** Pausing one feed does not pause the other. */
    @Test
    fun pausingOneFeedLeavesTheOtherNursing() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.save(context, twin)

        NursingSessionStore.update(context, "baby-noah") { it.paused(twin.sessionStart + 120_000L) }

        assertEquals(true, NursingSessionStore.load(context, "baby-noah")?.isPaused)
        assertEquals(session, NursingSessionStore.load(context, "baby-lea"))
    }

    /**
     * A feed put down for a burp and left there while the phone is killed has
     * to come back as a feed on a break — not as one that has been nursing all
     * along, which is the reading a missing flag would give it.
     */
    @Test
    fun aBreakSurvivesProcessDeath() {
        val pausedAt = session.sessionStart + 8 * 60_000L
        NursingSessionStore.save(context, session.paused(pausedAt))
        NursingSessionStore.forgetInMemory()

        val loaded = NursingSessionStore.load(context, "baby-lea")

        assertEquals(pausedAt, loaded?.pausedAt)
        assertEquals(1, loaded?.segments?.size)
        assertEquals(8 * 60_000L, loaded?.nursedMs(pausedAt + 5 * 60_000L))
    }

    @Test
    fun aSessionWithNoBreakReadsBackAsNursing() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.forgetInMemory()

        assertNull(NursingSessionStore.load(context, "baby-lea")?.pausedAt)
    }

    /**
     * Each feed comes back with its baby, or a feed left running while the
     * phone is killed is saved to whichever twin is selected.
     */
    @Test
    fun everyFeedAndItsBabySurviveProcessDeath() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.save(context, twin.paused(twin.sessionStart + 60_000L))
        NursingSessionStore.forgetInMemory()

        assertEquals(session, NursingSessionStore.load(context, "baby-lea"))
        assertEquals(true, NursingSessionStore.load(context, "baby-noah")?.isPaused)
        assertEquals(2, NursingSessionStore.loadAll(context).size)
    }

    /**
     * The one session a version before BDR-23 kept, as flat keys, is still
     * there after the update: a feed running through it is neither lost nor
     * moved to the wrong baby.
     */
    @Test
    fun aSessionStoredByThePreviousVersionIsReadBack() {
        storeAsThePreviousVersion(babyUid = "baby-lea")

        assertEquals(session, NursingSessionStore.load(context, "baby-lea"))
    }

    /** One stored before the baby was recorded is given the selected baby, once. */
    @Test
    fun aSessionWithNoBabyIsGivenTheSelectedOne() {
        storeAsThePreviousVersion(babyUid = null)

        NursingSessionStore.adoptUnnamed(context, "baby-noah")
        NursingSessionStore.forgetInMemory()

        assertEquals(session.copy(babyUid = "baby-noah"), NursingSessionStore.load(context, "baby-noah"))
        assertNull(NursingSessionStore.load(context, null))
    }

    @Test
    fun startingWhileIdleStartsTheSession() {
        val running = NursingSessionStore.startIfIdle(context, session)

        assertEquals(session.sessionStart, running.sessionStart)
        assertEquals(session.currentSide, NursingSessionStore.load(context, "baby-lea")?.currentSide)
    }

    /** Writes [session] the way the store did when it kept one feed only. */
    private fun storeAsThePreviousVersion(babyUid: String?) {
        context.getSharedPreferences("nursing_session", Context.MODE_PRIVATE).edit()
            .clear()
            .putLong("sessionStart", session.sessionStart)
            .putString("currentSide", session.currentSide.name)
            .putLong("segmentStart", session.segmentStart)
            .putString("segments", "")
            .putLong("pausedAt", 0L)
            .apply { if (babyUid != null) putString("babyUid", babyUid) }
            .commit()
        NursingSessionStore.forgetInMemory()
    }
}
