package com.gproust.sprout

import android.content.Intent
import android.net.Uri
import com.gproust.sprout.data.sync.SyncFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which intents are a Sprout file, and which are the app talking to itself.
 *
 * Worth its own test because the two used to be told apart by the action alone,
 * and the widget sent `ACTION_VIEW` — the same action the manifest filter uses
 * for an invitation or a replica. A tap meant for Feeding could land on the
 * sharing screen and be fed to the parser, which then said the file was not a
 * Sprout file. Nothing here needs a radio, a database or a screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingIntentTest {

    private val file: Uri = Uri.parse("content://com.gproust.sprout.sync/sync/replica.sprout")

    @Test
    fun `a tap on the widget is not a file`() {
        val widgetTap = Intent(MainActivity.ACTION_OPEN_ROUTE)
            .putExtra(MainActivity.EXTRA_ROUTE, "feeding")

        assertNull(SyncFiles.incomingUri(widgetTap))
    }

    @Test
    fun `a view intent with nothing to read is not a file either`() {
        assertNull(SyncFiles.incomingUri(Intent(Intent.ACTION_VIEW)))
    }

    @Test
    fun `only a uri we could actually open counts`() {
        // A deep link, a web address, anything an activity may be handed: none
        // of it is something the sync screen should try to merge.
        assertNull(SyncFiles.incomingUri(Intent(Intent.ACTION_VIEW, Uri.parse("sprout://open/feeding"))))
        assertNull(SyncFiles.incomingUri(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/x"))))
    }

    @Test
    fun `a file opened from another app still arrives`() {
        assertEquals(file, SyncFiles.incomingUri(Intent(Intent.ACTION_VIEW, file)))
    }

    @Test
    fun `a file sent through the share sheet still arrives`() {
        val shared = Intent(Intent.ACTION_SEND)
            .setType(SyncFiles.MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, file)

        assertEquals(file, SyncFiles.incomingUri(shared))
    }

    @Test
    fun `no intent at all is not a file`() {
        assertNull(SyncFiles.incomingUri(null))
    }
}
