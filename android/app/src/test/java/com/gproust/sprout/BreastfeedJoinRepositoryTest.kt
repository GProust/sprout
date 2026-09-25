package com.gproust.sprout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.SproutDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Joining two breastfeeds (BDR-19) is the one write in the app that edits one
 * row and deletes another as a single act. What matters here is that it is
 * one act — the other phone must never be handed a joined feed beside the half
 * that was folded into it — and that it joins what the database holds, not the
 * copies the confirmation was opened on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BreastfeedJoinRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private var clock = 1_750_000_000_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db, now = { clock })
        runBlocking {
            repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
            repository.addBaby("Sam", birthDate = 1_700_000_000_000)
        }
    }

    @After
    fun tearDown() = db.close()

    private fun breastfeed(side: BreastSide, from: Long, to: Long, notes: String? = null) = FeedingEntity(
        type = FeedType.BREAST,
        side = side,
        startTime = from,
        endTime = to,
        segments = listOf(NursingSegment(side, from, to)),
        notes = notes,
    )

    /** Logs the left side, then the right five minutes later; returns them earlier first. */
    private suspend fun twoFeeds(): Pair<FeedingEntity, FeedingEntity> {
        repository.addFeeding(breastfeed(BreastSide.LEFT, clock, clock + 9 * min))
        repository.addFeeding(breastfeed(BreastSide.RIGHT, clock + 14 * min, clock + 20 * min))
        val (later, earlier) = repository.feedings.first()
        return earlier to later
    }

    @Test
    fun joiningLeavesOneFeedAndDeletesTheOther() = runBlocking {
        val (earlier, later) = twoFeeds()
        clock += 60 * min

        assertTrue(repository.joinBreastfeeds(earlier, later))

        val feeds = repository.feedings.first()
        assertEquals(1, feeds.size)
        val joined = feeds.single()
        assertEquals(earlier.uid, joined.uid)
        assertEquals(earlier.startTime, joined.startTime)
        assertEquals(later.endTime, joined.endTime)
        assertEquals(BreastSide.BOTH, joined.side)
        assertEquals(2, joined.segments.size)
        // Stamped, so the join wins the merge on the other phone...
        assertEquals(clock, joined.updatedAt)
        // ...and the later feed is flagged rather than erased, so its deletion
        // travels too.
        val gone = db.feedingDao().findByUid(later.uid)!!
        assertEquals(clock, gone.deletedAt)
        assertEquals(clock, gone.updatedAt)
    }

    @Test
    fun aFeedDeletedWhileTheConfirmationWasOpenIsNotJoined() = runBlocking {
        val (earlier, later) = twoFeeds()
        repository.deleteFeeding(later)

        assertFalse(repository.joinBreastfeeds(earlier, later))

        val left = repository.feedings.first().single()
        assertEquals(earlier.segments, left.segments)
        assertEquals(earlier.updatedAt, left.updatedAt)
    }

    @Test
    fun theJoinReadsTheFeedsAsTheyAreNow() = runBlocking {
        val (earlier, later) = twoFeeds()
        // Edited on the other phone and merged in while the dialog was up.
        repository.addFeeding(later.copy(notes = "Hiccups"))

        assertTrue(repository.joinBreastfeeds(earlier, later))

        assertEquals("Hiccups", repository.feedings.first().single().notes)
    }

    @Test
    fun feedsThatNoLongerQualifyAreLeftAlone() = runBlocking {
        val (earlier, later) = twoFeeds()
        // Moved an hour later by an edit: no longer a burp apart.
        val moved = later.copy(
            startTime = later.startTime + 60 * min,
            endTime = later.endTime!! + 60 * min,
            segments = listOf(NursingSegment(BreastSide.RIGHT, later.startTime + 60 * min, later.endTime!! + 60 * min)),
        )
        repository.addFeeding(moved)

        assertFalse(repository.joinBreastfeeds(earlier, later))

        assertEquals(2, repository.feedings.first().size)
        assertNull(db.feedingDao().findByUid(later.uid)!!.deletedAt)
    }
}
