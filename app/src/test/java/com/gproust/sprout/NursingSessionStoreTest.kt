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
 * It is also the app's only copy of a running feed. The timer can be showing in
 * several places at once — the feeding screen's bar, the dashboard's live card,
 * a widget tap landing on Feeding while the timer is already open — and while
 * each of them kept a copy of its own, stopping the feed in one left the others
 * still holding it, and each of them could save the same breastfeed again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NursingSessionStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val session = NursingSession(
        sessionStart = 1_750_000_000_000L,
        currentSide = BreastSide.RIGHT,
        segmentStart = 1_750_000_000_000L,
    )

    @Before
    fun clear() = NursingSessionStore.clear(context)

    @Test
    fun loadRoundTripsASavedSession() {
        NursingSessionStore.save(context, session)

        val loaded = NursingSessionStore.load(context)

        assertEquals(session.currentSide, loaded?.currentSide)
        assertEquals(session.segmentStart, loaded?.segmentStart)
    }

    @Test
    fun clearEndsTheSession() {
        NursingSessionStore.save(context, session)
        NursingSessionStore.clear(context)

        assertNull(NursingSessionStore.load(context))
    }

    @Test
    fun everyReaderSeesTheSameSession() {
        val first = NursingSessionStore.sessions(context)
        val second = NursingSessionStore.sessions(context)

        NursingSessionStore.save(context, session)

        assertEquals(session.sessionStart, first.value?.sessionStart)
        assertEquals(session.sessionStart, second.value?.sessionStart)
    }

    @Test
    fun endingASessionIsVisibleToEveryReader() {
        val watcher = NursingSessionStore.sessions(context)
        NursingSessionStore.save(context, session)

        NursingSessionStore.consume(context)

        assertNull(watcher.value)
    }

    /** The duplicate feeds: two screens stopping the one timer. */
    @Test
    fun onlyOneCallerIsGivenTheSessionToSave() {
        NursingSessionStore.save(context, session)

        val first = NursingSessionStore.consume(context)
        val second = NursingSessionStore.consume(context)

        assertEquals(session.sessionStart, first?.sessionStart)
        assertNull(second)
    }

    /** And two ways into the timer opening at once. */
    @Test
    fun startingWhileOneRunsKeepsTheRunningSession() {
        NursingSessionStore.save(context, session)
        val later = session.copy(
            sessionStart = session.sessionStart + 60_000L,
            currentSide = BreastSide.LEFT,
        )

        val running = NursingSessionStore.startIfIdle(context, later)

        assertEquals(session.sessionStart, running.sessionStart)
        assertEquals(session.sessionStart, NursingSessionStore.load(context)?.sessionStart)
    }

    @Test
    fun startingWhileIdleStartsTheSession() {
        val running = NursingSessionStore.startIfIdle(context, session)

        assertEquals(session.sessionStart, running.sessionStart)
        assertEquals(session.currentSide, NursingSessionStore.load(context)?.currentSide)
    }
}
