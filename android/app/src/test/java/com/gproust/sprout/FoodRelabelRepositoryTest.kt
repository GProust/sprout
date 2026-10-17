package com.gproust.sprout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gproust.sprout.data.SproutRepository
import com.gproust.sprout.data.foodHistories
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.MealFood
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
 * Correcting a food after the fact (BDR-20): "carottes" typed by hand made into
 * the carrot from the list, on every meal of one baby's that named it. What
 * matters is that each meal it touched is stamped, so the correction travels,
 * and that it touches nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FoodRelabelRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SproutDatabase
    private lateinit var repository: SproutRepository
    private var clock = 1_750_000_000_000L
    private val hour = 60 * 60_000L
    private val carrot = MealFood("Carotte", ref = "carrot")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SproutRepository(db, now = { clock })
        runBlocking { repository.saveParentProfile(ParentProfileEntity(name = "Alex")) }
    }

    @After
    fun tearDown() = db.close()

    private fun meal(at: Long, vararg foods: MealFood) =
        FeedingEntity(type = FeedType.SOLID, startTime = at, foods = foods.toList())

    private suspend fun all(): List<FeedingEntity> = db.feedingDao().allForSync()

    @Test
    fun everyMealThatNamedTheFoodIsCorrectedAndStamped() = runBlocking {
        val sam = repository.addBaby("Sam", birthDate = 1_700_000_000_000)
        repository.addFeedingFor(sam, meal(clock, MealFood("carottes", FoodPortion.SERVING, FoodReception.LIKED)))
        repository.addFeedingFor(sam, meal(clock + hour, MealFood("Pea"), MealFood("carottes")))
        repository.addFeedingFor(sam, meal(clock + 2 * hour, MealFood("Pea")))
        val before = all().associateBy { it.uid }
        clock += 24 * hour

        assertEquals(2, repository.relabelFood(sam, from = "carottes", to = carrot))

        val after = all()
        val changed = after.filter { it.updatedAt == clock }
        assertEquals(2, changed.size)
        assertEquals(
            listOf(MealFood("Carotte", FoodPortion.SERVING, FoodReception.LIKED, ref = "carrot")),
            changed.single { it.startTime == before.values.minOf { m -> m.startTime } }.foods,
        )
        // The meal that never named it is not rewritten, so it does not travel.
        val untouched = after.single { it.foods == listOf(MealFood("Pea")) }
        assertEquals(before.getValue(untouched.uid).updatedAt, untouched.updatedAt)

        val foods = foodHistories(after)
        assertEquals(listOf("ref:carrot", "pea"), foods.map { it.key }.sorted())
        // Each meal keeps its own uid: the other phone is handed the same meals, corrected.
        assertEquals(before.keys, after.map { it.uid }.toSet())
    }

    @Test
    fun aTwinsMealsAndDeletedMealsAreLeftAlone() = runBlocking {
        val sam = repository.addBaby("Sam", birthDate = 1_700_000_000_000)
        val robin = repository.addBaby("Robin", birthDate = 1_700_000_000_000)
        repository.addFeedingFor(robin, meal(clock, MealFood("carottes")))
        repository.addFeedingFor(sam, meal(clock, MealFood("carottes")))
        val gone = all().single { it.babyId == sam }
        repository.deleteFeeding(gone)
        clock += hour

        assertEquals(0, repository.relabelFood(sam, from = "carottes", to = carrot))

        assertEquals(listOf("carottes", "carottes"), all().map { it.foods.single().name })
    }
}
