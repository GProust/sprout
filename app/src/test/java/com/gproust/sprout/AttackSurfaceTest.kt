package com.gproust.sprout

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Every way into Sprout from outside the app, written down (ADR-0014).
 *
 * Sprout's privacy claim is mostly a claim about *absence* — no network, no
 * account, no server, one database nothing else can reach. Absence is not
 * something a feature test notices going missing: a permission added to the
 * manifest, a component quietly exported, a `<files-path>` added to the
 * FileProvider so one screen can share one more thing, all of them compile,
 * pass every other test, and ship.
 *
 * So the surface is pinned here as a list, and the list is exact on purpose.
 * Widening it is allowed — it just cannot happen *by accident*: this test fails,
 * and whoever is widening it says so in the diff and covers the new door.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttackSurfaceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val packageName: String get() = context.packageName

    /**
     * What Sprout asks Android for.
     *
     * `INTERNET` is the one that matters most and has its own assertion in
     * `SupportLinksTest`; this is the rest of the sentence. `ACCESS_FINE_LOCATION`
     * is absent by design too — `BLUETOOTH_SCAN` is declared `neverForLocation`,
     * which is what lets the nearby exchange find a phone in the room without
     * ever being able to say where that room is.
     */
    @Test
    fun `the app asks for five permissions and no others`() {
        assertEquals(
            setOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            ),
            requestedPermissions(),
        )
    }

    /**
     * Which of Sprout's own components any other app on the phone can reach.
     *
     * Two, and both have to be. `MainActivity` is exported because a launcher
     * has to start it and because an invitation arrives as a file from whichever
     * app the other parent used. `SproutWidgetReceiver` is exported because the
     * platform's own `APPWIDGET_UPDATE` broadcast is how a widget is told to
     * draw. The three reminder receivers are not, and a fourth one should not be
     * either.
     */
    @Test
    fun `only the launcher activity and the widget receiver are exported`() {
        assertEquals(
            setOf(
                "com.gproust.sprout.MainActivity",
                "com.gproust.sprout.widget.SproutWidgetReceiver",
            ),
            ourComponents().filter { it.exported }.map { it.name }.toSet(),
        )
    }

    /**
     * Guard the guard.
     *
     * The assertion above is about a *subset* of the components, so it would
     * also pass if the manifest were read as empty — and it would keep passing
     * if a new receiver appeared, which is exactly the change worth seeing. So
     * the whole list is pinned too.
     */
    @Test
    fun `the manifest declares these components and no others`() {
        assertEquals(
            setOf(
                "com.gproust.sprout.MainActivity",
                "com.gproust.sprout.notifications.ReminderReceiver",
                "com.gproust.sprout.notifications.FeedingReminderReceiver",
                "com.gproust.sprout.notifications.GrowthSpurtReminderReceiver",
                "com.gproust.sprout.widget.SproutWidgetReceiver",
            ),
            ourComponents().map { it.name }.toSet(),
        )
    }

    /**
     * The one content provider, and the terms it hands a file out on.
     *
     * Not exported, so nothing can query it uninvited; `grantUriPermissions`, so
     * the only way in is a grant the user creates by picking an app in the share
     * sheet, scoped to one URI and revoked when the grant lapses.
     */
    @Test
    fun `the file provider is closed and grants access one URI at a time`() {
        val provider = providers().single { it.authority == "$packageName.sync" }

        assertFalse("the sync FileProvider must not be exported", provider.exported)
        assertTrue("the sync FileProvider must grant per-URI access", provider.grantUriPermissions)
    }

    /**
     * No provider at all is exported — ours or a dependency's.
     *
     * A library that starts shipping an exported provider is new surface on the
     * phone whether or not Sprout asked for it, and this is the cheapest place
     * to find out. If this fails after a dependency bump, that is the test
     * working.
     */
    @Test
    fun `no content provider is exported`() {
        val exported = providers().filter { it.exported }.map { it.authority }
        assertEquals(emptyList<String>(), exported)
    }

    /**
     * What the FileProvider is allowed to reach.
     *
     * Two staging directories inside the cache, and nothing else. The paths that
     * must never appear here are the ones that would put the database, the
     * preferences or the whole sandbox behind a `content://` URI: `<root-path>`,
     * `<files-path>`, and any of the `external` family.
     */
    @Test
    fun `the provider can only reach the two staging directories`() {
        assertEquals(
            setOf("cache-path:sync:sync/", "cache-path:reports:reports/"),
            filePathEntries(),
        )
    }

    // --- reading the manifest back ------------------------------------------

    private fun requestedPermissions(): Set<String> {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertTrue("manifest permissions were not read", requested.isNotEmpty())
        return requested
    }

    private data class Component(val name: String, val exported: Boolean)

    /**
     * Sprout's own activities and receivers.
     *
     * Scoped to this package deliberately. A dependency's components come and go
     * with its version — Compose's debug tooling contributes a preview activity,
     * the profile installer an exported receiver — and pinning those would turn
     * every routine bump into a failing build without saying anything about the
     * app's own doors. Providers are checked separately, and by authority: the
     * one Sprout declares lives in AndroidX's package, not ours.
     */
    private fun ourComponents(): List<Component> {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES,
        )
        val activities = info.activities?.toList().orEmpty()
        val receivers = info.receivers?.toList().orEmpty()
        val services = info.services?.toList().orEmpty()
        val all = (activities + receivers + services).map { Component(it.name, it.exported) }
        val ours = all.filter { it.name.startsWith("com.gproust.sprout.") }
        assertTrue("manifest components were not read", ours.isNotEmpty())
        return ours
    }

    private fun providers(): List<ProviderInfo> {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_PROVIDERS,
        )
        val providers = info.providers?.toList().orEmpty()
        assertTrue("manifest providers were not read", providers.isNotEmpty())
        return providers
    }

    /** Each `<*-path>` in `@xml/file_paths` as `tag:name:path`. */
    private fun filePathEntries(): Set<String> {
        val entries = mutableSetOf<String>()
        val parser = context.resources.getXml(R.xml.file_paths)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name != "paths") {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    val path = parser.getAttributeValue(null, "path").orEmpty()
                    entries.add("${parser.name}:$name:$path")
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        assertTrue("file_paths.xml was not read", entries.isNotEmpty())
        return entries
    }
}
