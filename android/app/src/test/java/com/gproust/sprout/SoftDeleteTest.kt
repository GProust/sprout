package com.gproust.sprout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.sync.TOMBSTONE_RETENTION_MS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting an entry stopped meaning "remove the row" in phase 0 of partner sync
 * (ADR-0007): it now flags the row, so the deletion is something the other phone
 * can be told about instead of quietly undoing it.
 *
 * That rewrites the riskiest path in the app, on data a user cannot re-enter, so
 * what matters here is both halves: a deleted entry is really gone *from the
 * app's point of view*, and "permanently delete a baby" is really gone from the
 * database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoftDeleteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db, now = { clock })
    }

    @After
    fun tearDown() = db.close()

    /** A profile and a baby, so the baby-scoped logs have somewhere to go. */
    private suspend fun seedBaby(): Long {
        repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
        return repository.addBaby("Sam", birthDate = 1_700_000_000_000)
    }

    private fun count(sql: String): Long =
        db.query(sql, null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    @Test
    fun everyNewRowIsStampedForSync() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))

        val feeding = db.feedingDao().lastFeed(babyId)!!
        assertTrue("a row needs an identity the partner's phone can match", feeding.uid.isNotEmpty())
        assertEquals(clock, feeding.updatedAt)
        assertNull(feeding.deletedAt)
    }

    @Test
    fun editingAnEntryKeepsItsIdentityAndMovesItsClock() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))
        val original = db.feedingDao().lastFeed(babyId)!!

        clock += 5_000
        repository.addFeeding(original.copy(amountMl = 120))
        val edited = db.feedingDao().lastFeed(babyId)!!

        assertEquals("an edit is the same entry, not a new one", original.uid, edited.uid)
        assertEquals(120, edited.amountMl)
        assertEquals("the later write is the one that wins a merge", clock, edited.updatedAt)
        assertEquals(1, count("SELECT COUNT(*) FROM feeding"))
    }

    @Test
    fun deletingAnEntryHidesItButRemembersTheDeletion() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))
        val feeding = db.feedingDao().lastFeed(babyId)!!

        clock += 1_000
        repository.deleteFeeding(feeding)

        assertNull("the app must not show a deleted feed", db.feedingDao().lastFeed(babyId))
        assertTrue(db.feedingDao().observeForBaby(babyId).first().isEmpty())
        assertNull("nor count it towards the last feed", db.feedingDao().lastFeedTime(babyId))
        assertEquals(
            "but the row stays, flagged, so the deletion can reach the other phone",
            1,
            count("SELECT COUNT(*) FROM feeding WHERE deletedAt = $clock"),
        )
    }

    @Test
    fun deletingHidesEveryKindOfEntry() = runBlocking {
        val babyId = seedBaby()
        repository.addDiaper(DiaperEntity(time = 1, wet = true))
        repository.addPumping(PumpingEntity(time = 1, amountMl = 120, storage = MilkStorage.FRIDGE))

        val diaper = db.diaperDao().observeForBaby(babyId).first().single()
        val pumping = db.pumpingDao().observeAll().first().single()
        repository.deleteDiaper(diaper)
        repository.deletePumping(pumping)

        assertTrue(db.diaperDao().observeForBaby(babyId).first().isEmpty())
        assertTrue("the stash must not count milk that was deleted", db.pumpingDao().observeAll().first().isEmpty())
    }

    @Test
    fun anArchivedBabyIsNotADeletedOne() = runBlocking {
        val babyId = seedBaby()

        repository.archiveBaby(babyId)

        assertTrue(db.babyDao().observeActiveBabies().first().isEmpty())
        assertEquals(
            "stop tracking keeps the history — it is not a delete",
            1,
            db.babyDao().observeArchivedBabies().first().size,
        )
        repository.restoreBaby(babyId)
        assertEquals(1, db.babyDao().observeActiveBabies().first().size)
    }

    @Test
    fun permanentlyDeletingABabyReallyErasesIt() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))
        repository.addDiaper(DiaperEntity(time = 1, wet = true))

        repository.deleteBaby(babyId)

        assertEquals("the rows are gone, not flagged", 0, count("SELECT COUNT(*) FROM feeding"))
        assertEquals(0, count("SELECT COUNT(*) FROM diaper"))
        assertEquals(0, count("SELECT COUNT(*) FROM baby"))
        assertEquals(
            "only the bare fact of each deletion is kept, so it still travels",
            3,
            count("SELECT COUNT(*) FROM tombstone"),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM tombstone WHERE entity = 'baby'"))
        assertNotNull(db.tombstoneDao().all().first().uid)
    }

    @Test
    fun compactionErasesOldDeletionsAndSparesRecentOnes() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))
        repository.deleteFeeding(db.feedingDao().lastFeed(babyId)!!)

        repository.compactTombstones()
        assertEquals(
            "a fresh deletion is still needed — the partner may not have synced yet",
            1,
            count("SELECT COUNT(*) FROM feeding"),
        )

        clock += TOMBSTONE_RETENTION_MS + 1
        repository.compactTombstones()
        assertEquals("past the retention window it goes for good", 0, count("SELECT COUNT(*) FROM feeding"))
    }

    @Test
    fun compactionEventuallyClearsThePermanentDeletionsToo() = runBlocking {
        val babyId = seedBaby()
        repository.addFeeding(FeedingEntity(type = FeedType.BOTTLE, startTime = 1, amountMl = 90))
        repository.deleteBaby(babyId)
        assertEquals(2, count("SELECT COUNT(*) FROM tombstone"))

        clock += TOMBSTONE_RETENTION_MS + 1
        repository.compactTombstones()

        assertEquals(0, count("SELECT COUNT(*) FROM tombstone"))
    }
}
