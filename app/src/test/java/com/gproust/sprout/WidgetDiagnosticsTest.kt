package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.widget.WidgetDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The diagnostics log is the only record of what the widget did, so it has to
// round-trip entries that contain newlines and stack traces without smearing
// them into each other.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class WidgetDiagnosticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() = WidgetDiagnostics.clear(context)

    @Test
    fun recordsEntriesInOrder() {
        WidgetDiagnostics.record(context, "first")
        WidgetDiagnostics.record(context, "second")

        val entries = WidgetDiagnostics.entries(context)
        assertEquals(2, entries.size)
        assertTrue(entries[0].endsWith("first"))
        assertTrue(entries[1].endsWith("second"))
    }

    @Test
    fun multiLineErrorStaysOneEntry() {
        WidgetDiagnostics.record(context, "boom", IllegalStateException("a\nb"))

        val entries = WidgetDiagnostics.entries(context)
        assertEquals(1, entries.size)
        assertTrue(entries[0].contains("boom"))
        assertTrue(entries[0].contains("IllegalStateException"))
    }

    @Test
    fun keepsOnlyTheMostRecentEntries() {
        repeat(60) { WidgetDiagnostics.record(context, "entry $it") }

        val entries = WidgetDiagnostics.entries(context)
        assertEquals(40, entries.size)
        assertTrue(entries.last().endsWith("entry 59"))
        // The oldest ones fall off rather than growing the file for ever.
        assertTrue(entries.none { it.endsWith("entry 0") })
    }

    @Test
    fun clearEmptiesTheLog() {
        WidgetDiagnostics.record(context, "something")
        WidgetDiagnostics.clear(context)

        assertEquals(emptyList<String>(), WidgetDiagnostics.entries(context))
    }
}
