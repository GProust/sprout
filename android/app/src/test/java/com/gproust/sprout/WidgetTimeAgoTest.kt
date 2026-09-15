package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.ui.common.formatDateTime
import com.gproust.sprout.widget.widgetTimeAgo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric supplies a real Context so the localized (default = English)
// resources resolve in plain JVM unit tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class WidgetTimeAgoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_750_000_000_000L

    @Test
    fun underAMinute_isJustNow() {
        assertEquals("just now", widgetTimeAgo(context, now - 30_000L, now))
    }

    @Test
    fun underAnHour_showsMinutes() {
        assertEquals("5 min ago", widgetTimeAgo(context, now - 5 * min, now))
        assertEquals("59 min ago", widgetTimeAgo(context, now - 59 * min, now))
    }

    @Test
    fun wholeHours_skipTheMinutes() {
        assertEquals("2 h ago", widgetTimeAgo(context, now - 2 * hour, now))
    }

    @Test
    fun hoursAndMinutes_showBothUnits() {
        assertEquals("2 h 15 min ago", widgetTimeAgo(context, now - 2 * hour - 15 * min, now))
        assertEquals("26 h 30 min ago", widgetTimeAgo(context, now - 26 * hour - 30 * min, now))
    }

    @Test
    fun overTwoDays_fallsBackToDateTime() {
        val old = now - 50 * hour
        assertEquals(formatDateTime(context, old), widgetTimeAgo(context, old, now))
    }
}
