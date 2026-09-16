package com.gproust.sprout

import com.gproust.sprout.data.sync.SyncSecret
import com.gproust.sprout.data.sync.nearby.HouseholdBeacon
import com.gproust.sprout.data.sync.nearby.L2capPsm
import com.gproust.sprout.data.sync.nearby.NearbyPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // --- the derived service UUID (ADR-0016) -------------------------------

    @Test
    fun `a household recognises its own advertised uuid`() {
        assertEquals(
            HouseholdBeacon.advertUuid(secret, now),
            HouseholdBeacon.advertUuidsToScanFor(secret, now).first(),
        )
    }

    @Test
    fun `another household's advertised uuid means nothing to us`() {
        assertFalse(
            HouseholdBeacon.advertUuidsToScanFor(secret, now)
                .contains(HouseholdBeacon.advertUuid(other, now)),
        )
    }

    @Test
    fun `the advertised uuid changes on its own, so it cannot be followed`() {
        val first = HouseholdBeacon.advertUuid(secret, now)
        val later = HouseholdBeacon.advertUuid(secret, now + HouseholdBeacon.WINDOW_MS)

        assertNotEquals(
            "after the old form is dropped this is the only thing on the air",
            first,
            later,
        )
        // Same window, same value — otherwise two phones could never agree.
        assertEquals(first, HouseholdBeacon.advertUuid(secret, now + HouseholdBeacon.WINDOW_MS / 2))
    }

    @Test
    fun `a phone whose clock lags a little is still scanned for`() {
        val scanning = HouseholdBeacon.advertUuidsToScanFor(secret, now)

        assertEquals(2, scanning.size)
        assertTrue(
            "a scan filter cannot say 'or the one before', so both are listed",
            scanning.contains(HouseholdBeacon.advertUuid(secret, now - HouseholdBeacon.WINDOW_MS)),
        )
        assertFalse(
            "but not forever — an old capture must stop working",
            scanning.contains(HouseholdBeacon.advertUuid(secret, now - 2 * HouseholdBeacon.WINDOW_MS)),
        )
    }

    @Test
    fun `the two forms of one window are different values`() {
        // Both are an HMAC of the same secret over the same window; only the
        // label keeps them apart. Without it the service data would be the first
        // eight bytes of the UUID, which is a fixed relationship an observer
        // could use to tie the two advertisements to one phone.
        val beacon = HouseholdBeacon.value(secret, now)
        val uuid = HouseholdBeacon.advertUuid(secret, now)
        val firstEightOfUuid = ByteArray(8) { i -> ((uuid.mostSignificantBits ushr (56 - 8 * i)) and 0xFFL).toByte() }

        assertFalse(beacon.contentEquals(firstEightOfUuid))
    }

    @Test
    fun `neither form of the advertisement can share a packet with the other`() {
        // Why the transport starts two advertisements rather than one: 3 bytes
        // of flags, 2 + 16 for a service UUID, and 2 + 16 + 8 for service data.
        val both = 3 + (2 + 16) + (2 + 16 + HouseholdBeacon.VALUE_BYTES)

        assertTrue("both forms would be $both bytes", both > 31)
    }

    // --- reaching the channel once we have found it ------------------------

    @Test
    fun `a psm survives the round trip`() {
        for (psm in listOf(1, 128, 0x1234, 32_768, 65_535)) {
            assertEquals(psm, L2capPsm.decode(L2capPsm.encode(psm)))
        }
    }

    @Test
    fun `a psm is big-endian, because the other phone reads it that way`() {
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()), L2capPsm.encode(128))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00), L2capPsm.encode(32_768))
    }

    @Test
    fun `something that is not a psm is refused rather than dialled`() {
        assertNull(L2capPsm.decode(null))
        assertNull("zero is not a channel", L2capPsm.decode(byteArrayOf(0, 0)))
        assertNull(L2capPsm.decode(ByteArray(0)))
        assertNull(L2capPsm.decode(byteArrayOf(1)))
        assertNull(L2capPsm.decode(byteArrayOf(0, 0, 1)))
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
