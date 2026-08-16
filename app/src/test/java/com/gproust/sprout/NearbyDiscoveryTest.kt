package com.gproust.sprout

import com.gproust.sprout.data.sync.SyncSecret
import com.gproust.sprout.data.sync.nearby.HouseholdBeacon
import com.gproust.sprout.data.sync.nearby.NearbyPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How two phones of one household find each other, and how often they are
 * allowed to look (ADR-0010).
 *
 * Both halves are pure logic on purpose — the Bluetooth plumbing around them
 * cannot be tested without radios, so the parts that carry a decision are kept
 * where CI can reach them. What the beacon must not do (be a stable identifier
 * anyone could follow) and what discovery must not do (run continuously) are
 * exactly the things that would be quietly undone by a later change.
 */
class NearbyDiscoveryTest {

    private val secret = SyncSecret(ByteArray(SyncSecret.SIZE_BYTES) { it.toByte() })
    private val other = SyncSecret(ByteArray(SyncSecret.SIZE_BYTES) { (it + 1).toByte() })
    private val now = 1_700_000_000_000L

    // --- the beacon --------------------------------------------------------

    @Test
    fun `a household recognises its own beacon`() {
        val advertised = HouseholdBeacon.value(secret, now)

        assertTrue(HouseholdBeacon.matches(advertised, secret, now))
        assertEquals(HouseholdBeacon.VALUE_BYTES, advertised.size)
    }

    @Test
    fun `another household's beacon means nothing to us`() {
        val theirs = HouseholdBeacon.value(other, now)

        assertFalse(HouseholdBeacon.matches(theirs, secret, now))
    }

    @Test
    fun `the beacon changes on its own, so it cannot be followed`() {
        val first = HouseholdBeacon.value(secret, now)
        val later = HouseholdBeacon.value(secret, now + HouseholdBeacon.WINDOW_MS)

        assertFalse(
            "a fixed value would be a tracker attached to a family",
            first.contentEquals(later),
        )
        // Same window, same value — otherwise two phones could never agree.
        assertArrayEquals(first, HouseholdBeacon.value(secret, now + HouseholdBeacon.WINDOW_MS / 2))
    }

    @Test
    fun `a phone whose clock lags a little is still recognised`() {
        val lagging = HouseholdBeacon.value(secret, now - HouseholdBeacon.WINDOW_MS)

        assertTrue(
            "the previous window is accepted, so a boundary or a few minutes of drift is harmless",
            HouseholdBeacon.matches(lagging, secret, now),
        )
        assertFalse(
            "but not forever — an old capture must stop working",
            HouseholdBeacon.matches(lagging, secret, now + 2 * HouseholdBeacon.WINDOW_MS),
        )
    }

    @Test
    fun `something that is not a beacon at all is refused`() {
        assertFalse(HouseholdBeacon.matches(ByteArray(0), secret, now))
        assertFalse(HouseholdBeacon.matches(ByteArray(4), secret, now))
        assertFalse(HouseholdBeacon.matches(ByteArray(64), secret, now))
    }

    @Test
    fun `the advertisement fits in a BLE packet`() {
        // 31 bytes total: 3 for flags, 2 of header plus 16 for a 128-bit service
        // UUID, and the value. Going over would make the advertisement silently
        // undeliverable rather than fail loudly.
        val advertisement = 3 + 2 + 16 + HouseholdBeacon.VALUE_BYTES

        assertTrue("advertisement is $advertisement bytes", advertisement <= 31)
    }

    // --- when we are allowed to look ---------------------------------------

    @Test
    fun `the first launch may look`() {
        assertTrue(NearbyPolicy.mayOpenWindow(lastOpenedAt = null, now = now))
    }

    @Test
    fun `opening the app again straight away does not light up the radio`() {
        assertFalse(
            "Sprout gets opened for fifteen seconds at a time, many times a day",
            NearbyPolicy.mayOpenWindow(lastOpenedAt = now, now = now + 30_000),
        )
        assertTrue(
            NearbyPolicy.mayOpenWindow(lastOpenedAt = now, now = now + NearbyPolicy.MIN_INTERVAL_MS),
        )
    }

    @Test
    fun `asking explicitly is never refused`() {
        assertTrue(
            "a user who taps Sync now and is silently ignored cannot tell that from a failure",
            NearbyPolicy.mayOpenWindow(lastOpenedAt = now, now = now + 1_000, userAsked = true),
        )
    }

    @Test
    fun `a clock that jumps backwards does not lock discovery out`() {
        assertTrue(
            "a time zone change or a reboot must not disable syncing until the clock catches up",
            NearbyPolicy.mayOpenWindow(lastOpenedAt = now, now = now - 2 * 60 * 60 * 1000),
        )
    }

    @Test
    fun `a window is short`() {
        assertEquals(now + NearbyPolicy.WINDOW_MS, NearbyPolicy.windowEndsAt(now))
        assertTrue("seconds of radio per launch, not minutes", NearbyPolicy.WINDOW_MS <= 15_000)
    }
}
