package com.gproust.sprout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.sync.SyncEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two phones, one baby.
 *
 * Each test runs two real databases side by side and moves replicas between
 * them, because the properties worth having are properties of the pair:
 * applying the same replica twice must change nothing, exchanging in either
 * order must converge, and a deletion must stay deleted — the failure this
 * whole design exists to prevent is the partner's copy quietly resurrecting
 * what someone deleted (ADR-0007).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val household = "household-1"

    /** One phone: its database, the repository that writes to it, and its engine. */
    private inner class Phone(val name: String) {
        var clock = 1_000_000L
        val db: SproutDatabase = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = SproutRepository(db, now = { clock })
        val engine = SyncEngine(db, now = { clock })

        suspend fun replica(includePumping: Boolean = true) = engine.buildPayload(
            householdId = household,
            deviceId = name,
            deviceName = name,
            includePumping = includePumping,
        )

        suspend fun feedings() = db.feedingDao().observeForBaby(babyId()).first()
        suspend fun babyId() = db.babyDao().allForSync().first { it.deletedAt == null }.id
        suspend fun feedingUids() = feedings().map { it.uid }.toSet()

        suspend fun seedParent() = repository.saveParentProfile(ParentProfileEntity(name = name))
    }

    private val phones = mutableListOf<Phone>()

    private fun phone(name: String) = Phone(name).also { phones += it }

    @After
    fun tearDown() = phones.forEach { it.db.close() }

    private suspend fun Phone.logFeed(amountMl: Int, at: Long) {
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, amountMl = amountMl, startTime = at))
    }

    @Test
    fun `a phone with no history of its own adopts the other's`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)
        alex.logFeed(80, at = 1_700_000_200_000)

        val sam = phone("sam")
        sam.seedParent()
        assertFalse("the fresh phone has nothing yet", sam.engine.hasOwnHistory())

        val summary = sam.engine.merge(alex.replica())

        assertEquals("one baby and two feeds", 3, summary.added)
        assertEquals(0, summary.skipped)
        assertEquals("Léa", sam.db.babyDao().allForSync().single().name)
        assertEquals(listOf(80, 90), sam.feedings().mapNotNull { it.amountMl }.sorted())
    }

    @Test
    fun `merging the same replica twice changes nothing the second time`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)
        val replica = alex.replica()

        val sam = phone("sam")
        sam.seedParent()
        val first = sam.engine.merge(replica)
        val second = sam.engine.merge(replica)

        assertEquals(2, first.added)
        assertEquals("idempotent: nothing new the second time", 0, second.added)
        assertEquals(0, second.updated)
        assertEquals(2, second.alreadyKnown)
        assertEquals(1, sam.feedings().size)
    }

    @Test
    fun `each phone's own entries survive an exchange in either direction`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(alex.replica())
        // Adopting a baby doesn't select it — the import flow does that; here it
        // is done by hand so this phone can log something of its own.
        sam.repository.setActiveBaby(sam.babyId())
        sam.clock += 1_000
        sam.logFeed(70, at = 1_700_000_300_000)

        // Both directions, and then both again: convergence, not ping-pong.
        alex.engine.merge(sam.replica())
        sam.engine.merge(alex.replica())
        alex.engine.merge(sam.replica())

        assertEquals("both phones hold the same entries", alex.feedingUids(), sam.feedingUids())
        assertEquals(2, alex.feedings().size)
        assertEquals(listOf(70, 90), alex.feedings().mapNotNull { it.amountMl }.sorted())
    }

    @Test
    fun `the later edit is the one that wins`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(alex.replica())

        alex.clock += 5_000
        val corrected = alex.feedings().single().copy(amountMl = 120)
        alex.repository.addFeeding(corrected)

        val summary = sam.engine.merge(alex.replica())

        assertEquals(1, summary.updated)
        assertEquals(0, summary.added)
        assertEquals("the correction, not a second feed", 120, sam.feedings().single().amountMl)
    }

    @Test
    fun `a deletion reaches the other phone`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)
        alex.logFeed(80, at = 1_700_000_200_000)

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(alex.replica())

        alex.clock += 5_000
        alex.repository.deleteFeeding(alex.feedings().first())
        val summary = sam.engine.merge(alex.replica())

        assertEquals(1, summary.deleted)
        assertEquals(1, sam.feedings().size)
    }

    @Test
    fun `a deletion is not undone by a copy that predates it`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)
        val staleReplica = alex.replica()

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(staleReplica)
        sam.clock += 5_000
        sam.repository.deleteFeeding(sam.feedings().single())

        // Alex's phone still holds the entry and offers it back.
        val summary = sam.engine.merge(staleReplica)

        assertEquals("resurrecting it would undo what the parent asked for", 0, summary.added)
        assertTrue(sam.feedings().isEmpty())
    }

    @Test
    fun `an erased baby is not restored by the other phone's copy`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)
        val replica = alex.replica()

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(replica)
        sam.clock += 5_000
        sam.repository.deleteBaby(sam.babyId())

        val summary = sam.engine.merge(replica)

        assertEquals(0, summary.added)
        assertEquals("both the baby and its feed stay gone", 2, summary.skipped)
        assertTrue(sam.db.babyDao().allForSync().isEmpty())
        assertTrue(sam.db.feedingDao().allForSync().isEmpty())
    }

    @Test
    fun `the erasure travels, so the other phone lets it go too`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(alex.replica())

        alex.clock += 5_000
        alex.repository.deleteBaby(alex.babyId())
        val summary = sam.engine.merge(alex.replica())

        assertEquals("the baby and its feed", 2, summary.deleted)
        assertTrue(sam.db.babyDao().allForSync().isEmpty())
        assertTrue(sam.db.feedingDao().allForSync().isEmpty())
    }

    @Test
    fun `the stash switch keeps expressed milk at home`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.repository.addPumping(
            PumpingEntity(time = 1_700_000_100_000, amountMl = 120, storage = MilkStorage.FRIDGE),
        )

        val sam = phone("sam")
        sam.seedParent()
        sam.engine.merge(alex.replica(includePumping = false))

        assertTrue("a replica without the stash merges like any other", sam.db.pumpingDao().allForSync().isEmpty())
        assertEquals("the rest still arrives", 1, sam.db.babyDao().allForSync().size)

        // And turning it back on shares it, without any special handling.
        sam.engine.merge(alex.replica(includePumping = true))
        assertEquals(120, sam.db.pumpingDao().allForSync().single().amountMl)
    }

    @Test
    fun `sharing from the pairing forward leaves the earlier entries at home`() = runBlocking {
        val alex = phone("alex")
        alex.seedParent()
        alex.repository.addBaby("Léa", birthDate = 1_699_000_000_000)
        alex.logFeed(90, at = 1_700_000_100_000)

        val pairedAt = alex.clock + 1
        alex.clock += 10_000
        alex.logFeed(80, at = 1_700_000_200_000)

        val sam = phone("sam")
        sam.seedParent()
        val summary = sam.engine.merge(alex.replica(), since = pairedAt)

        assertEquals("the baby, and only what was logged after pairing", 2, summary.added)
        assertEquals(1, summary.skipped)
        assertEquals(listOf(80), sam.db.feedingDao().allForSync().map { it.amountMl })
    }
}
