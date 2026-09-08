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
import java.io.File

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
     * What Sprout asks Android for, read from the manifest Sprout writes.
     *
     * The source file rather than the merged one, because this list is about
     * authorship: a permission here is one a person typed, and adding a sixth
     * should cost a line in this test and a sentence in the pull request.
     * `ACCESS_FINE_LOCATION` is absent by design — `BLUETOOTH_SCAN` is declared
     * `neverForLocation`, which is what lets the nearby exchange find a phone in
     * the room without ever being able to say where that room is.
     */
    @Test
    fun `the manifest Sprout writes asks for five permissions and no others`() {
        assertEquals(SPROUT_PERMISSIONS, declaredPermissions())
    }

    /**
     * And what the *build* ends up asking for, dependencies included.
     *
     * Not pinned exactly. The merged manifest carries whatever every AndroidX
     * artifact declares, so an exact list there fails on a routine version bump
     * while saying nothing about Sprout — the same reason the component pin is
     * scoped to this package. What is worth asserting is the part that would
     * actually cost something: that Sprout's five survive the merge, and that
     * nothing anywhere in the build quietly asks for a permission the app's
     * privacy claim says it does not have.
     *
     * `INTERNET` is the one that matters most and is asserted on its own in
     * `SupportLinksTest`. It is here too, because this is the list a reader
     * checks, and leaving the important one off it would be strange.
     */
    @Test
    fun `nothing in the build asks for a permission Sprout refuses`() {
        val requested = requestedPermissions()

        assertTrue(
            "Sprout's own permissions must survive the manifest merge",
            requested.containsAll(SPROUT_PERMISSIONS),
        )
        assertEquals(
            "a dependency, or a new line in the manifest, asks for something " +
                "Sprout tells its users it never asks for (PRIVACY.md, ADR-0014)",
            emptySet<String>(),
            requested.intersect(REFUSED_PERMISSIONS),
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

    /**
     * The `<uses-permission>` names in Sprout's own manifest.
     *
     * Read as a file, not through the package manager, which only ever sees the
     * merged result. Robolectric runs with the module directory as its working
     * directory; the guard below turns a change to that into a failed assertion
     * rather than a test that passes on an empty list.
     */
    private fun declaredPermissions(): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(
            "expected Sprout's manifest at ${manifest.absolutePath}",
            manifest.isFile,
        )
        // `[^>]` spans newlines, so this matches an element whose attributes are
        // wrapped across lines as well as one written on a single line. It looks
        // only at `<uses-permission`, which is why `<uses-feature>` below the
        // Bluetooth block — and the prose in the comments — are not counted.
        val usesPermission = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"")
        val names = usesPermission.findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no permissions were read from the manifest", names.isNotEmpty())
        return names
    }

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

    private companion object {

        /** Every permission Sprout's own manifest declares. */
        val SPROUT_PERMISSIONS = setOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )

        /**
         * Permissions whose presence would contradict something Sprout says.
         *
         * Not "every dangerous permission" — the ones with a decision behind
         * them. A socket of any kind (ADR-0002, BDR-11); where the phone is,
         * which `neverForLocation` exists to avoid (ADR-0010); the shared
         * storage the app deliberately never writes to (ADR-0007); a foreground
         * service, which the bounded discovery window was chosen instead of
         * (ADR-0010); and the sensors and personal stores an offline tracker has
         * no business reading.
         */
        val REFUSED_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.GET_ACCOUNTS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.QUERY_ALL_PACKAGES",
        )
    }
}
