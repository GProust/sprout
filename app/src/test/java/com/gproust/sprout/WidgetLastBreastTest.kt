package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.widget.lastBreastLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A bottle between two nursings must not take the last breast with it: the
// widget still has to answer "which side do I start on?" for a full day.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class WidgetLastBreastTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val hour = 60 * 60_000L
    private val now = 1_750_000_000_000L

    private fun bottle(at: Long) =
        FeedingEntity(type = FeedType.BOTTLE, amountMl = 90, startTime = at)

    private fun breastFeed(
        at: Long,
        side: BreastSide? = BreastSide.LEFT,
        segments: List<NursingSegment> = emptyList(),
    ) = FeedingEntity(type = FeedType.BREAST, side = side, startTime = at, segments = segments)

    @Test
    fun bottleAfterANursing_stillShowsTheBreastItStartedOn() {
        val line = lastBreastLine(
            context,
            feed = bottle(now - hour),
            lastBreast = breastFeed(now - 3 * hour, side = BreastSide.RIGHT),
            now = now,
        )
        assertEquals("Last breast: Right · 3 h ago", line)
    }

    @Test
    fun solidsAfterANursing_showItToo() {
        val line = lastBreastLine(
            context,
            feed = FeedingEntity(type = FeedType.SOLID, startTime = now - 20 * 60_000L),
            lastBreast = breastFeed(now - hour - 15 * 60_000L),
            now = now,
        )
        assertEquals("Last breast: Left · 1 h 15 min ago", line)
    }

    @Test
    fun sideComesFromWhereTheNursingBegan_notWhereItEnded() {
        val line = lastBreastLine(
            context,
            feed = bottle(now - hour),
            lastBreast = breastFeed(
                now - 2 * hour,
                side = BreastSide.BOTH,
                segments = listOf(
                    NursingSegment(BreastSide.RIGHT, now - 2 * hour, now - 2 * hour + 600_000L),
                    NursingSegment(BreastSide.LEFT, now - 2 * hour + 600_000L, now - hour),
                ),
            ),
            now = now,
        )
        assertEquals("Last breast: Right · 2 h ago", line)
    }

    @Test
    fun lastFeedIsTheNursing_headlineAlreadySaysIt() {
        val nursing = breastFeed(now - hour)
        assertNull(lastBreastLine(context, feed = nursing, lastBreast = nursing, now = now))
    }

    @Test
    fun nothingNursedInTheLastDay_saysNothing() {
        assertNull(
            lastBreastLine(
                context,
                feed = bottle(now - hour),
                lastBreast = breastFeed(now - 25 * hour),
                now = now,
            ),
        )
        assertNull(lastBreastLine(context, feed = bottle(now - hour), lastBreast = null, now = now))
    }

    @Test
    fun justUnderADay_stillCounts() {
        val line = lastBreastLine(
            context,
            feed = bottle(now - hour),
            lastBreast = breastFeed(now - 23 * hour - 30 * 60_000L),
            now = now,
        )
        assertEquals("Last breast: Left · 23 h 30 min ago", line)
    }

    @Test
    fun nursingWithoutASide_hasNothingToSay() {
        assertNull(
            lastBreastLine(
                context,
                feed = bottle(now - hour),
                lastBreast = breastFeed(now - 2 * hour, side = null),
                now = now,
            ),
        )
    }

    @Test
    fun noFeedAtAll_saysNothing() {
        assertNull(
            lastBreastLine(context, feed = null, lastBreast = breastFeed(now - hour), now = now),
        )
    }
}
