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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A breastfeed timed on the nursing screen is saved to the baby it was started
 * for (#115). With twins the selection is a mode: start Léa's feed from her
 * card, tap Noah's card a minute later, and the selection is Noah's by the time
 * the feed is saved. It used to be saved to him.
 *
 * The same cases are checked on iOS, because both phones in a household have to
 * file the same feed under the same baby.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimedFeedRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private val clock = 1_750_000_000_000L
    private val min = 60_000L
    private var lea = 0L
    private var noah = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db, now = { clock })
        runBlocking {
            repository.saveParentProfile(ParentProfileEntity(name = "Alex"))
            // The first baby added is the one selected.
            lea = repository.addBaby("Léa", birthDate = 1_700_000_000_000)
            noah = repository.addBaby("Noah", birthDate = 1_700_000_000_000)
        }
    }

    @After
    fun tearDown() = db.close()

    private val feed = FeedingEntity(
        type = FeedType.BREAST,
        side = BreastSide.LEFT,
        startTime = clock,
        endTime = clock + 9 * min,
        leftDurationMs = 9 * min,
        segments = listOf(NursingSegment(BreastSide.LEFT, clock, clock + 9 * min)),
    )

    private suspend fun savedTo(): Long = db.feedingDao().allForSync().single().babyId

    @Test
    fun theFeedGoesToTheBabyItWasStartedFor() = runBlocking {
        val startedFor = repository.activeBabyUidNow()
        repository.setActiveBaby(noah)

        val babyId = repository.addTimedFeeding(startedFor, feed)

        assertEquals(lea, babyId)
        assertEquals(lea, savedTo())
    }

    @Test
    fun theSelectedBabyIsTheOneAFeedStartsFor() = runBlocking {
        repository.setActiveBaby(noah)

        assertEquals(db.babyDao().uidById(noah), repository.activeBabyUidNow())
    }

    /** A session left running by the version before, which named no baby. */
    @Test
    fun aSessionThatNamesNoBabyGoesToTheSelectedOne() = runBlocking {
        repository.setActiveBaby(noah)

        assertEquals(noah, repository.addTimedFeeding(null, feed))
        assertEquals(noah, savedTo())
    }

    /** A baby that is no longer here: the feed is kept, on the selected baby. */
    @Test
    fun aBabyNoLongerHereLeavesTheFeedToTheSelectedOne() = runBlocking {
        repository.setActiveBaby(noah)

        assertEquals(noah, repository.addTimedFeeding("not-a-baby-any-more", feed))
        assertEquals(noah, savedTo())
    }
}
