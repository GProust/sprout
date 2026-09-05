package com.gproust.sprout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.sync.DeviceIdentity
import com.gproust.sprout.widget.WidgetDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * What crosses to a new phone (ADR-0011).
 *
 * The two files are the whole of that decision, and neither of them is exercised
 * by anything else: Android reads them, we never do, and getting one wrong is
 * invisible until a parent has already restored onto a handset that thinks it is
 * the one they left behind. So the rules are read back here and checked against
 * the code that names the files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val deviceFile = "${DeviceIdentity.PREFS}.xml"
    private val diagnosticsFile = "${WidgetDiagnostics.PREFS}.xml"

    @Test
    fun `the pre-31 rules hold back this handset's own preferences`() {
        assertEquals(
            mapOf("" to setOf(deviceFile, diagnosticsFile)),
            excludedSharedPrefs(R.xml.backup_rules),
        )
    }

    @Test
    fun `so do the rules for 31 and up, on both routes`() {
        // A section left out means "everything", so the transfer needs saying as
        // explicitly as the backup: a cable copy during setup lands on a second
        // handset just as surely as a restore does.
        assertEquals(
            mapOf(
                "cloud-backup" to setOf(deviceFile, diagnosticsFile),
                "device-transfer" to setOf(deviceFile, diagnosticsFile),
            ),
            excludedSharedPrefs(R.xml.data_extraction_rules),
        )
    }

    @Test
    fun `nothing is included by name, so the record itself still travels`() {
        // The point of the exclusions is to hold back two files, not to smuggle
        // in an allow-list: a stray <include> would silently stop the database
        // and every setting around it from reaching the new phone.
        assertEquals(emptyList<String>(), tagNames(R.xml.backup_rules).filter { it == "include" })
        assertEquals(
            emptyList<String>(),
            tagNames(R.xml.data_extraction_rules).filter { it == "include" },
        )
    }

    /** Excluded `sharedpref` paths per section — `""` for a file with no sections. */
    private fun excludedSharedPrefs(resId: Int): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        var section = ""
        forEachStartTag(resId) { parser ->
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "exclude" -> if (parser.getAttributeValue(null, "domain") == "sharedpref") {
                    val path = parser.getAttributeValue(null, "path").orEmpty()
                    out.getOrPut(section) { mutableSetOf() }.add(path)
                }
            }
        }
        return out
    }

    private fun tagNames(resId: Int): List<String> =
        buildList<String> { forEachStartTag(resId) { add(it.name) } }

    private fun forEachStartTag(resId: Int, onTag: (XmlPullParser) -> Unit) {
        val parser = context.resources.getXml(resId)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) onTag(parser)
                event = parser.next()
            }
        } finally {
            parser.close()
        }
    }
}
