package com.gproust.sprout

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.ui.settings.SupportLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The support links are typed-out URLs that ship to users, so a typo is not a
 * cosmetic bug: it sends a parent who wanted to help to whoever registered the
 * misspelling. These assertions pin the hosts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupportLinksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun links_areHttpsAndPointAtTheIntendedHosts() {
        assertEquals("https://github.com/sponsors/gproust", SupportLinks.GITHUB_SPONSORS)
        assertEquals("https://buymeacoffee.com/gproust", SupportLinks.BUY_ME_A_COFFEE)
        for (url in SupportLinks.ALL) {
            assertTrue("$url must be https", url.startsWith("https://"))
        }
    }

    @Test
    fun open_handsTheUrlToABrowserRatherThanFetchingIt() {
        val intent = SupportLinks.viewIntent(SupportLinks.BUY_ME_A_COFFEE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(SupportLinks.BUY_ME_A_COFFEE, intent.data.toString())
    }

    /**
     * The reason the links above are safe: Sprout cannot open a socket at all.
     * A donate screen that fetched anything itself would need `INTERNET`, and
     * that permission's absence is the one privacy claim a user can check for
     * themselves instead of taking on trust.
     */
    @Test
    fun app_declaresNoInternetPermission() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        // Guard the guard: an empty list would make the assertion below pass
        // without having read anything. Sprout does declare permissions
        // (Bluetooth, notifications) — INTERNET is simply never one of them.
        assertTrue("manifest permissions were not read", requested.isNotEmpty())
        assertFalse(
            "Sprout must never declare INTERNET (see PRIVACY.md and BDR-11)",
            requested.contains(android.Manifest.permission.INTERNET),
        )
    }
}
