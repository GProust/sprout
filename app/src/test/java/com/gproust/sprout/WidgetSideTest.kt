package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.widget.lastNursedSide
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSideTest {

    private fun breastFeed(
        side: BreastSide?,
        segments: List<NursingSegment> = emptyList(),
    ) = FeedingEntity(type = FeedType.BREAST, side = side, startTime = 0L, segments = segments)

    @Test
    fun lastSegmentSide_winsWhenSegmentsExist() {
        val feed = breastFeed(
            side = BreastSide.BOTH,
            segments = listOf(
                NursingSegment(BreastSide.LEFT, 0, 10),
                NursingSegment(BreastSide.RIGHT, 10, 20),
                NursingSegment(BreastSide.LEFT, 20, 30),
            ),
        )
        assertEquals(BreastSide.LEFT, lastNursedSide(feed))
    }

    @Test
    fun sessionSide_usedWhenNoSegments() {
        assertEquals(BreastSide.RIGHT, lastNursedSide(breastFeed(side = BreastSide.RIGHT)))
        assertEquals(BreastSide.BOTH, lastNursedSide(breastFeed(side = BreastSide.BOTH)))
    }

    @Test
    fun nullSide_whenNeitherSegmentsNorSide() {
        assertEquals(null, lastNursedSide(breastFeed(side = null)))
    }
}
