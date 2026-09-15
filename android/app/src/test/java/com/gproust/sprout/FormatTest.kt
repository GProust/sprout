package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatClock
import com.gproust.sprout.ui.common.formatDuration
import com.gproust.sprout.ui.common.formatRelative
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real Context so the localized (default = English)
// resources resolve in plain JVM unit tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class FormatTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun duration_formatsHoursAndMinutes() {
        assertEquals("1h 30m", formatDuration(context, 90L * 60_000))
        assertEquals("45m", formatDuration(context, 45L * 60_000))
        assertEquals("2h", formatDuration(context, 120L * 60_000))
        assertEquals("30s", formatDuration(context, 30L * 1000))
    }

    @Test
    fun clock_formatsRunningTimer() {
        assertEquals("0:00", formatClock(0L))
        assertEquals("0:05", formatClock(5L * 1000))
        assertEquals("1:30", formatClock(90L * 1000))
        assertEquals("12:00", formatClock(12L * 60_000))
        assertEquals("1:05:09", formatClock(60L * 60_000 + 5L * 60_000 + 9L * 1000))
    }

    @Test
    fun relative_formatsElapsedTime() {
        val now = 1_700_000_000_000L
        assertEquals("just now", formatRelative(context, now, now))
        assertEquals("5m ago", formatRelative(context, now - 5L * 60_000, now))
        assertEquals("2h ago", formatRelative(context, now - 2L * 60 * 60_000, now))
        assertEquals("3d ago", formatRelative(context, now - 3L * 24 * 60 * 60_000, now))
    }

    @Test
    fun age_handlesUnbornAndYoung() {
        val birth = 1_700_000_000_000L
        assertEquals("not born yet", babyAge(context, birth, birth - 24L * 60 * 60_000))
    }
}
