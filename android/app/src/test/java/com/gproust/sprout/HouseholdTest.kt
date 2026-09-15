package com.gproust.sprout

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.sync.HouseholdDevices
import com.gproust.sprout.data.sync.Pairing
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SecretVault
import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A household is a group, not a pair (ADR-0009): several phones sharing one
 * secret, and one way to remove any of them — change the secret.
 *
 * The part worth pinning down is what removal costs, because it is the part
 * that surprises: rotating locks out *everyone* until they are re-invited, and
 * re-invited phones must come back as the same pairing rather than as a fresh
 * one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HouseholdTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_700_000_000_000L

    private class FakeVault : SecretVault {
        override fun wrap(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        override fun unwrap(blob: String): ByteArray = Base64.decode(blob, Base64.NO_WRAP)
    }

    private lateinit var store: PairingStore
    private lateinit var devices: HouseholdDevices

    @Before
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = PairingStore(context, FakeVault())
        devices = HouseholdDevices(context)
        store.save(
            Pairing(
                householdId = "household-1",
                secret = SyncSecret.random(),
                pairedAt = now,
                shareStash = true,
            ),
        )
    }

    @Test
    fun `phones are remembered as they introduce themselves, newest first`() {
        devices.seen("device-a", "Aurélien", now)
        devices.seen("device-b", "Mamie", now + 1_000)

        assertEquals(listOf("Mamie", "Aurélien"), devices.all().map { it.name })
    }

    @Test
    fun `a phone that sends again is the same phone, not a second one`() {
        devices.seen("device-a", "Aurélien", now)
        devices.seen("device-a", "Aurélien", now + 5_000)

        assertEquals(1, devices.all().size)
        assertEquals(now + 5_000, devices.all().single().lastSeen)
    }

    @Test
    fun `a replica with no name does not erase the name we already had`() {
        devices.seen("device-a", "Mamie", now)

        // What a replica from a Sprout older than ADR-0009 looks like.
        devices.seen("device-a", "", now + 1_000)

        assertEquals("Mamie", devices.all().single().name)
    }

    @Test
    fun `rotating the secret keeps the household and changes the code`() {
        val before = store.current()!!

        val after = store.rotateSecret(now + 10_000)!!

        assertEquals("still the same household", before.householdId, after.householdId)
        assertNotEquals(
            "the code changing is how everyone sees they must be re-invited",
            before.verificationCode(),
            after.verificationCode(),
        )
        assertArrayEquals(after.secret.bytes, store.current()!!.secret.bytes)
        assertEquals("the switch survives a rotation", true, store.current()!!.shareStash)
    }

    @Test
    fun `a replica sealed before the rotation can no longer be opened`() {
        val before = store.current()!!
        val sealed = SyncCrypto.seal("a feed".toByteArray(), before.secret)

        val after = store.rotateSecret(now + 10_000)!!

        // Which is the point: the removed phone's replicas stop being readable,
        // and so do everyone else's until they are re-invited.
        val failure = runCatching { SyncCrypto.open(sealed, after.secret) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `forgetting a phone from the list is not what removes it`() {
        devices.seen("device-a", "Mamie", now)
        val secretBefore = store.current()!!.secret.bytes

        devices.forget("device-a")

        assertTrue(devices.all().isEmpty())
        assertArrayEquals(
            "the list is a courtesy — the secret is what grants access",
            secretBefore,
            store.current()!!.secret.bytes,
        )
    }

    @Test
    fun `leaving the household forgets the other phones too`() {
        devices.seen("device-a", "Mamie", now)

        store.unpair()
        devices.clear()

        assertNull(store.current())
        assertTrue(devices.all().isEmpty())
    }
}
