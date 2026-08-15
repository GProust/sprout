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

// The widget reads this on every render, so a session that round-trips wrong
// is a widget that shows the wrong side mid-feed.
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
}
