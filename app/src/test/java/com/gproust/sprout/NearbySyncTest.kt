package com.gproust.sprout

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.sync.HouseholdDevices
import com.gproust.sprout.data.sync.Pairing
import com.gproust.sprout.data.sync.PairingStore
import com.gproust.sprout.data.sync.SecretVault
import com.gproust.sprout.data.sync.SyncCrypto
import com.gproust.sprout.data.sync.SyncEngine
import com.gproust.sprout.data.sync.SyncPayloadCodec
import com.gproust.sprout.data.sync.SyncSecret
import com.gproust.sprout.data.sync.nearby.HouseholdBeacon
import com.gproust.sprout.data.sync.nearby.NearbyPolicy
import com.gproust.sprout.data.sync.nearby.NearbyResult
import com.gproust.sprout.data.sync.nearby.NearbySync
import com.gproust.sprout.data.sync.nearby.NearbyTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One discovery window, end to end — minus the radio (ADR-0010).
 *
 * The transport is faked, which is the whole reason it is an interface: what is
 * worth testing is that a window is opened only when the policy allows, that
 * what goes out is sealed, that what comes back is merged, and that the first
 * merge's question is not answered behind the parent's back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearbySyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val household = "household-1"
    private val secret = SyncSecret(ByteArray(SyncSecret.SIZE_BYTES) { it.toByte() })
    private var clock = 1_700_000_000_000L

    private class FakeVault : SecretVault {
        override fun wrap(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        override fun unwrap(blob: String): ByteArray = Base64.decode(blob, Base64.NO_WRAP)
    }

    /** A transport that hands back whatever replicas the test lined up. */
    private class FakeTransport(
        private val replies: List<ByteArray> = emptyList(),
        private val unavailable: NearbyTransport.Unavailable? = null,
        private val refuse: Boolean = false,
    ) : NearbyTransport {
        var windows = 0
        var advertised: ByteArray? = null
        var sent: ByteArray? = null

        /** The rule the caller handed down for recognising a household beacon. */
        var isOurs: ((ByteArray) -> Boolean)? = null

        override fun unavailableReason() = unavailable

        override suspend fun exchange(
            beacon: ByteArray,
            isOurs: (ByteArray) -> Boolean,
            mine: ByteArray,
            windowMs: Long,
        ): List<ByteArray> {
            windows++
            advertised = beacon
            sent = mine
            this.isOurs = isOurs
            if (refuse) throw NearbyTransport.RadioRefused("advertising refused (1)")
            return replies
        }
    }

    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private lateinit var engine: SyncEngine
    private lateinit var store: PairingStore
    private lateinit var devices: HouseholdDevices

    @Before
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db, now = { clock })
        engine = SyncEngine(db, now = { clock })
        store = PairingStore(context, FakeVault())
        devices = HouseholdDevices(context)
        store.save(Pairing(household, secret, pairedAt = clock, shareStash = true))
    }

    @After
    fun tearDown() = db.close()

    private fun sync(transport: NearbyTransport) = NearbySync(
        context = context,
        engine = engine,
        pairingStore = store,
        householdDevices = devices,
        transport = transport,
        deviceName = { "Alex" },
        now = { clock },
    )

    /** A replica as the other phone would seal it. */
    private fun replicaFrom(name: String, deviceId: String): ByteArray = runBlocking {
        val other = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val theirRepo = SproutRepository(other, now = { clock })
            theirRepo.saveParentProfile(ParentProfileEntity(name = name))
            theirRepo.addBaby("Léa", birthDate = 1_699_000_000_000)
            theirRepo.addFeeding(FeedingEntity(type = FeedType.BOTTLE, amountMl = 90, startTime = clock))
            val payload = SyncEngine(other, now = { clock })
                .buildPayload(household, deviceId, name, includePumping = true)
            SyncCrypto.seal(SyncPayloadCodec.encode(payload), secret)
        } finally {
            other.close()
        }
    }

    @Test
    fun `a window merges what the other phone sent, and remembers who sent it`() = runBlocking {
        val transport = FakeTransport(listOf(replicaFrom("Mamie", "device-mamie")))

        val result = sync(transport).run(userAsked = true)

        assertTrue(result is NearbyResult.Synced)
        assertEquals("a baby and a feed", 2, (result as NearbyResult.Synced).summary.added)
        assertEquals(1, result.phones)
        assertEquals("Mamie", devices.all().single().name)
    }

    @Test
    fun `what leaves the phone is sealed, and the beacon is not the household id`() = runBlocking {
        val transport = FakeTransport()

        sync(transport).run(userAsked = true)

        val sent = transport.sent!!
        assertTrue("a replica is framed and encrypted", String(sent).startsWith("SPRT"))
        assertTrue("the household id must not be readable", !String(sent).contains(household))
        assertEquals(HouseholdBeacon.VALUE_BYTES, transport.advertised!!.size)
        assertTrue(
            "what is advertised is the rotating beacon, not the household",
            HouseholdBeacon.matches(transport.advertised!!, secret, clock),
        )
    }

    @Test
    fun `the transport is told how to recognise a beacon, previous window included`() = runBlocking {
        val transport = FakeTransport()

        sync(transport).run(userAsked = true)

        val isOurs = transport.isOurs!!
        assertTrue("this half-hour", isOurs(HouseholdBeacon.value(secret, clock)))
        assertTrue(
            "the half-hour before, so a boundary is not a wall",
            isOurs(HouseholdBeacon.value(secret, clock - HouseholdBeacon.WINDOW_MS)),
        )
        assertTrue(
            "another household's beacon is not ours",
            !isOurs(HouseholdBeacon.value(SyncSecret(ByteArray(SyncSecret.SIZE_BYTES) { 7 }), clock)),
        )
    }

    @Test
    fun `a radio that refuses mid-window is not an empty room`() = runBlocking {
        val transport = FakeTransport(refuse = true)

        val result = sync(transport).run(userAsked = true)

        assertEquals(
            "\"nobody answered\" would tell a parent to wait; this tells them to fix something",
            NearbyResult.CannotStart(NearbyTransport.Unavailable.RADIO_REFUSED),
            result,
        )
    }

    @Test
    fun `meeting nobody is an outcome, not a failure`() = runBlocking {
        val result = sync(FakeTransport()).run(userAsked = true)

        assertEquals(NearbyResult.NobodyNearby, result)
    }

    @Test
    fun `opening the app again straight away does not open a second window`() = runBlocking {
        val transport = FakeTransport()
        sync(transport).run(userAsked = false)

        clock += 30_000
        val second = sync(transport).run(userAsked = false)

        assertEquals(NearbyResult.TooSoon, second)
        assertEquals("the radio was used once, not twice", 1, transport.windows)

        // Far enough apart, and it looks again.
        clock += NearbyPolicy.MIN_INTERVAL_MS
        sync(transport).run(userAsked = false)
        assertEquals(2, transport.windows)
    }

    @Test
    fun `asking explicitly always opens a window`() = runBlocking {
        val transport = FakeTransport()
        sync(transport).run(userAsked = false)

        clock += 1_000
        sync(transport).run(userAsked = true)

        assertEquals(2, transport.windows)
    }

    @Test
    fun `a phone with no pairing never uses the radio`() = runBlocking {
        store.unpair()
        val transport = FakeTransport()

        val result = sync(transport).run(userAsked = true)

        assertEquals(NearbyResult.NotPaired, result)
        assertEquals(0, transport.windows)
    }

    @Test
    fun `a transport that cannot start says why`() = runBlocking {
        val transport = FakeTransport(unavailable = NearbyTransport.Unavailable.BLUETOOTH_OFF)

        val result = sync(transport).run(userAsked = true)

        assertEquals(NearbyResult.CannotStart(NearbyTransport.Unavailable.BLUETOOTH_OFF), result)
        assertEquals(0, transport.windows)
    }

    @Test
    fun `the first merge's question is not answered behind the parent's back`() = runBlocking {
        // This phone has its own history, and so has the one it just met.
        repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
        repository.addBaby("Sam", birthDate = 1_699_000_000_000)
        val transport = FakeTransport(listOf(replicaFrom("Mamie", "device-mamie")))

        val result = sync(transport).run(userAsked = true)

        assertEquals(
            "keeping everything or sharing from the pairing forward is the parent's call",
            NearbyResult.FirstMergeNeedsYou,
            result,
        )
        assertEquals("nothing was merged", 1, db.babyDao().allForSync().size)
    }

    @Test
    fun `once the first merge is done, windows merge on their own`() = runBlocking {
        repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
        repository.addBaby("Sam", birthDate = 1_699_000_000_000)
        store.markFirstMergeDone()
        val transport = FakeTransport(listOf(replicaFrom("Mamie", "device-mamie")))

        val result = sync(transport).run(userAsked = true)

        assertTrue(result is NearbyResult.Synced)
        assertEquals("Sam and Léa", 2, db.babyDao().allForSync().size)
    }
}
