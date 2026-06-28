package com.gproust.sprout

import com.gproust.sprout.notifications.feedingReminderOverdue
import com.gproust.sprout.notifications.feedingReminderTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedingReminderLogicTest {

    @Test
    fun trigger_isLastFeedPlusInterval() {
        val last = 1_000_000L
        assertEquals(last + 180L * 60_000L, feedingReminderTrigger(last, 180))
    }

    @Test
    fun overdue_isFalseBeforeTheInterval() {
        val last = 0L
        assertFalse(feedingReminderOverdue(last, 119L * 60_000L, 120))
    }

    @Test
    fun overdue_isTrueAtAndAfterTheInterval() {
        val last = 0L
        assertTrue(feedingReminderOverdue(last, 120L * 60_000L, 120))
        assertTrue(feedingReminderOverdue(last, 500L * 60_000L, 120))
    }
}
