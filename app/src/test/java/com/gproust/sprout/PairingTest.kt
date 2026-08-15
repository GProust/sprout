package com.gproust.sprout

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.sync.Pairing
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SecretVault
import com.gproust.sprout.data.sync.SyncInvitation
import com.gproust.sprout.data.sync.SyncInvitationCodec
import com.gproust.sprout.data.sync.SyncInvitationException
import com.gproust.sprout.data.sync.SyncSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pairing: the invitation that carries the secret, and what this phone
 * remembers afterwards (ADR-0008).
 *
 * The invitation is the one moment the secret is out in the open, travelling
 * through whatever channel the parents chose — so the rules that narrow that
 * window (it expires, it is refused when damaged, it is refused when newer than
 * this app) are the ones worth pinning down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_700_000_000_000L

    /**
     * Stands in for the Android Keystore, which is not available under
     * Robolectric. What is being tested here is that the store round-trips a
     * secret through *a* vault and never keeps it in the clear itself — not the
     * Keystore's own encryption.
     */
    private class FakeVault : SecretVault {
        override fun wrap(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        override fun unwrap(blob: String): ByteArray = Base64.decode(blob, Base64.NO_WRAP)
    }

    private lateinit var store: PairingStore

    @Before
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        store = PairingStore(context, FakeVault())
    }

    private fun pairing(secret: SyncSecret = SyncSecret.random()) = Pairing(
        householdId = "household-1",
        secret = secret,
        pairedAt = now,
        shareStash = true,
    )

    @Test
    fun `an invitation survives the trip and pairs the other phone`() {
        val invitation = SyncInvitation(
            householdId = "household-1",
            secret = SyncSecret.random(),
            createdAt = now,
            fromName = "Alex",
        )

        val decoded = SyncInvitationCodec.decode(SyncInvitationCodec.encode(invitation), now + 1_000)

        assertEquals(invitation.householdId, decoded.householdId)
        assertArrayEquals(invitation.secret.bytes, decoded.secret.bytes)
        assertEquals("Alex", decoded.fromName)
        assertEquals(
            "both phones end up showing the same code",
            invitation.secret.verificationCode(),
            decoded.secret.verificationCode(),
        )
    }

    @Test
    fun `an invitation left in an old conversation pairs nobody`() {
        val stale = SyncInvitation("household-1", SyncSecret.random(), createdAt = now, fromName = "Alex")
        val bytes = SyncInvitationCodec.encode(stale)

        assertThrows(SyncInvitationException.Expired::class.java) {
            SyncInvitationCodec.decode(bytes, now + SyncInvitation.VALID_FOR_MS + 1)
        }
        // Still good just inside the window.
        SyncInvitationCodec.decode(bytes, now + SyncInvitation.VALID_FOR_MS - 1)
    }

    @Test
    fun `something that is not an invitation is refused`() {
        assertThrows(SyncInvitationException.Unreadable::class.java) {
            SyncInvitationCodec.decode("a holiday photo".toByteArray(), now)
        }
        assertThrows(SyncInvitationException.Unreadable::class.java) {
            SyncInvitationCodec.decode("""{"formatVersion":1}""".toByteArray(), now)
        }
    }

    @Test
    fun `an invitation from a newer Sprout is refused rather than guessed at`() {
        val bytes = """{"formatVersion":99,"householdId":"h","secret":"x","createdAt":1,"fromName":"A"}"""
            .toByteArray()

        val failure = assertThrows(SyncInvitationException.TooNew::class.java) {
            SyncInvitationCodec.decode(bytes, now)
        }
        assertEquals(99, failure.formatVersion)
    }

    @Test
    fun `the pairing is remembered, and the secret is never stored in the clear`() {
        val secret = SyncSecret.random()
        store.save(pairing(secret))

        val restored = store.current()!!
        assertEquals("household-1", restored.householdId)
        assertArrayEquals(secret.bytes, restored.secret.bytes)
        assertEquals(now, restored.pairedAt)

        val raw = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.values
            .joinToString(" ") { it.toString() }
        assertFalse(
            "the raw secret must not be readable in preferences",
            raw.contains(String(secret.bytes, Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun `the stash switch is remembered on its own`() {
        store.save(pairing())
        assertTrue("sharing the stash is the default", store.current()!!.shareStash)

        store.setShareStash(false)

        assertFalse(store.current()!!.shareStash)
        assertTrue("turning it off does not unpair", store.isPaired())
    }

    @Test
    fun `unpairing forgets the partner and the first-merge question`() {
        store.save(pairing())
        store.markFirstMergeDone()

        store.unpair()

        assertNull(store.current())
        assertFalse(store.isPaired())
        assertFalse("a later pairing gets asked about histories again", store.firstMergeDone())
    }

    @Test
    fun `a pairing that cannot be unwrapped reads as not paired, rather than crashing`() {
        store.save(pairing())

        // What a restored backup or a wiped Keystore looks like from here.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("sync_secret", "not base64 at all !!").commit()

        assertNull(PairingStore(context, FakeVault()).current())
    }
}
