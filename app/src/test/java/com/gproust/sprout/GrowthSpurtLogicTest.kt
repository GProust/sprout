package com.gproust.sprout

import com.gproust.sprout.notifications.GROWTH_SPURT_NOTIFY_AT
import com.gproust.sprout.notifications.nextGrowthSpurtTrigger
import com.gproust.sprout.ui.common.GROWTH_SPURT_WINDOWS
import com.gproust.sprout.ui.common.ageInDays
import com.gproust.sprout.ui.common.currentGrowthSpurt
import com.gproust.sprout.ui.common.nextGrowthSpurt
import com.gproust.sprout.ui.common.upcomingGrowthSpurt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GrowthSpurtLogicTest {

    private val zone = ZoneOffset.UTC
    private val birth = LocalDate.of(2026, 1, 1)
    private val birthMillis = birth.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun onDay(day: Long, hour: Int = 12): Long =
        birth.plusDays(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun windows_areOrderedAndNonOverlapping() {
        GROWTH_SPURT_WINDOWS.zipWithNext().forEach { (a, b) ->
            assertTrue(a.endDay < b.startDay)
        }
        GROWTH_SPURT_WINDOWS.forEach {
            assertTrue(it.startDay <= it.endDay)
            assertTrue((it.approxWeeks != null) != (it.approxMonths != null))
        }
    }

    @Test
    fun ageInDays_countsCalendarDays() {
        assertEquals(0L, ageInDays(birthMillis, onDay(0, hour = 23), zone))
        assertEquals(7L, ageInDays(birthMillis, onDay(7, hour = 0), zone))
    }

    @Test
    fun currentSpurt_coversWindowBoundsInclusive() {
        assertNull(currentGrowthSpurt(6))
        assertEquals(7, currentGrowthSpurt(7)?.startDay)
        assertEquals(7, currentGrowthSpurt(10)?.startDay)
        assertNull(currentGrowthSpurt(11))
        assertEquals(3, currentGrowthSpurt(90)?.approxMonths)
    }

    @Test
    fun nextSpurt_isFirstWindowNotYetStarted() {
        assertEquals(7, nextGrowthSpurt(0)?.startDay)
        // Mid-window, "next" is the following one.
        assertEquals(14, nextGrowthSpurt(8)?.startDay)
        assertNull(nextGrowthSpurt(280))
    }

    @Test
    fun upcomingSpurt_onlyWithinHeadsUpDays() {
        assertNull(upcomingGrowthSpurt(3))
        assertEquals(7, upcomingGrowthSpurt(4)?.startDay)
        assertEquals(7, upcomingGrowthSpurt(6)?.startDay)
        assertNull(upcomingGrowthSpurt(281))
    }

    @Test
    fun trigger_isNineAmOnFirstDayOfNextWindow() {
        val expected = birth.plusDays(7)
            .atTime(GROWTH_SPURT_NOTIFY_AT).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, nextGrowthSpurtTrigger(birthMillis, onDay(0), zone))
    }

    @Test
    fun trigger_skipsToFollowingWindowOnceFired() {
        val day7At9 = birth.plusDays(7)
            .atTime(GROWTH_SPURT_NOTIFY_AT).atZone(zone).toInstant().toEpochMilli()
        val expected = birth.plusDays(14)
            .atTime(GROWTH_SPURT_NOTIFY_AT).atZone(zone).toInstant().toEpochMilli()
        // Strictly after "now": the alarm that just fired isn't re-armed.
        assertEquals(expected, nextGrowthSpurtTrigger(birthMillis, day7At9, zone))
    }

    @Test
    fun trigger_isNullPastTheLastWindow() {
        assertNull(nextGrowthSpurtTrigger(birthMillis, onDay(300), zone))
    }
}
