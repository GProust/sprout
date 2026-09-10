package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.widget.firstNursedSide
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSideTest {

    private fun breastFeed(
        side: BreastSide?,
        segments: List<NursingSegment> = emptyList(),
    ) = FeedingEntity(type = FeedType.BREAST, side = side, startTime = 0L, segments = segments)

    @Test
    fun firstSegmentSide_winsWhenSegmentsExist() {
        // Started left, switched to right, finished back on the left: the next
        // feed should start on the right, so the widget has to say "left".
        val feed = breastFeed(
            side = BreastSide.BOTH,
            segments = listOf(
                NursingSegment(BreastSide.LEFT, 0, 10),
                NursingSegment(BreastSide.RIGHT, 10, 20),
                NursingSegment(BreastSide.LEFT, 20, 30),
            ),
        )
        assertEquals(BreastSide.LEFT, firstNursedSide(feed))
    }

    @Test
    fun firstSegmentSide_isNotTheSideItEndedOn() {
        val feed = breastFeed(
            side = BreastSide.BOTH,
            segments = listOf(
                NursingSegment(BreastSide.RIGHT, 0, 10),
                NursingSegment(BreastSide.LEFT, 10, 20),
            ),
        )
        assertEquals(BreastSide.RIGHT, firstNursedSide(feed))
    }

    @Test
    fun sessionSide_usedWhenNoSegments() {
        assertEquals(BreastSide.RIGHT, firstNursedSide(breastFeed(side = BreastSide.RIGHT)))
        assertEquals(BreastSide.BOTH, firstNursedSide(breastFeed(side = BreastSide.BOTH)))
    }

    @Test
    fun nullSide_whenNeitherSegmentsNorSide() {
        assertEquals(null, firstNursedSide(breastFeed(side = null)))
    }
}
