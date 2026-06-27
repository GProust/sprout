package com.gproust.sprout

import com.gproust.sprout.ui.common.CheckInQuestion.BLEEDING
import com.gproust.sprout.ui.common.CheckInQuestion.BREASTS
import com.gproust.sprout.ui.common.CheckInQuestion.HEALING
import com.gproust.sprout.ui.common.CheckInQuestion.MOOD
import com.gproust.sprout.ui.common.CheckInQuestion.NOTES
import com.gproust.sprout.ui.common.checkInQuestions
import com.gproust.sprout.ui.common.greetingForHour
import com.gproust.sprout.ui.common.isSameDay
import com.gproust.sprout.ui.startup.needsCheckIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInLogicTest {

    private val t = 1_700_000_000_000L
    private val twoDays = 2L * 24 * 60 * 60_000

    @Test
    fun sameDay_detectsCalendarDay() {
        assertTrue(isSameDay(t, t))
        assertFalse(isSameDay(t, t + twoDays))
    }

    @Test
    fun needsCheckIn_trueWhenNeverOrAnotherDay() {
        assertTrue("never checked in", needsCheckIn(null, t))
        assertTrue("checked in two days ago", needsCheckIn(t, t + twoDays))
        assertFalse("already checked in today", needsCheckIn(t, t))
    }

    @Test
    fun checkInQuestions_dependOnCapabilities() {
        // Everyone gets mood + notes; nobody is left out.
        assertEquals(listOf(MOOD, NOTES), checkInQuestions(gaveBirth = false, breastfeeding = false))
        // A birthing parent also gets the postpartum questions.
        assertEquals(
            listOf(MOOD, HEALING, BLEEDING, NOTES),
            checkInQuestions(gaveBirth = true, breastfeeding = false),
        )
        // A non-birthing parent who breastfeeds (e.g. induced lactation) gets the breast question.
        assertEquals(listOf(MOOD, BREASTS, NOTES), checkInQuestions(gaveBirth = false, breastfeeding = true))
        // Both at once (e.g. co-nursing) gets everything.
        assertEquals(
            listOf(MOOD, HEALING, BLEEDING, BREASTS, NOTES),
            checkInQuestions(gaveBirth = true, breastfeeding = true),
        )
    }

    @Test
    fun greeting_byTimeOfDay() {
        assertEquals("Good morning", greetingForHour(8))
        assertEquals("Good afternoon", greetingForHour(14))
        assertEquals("Good evening", greetingForHour(20))
        assertEquals("Hello", greetingForHour(2))
    }
}
