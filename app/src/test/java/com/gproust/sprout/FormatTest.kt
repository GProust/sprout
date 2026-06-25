package com.gproust.sprout

import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatRelative
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun duration_formatsHoursAndMinutes() {
        assertEquals("1h 30m", formatDuration(90L * 60_000))
        assertEquals("45m", formatDuration(45L * 60_000))
        assertEquals("2h", formatDuration(120L * 60_000))
        assertEquals("30s", formatDuration(30L * 1000))
    }

    @Test
    fun relative_formatsElapsedTime() {
        val now = 1_700_000_000_000L
        assertEquals("just now", formatRelative(now, now))
        assertEquals("5m ago", formatRelative(now - 5L * 60_000, now))
        assertEquals("2h ago", formatRelative(now - 2L * 60 * 60_000, now))
        assertEquals("3d ago", formatRelative(now - 3L * 24 * 60 * 60_000, now))
    }

    @Test
    fun age_handlesUnbornAndYoung() {
        val birth = 1_700_000_000_000L
        assertEquals("not born yet", babyAge(birth, birth - 24L * 60 * 60_000))
    }
}
