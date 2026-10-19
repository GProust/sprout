package com.gproust.sprout

import com.gproust.sprout.data.foodHistories
import com.gproust.sprout.data.foodIdentity
import com.gproust.sprout.data.foodKey
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodPortion.SERVING
import com.gproust.sprout.data.local.FoodPortion.TASTE
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.FoodReception.LIKED
import com.gproust.sprout.data.local.FoodReception.MIXED
import com.gproust.sprout.data.local.FoodReception.REFUSED
import com.gproust.sprout.data.local.MealFood
import com.gproust.sprout.data.relabelledFoods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The First foods page's arithmetic (BDR-20). `FoodExposuresTests.swift` runs
 * the same cases against the iOS port: a household with one phone of each has
 * to see the same count for the same carrot.
 */
class FoodExposuresTest {

    private val day = 24 * 60 * 60_000L
    private val t0 = 1_760_000_000_000L

    private fun meal(
        uid: String,
        at: Long,
        vararg foods: MealFood,
        type: FeedType = FeedType.SOLID,
        deletedAt: Long? = null,
    ) = FeedingEntity(type = type, startTime = at, foods = foods.toList(), uid = uid, deletedAt = deletedAt)

    private fun food(name: String, portion: FoodPortion = TASTE, reception: FoodReception? = null) =
        MealFood(name, portion, reception)

    @Test
    fun `a food is its name, without regard to case or spacing`() {
        assertEquals("carrot", foodKey("Carrot"))
        assertEquals("carrot", foodKey("  CARROT "))
        assertEquals("sweet potato", foodKey("Sweet \t  potato"))
        // A no-break space, as a name pasted from a recipe might carry.
        assertEquals("sweet potato", foodKey("sweet\u00A0potato"))
        assertEquals("œuf", foodKey("Œuf"))
        assertEquals("", foodKey("   "))
        // Sprout does not know the language well enough to merge these.
        assertNotEquals(foodKey("carrot"), foodKey("carrots"))
    }

    @Test
    fun `every meal a food was in is one offer, tastes and servings apart`() {
        val foods = foodHistories(
            listOf(
                meal("m1", t0, food("carrot")),
                meal("m2", t0 + day, food("carrot", SERVING, LIKED), food("Pea")),
                meal("m3", t0 + 2 * day, food("Carrot", SERVING, REFUSED)),
            ),
        )

        assertEquals(listOf("carrot", "pea"), foods.map { it.key })
        val carrot = foods[0]
        assertEquals(3, carrot.timesOffered)
        assertEquals(1, carrot.tastes)
        assertEquals(2, carrot.servings)
        assertEquals(1, carrot.timesReceived(LIKED))
        assertEquals(1, carrot.timesReceived(REFUSED))
        assertEquals(0, carrot.timesReceived(MIXED))
        assertEquals(t0, carrot.firstOffered)
        assertEquals(t0 + 2 * day, carrot.lastOffered)
        assertEquals(SERVING, carrot.lastPortion)
        assertEquals(listOf("m3", "m2", "m1"), carrot.offers.map { it.feedUid })
    }

    @Test
    fun `a food is shown as it was most recently written`() {
        val foods = foodHistories(
            listOf(
                meal("m1", t0 + day, food("Carrot")),
                meal("m2", t0, food("carrot")),
            ),
        )

        assertEquals("Carrot", foods.single().name)
    }

    @Test
    fun `the foods are in the order they were last offered`() {
        val foods = foodHistories(
            listOf(
                meal("m1", t0, food("Apple"), food("Pear")),
                meal("m2", t0 + day, food("Banana")),
                meal("m3", t0 + 2 * day, food("Pear")),
            ),
        )

        // Pear was last offered most recently; Apple and Banana keep their
        // own last time, and one meal's foods keep the order it listed them.
        assertEquals(listOf("Pear", "Banana", "Apple"), foods.map { it.name })
    }

    @Test
    fun `a food named twice in one meal is one offer of it`() {
        val pea = foodHistories(
            listOf(meal("m1", t0, food("Pea"), food(" pea ", SERVING, MIXED))),
        ).single()

        assertEquals(1, pea.timesOffered)
        assertEquals("a serving if either was", SERVING, pea.lastPortion)
        assertEquals("and the first answer that was noted", 1, pea.timesReceived(MIXED))
        assertEquals("Pea", pea.name)
    }

    @Test
    fun `only solid meals that still exist count, and a blank name is no food`() {
        val foods = foodHistories(
            listOf(
                meal("bottle", t0, food("Carrot"), type = FeedType.BOTTLE),
                meal("gone", t0, food("Carrot"), deletedAt = t0 + 1),
                meal("blank", t0, food("   ")),
                meal("plain", t0),
            ),
        )

        assertTrue(foods.isEmpty())
    }

    @Test
    fun `two meals at the same moment are read in uid order, so both phones agree`() {
        val foods = foodHistories(
            listOf(
                meal("b", t0, food("Carrot")),
                meal("a", t0, food("carrot", SERVING)),
            ),
        )

        val carrot = foods.single()
        assertEquals("carrot", carrot.name)
        assertEquals(listOf("a", "b"), carrot.offers.map { it.feedUid })
        assertEquals(SERVING, carrot.lastPortion)
    }

    @Test
    fun `a food from the list is one food in every language`() {
        val foods = foodHistories(
            listOf(
                meal("m1", t0, MealFood("Carotte", TASTE, ref = "carrot")),
                meal("m2", t0 + day, MealFood("Carrot", SERVING, ref = "carrot")),
                // Typed in, not picked from the list: the household's own food,
                // which a carrot from the list never silently becomes.
                meal("m3", t0 + 2 * day, food("Carrot")),
            ),
        )

        assertEquals(listOf("carrot", "ref:carrot"), foods.map { it.key })
        val listed = foods[1]
        assertEquals(2, listed.timesOffered)
        assertEquals("carrot", listed.ref)
        assertEquals("the latest name it was saved with", "Carrot", listed.name)
        assertNull(foods[0].ref)
        assertEquals("ref:carrot", foodIdentity(MealFood("Zanahoria", ref = "carrot")))
        assertEquals("carrot", foodIdentity(MealFood(" Carrot ")))
    }

    @Test
    fun `nothing noted is not an answer`() {
        val carrot = foodHistories(listOf(meal("m1", t0, food("Carrot")))).single()

        assertNull(carrot.offers.single().reception)
        FoodReception.entries.forEach { assertEquals(0, carrot.timesReceived(it)) }
        assertEquals("a new food starts as a taste", TASTE, carrot.lastPortion)
    }

    @Test
    fun `a food corrected to one from the list takes its id and keeps how it was given`() {
        val carrot = MealFood("Carotte", ref = "carrot")
        val corrected = relabelledFoods(
            listOf(food("carottes", SERVING, LIKED), food("Pea")),
            from = "carottes",
            to = carrot,
        )

        assertEquals(
            listOf(MealFood("Carotte", SERVING, LIKED, ref = "carrot"), food("Pea")),
            corrected,
        )
    }

    @Test
    fun `a meal that already named the food it becomes keeps one offer of it`() {
        val corrected = relabelledFoods(
            listOf(MealFood("Carotte", TASTE, ref = "carrot"), food("carottes", SERVING, MIXED)),
            from = "carottes",
            to = MealFood("Carotte", ref = "carrot"),
        )

        assertEquals(listOf(MealFood("Carotte", SERVING, MIXED, ref = "carrot")), corrected)
    }

    @Test
    fun `a meal that does not name the food is left alone`() {
        assertNull(relabelledFoods(listOf(food("Pea")), from = "carottes", to = food("Carrot")))
    }

    @Test
    fun `after a correction the two lines are one food`() {
        val meals = listOf(
            meal("m1", t0, food("carottes")),
            meal("m2", t0 + day, MealFood("Carotte", SERVING, ref = "carrot")),
        )
        val fixed = meals.map { m ->
            relabelledFoods(m.foods, "carottes", MealFood("Carotte", ref = "carrot"))?.let { m.copy(foods = it) } ?: m
        }

        val carrot = foodHistories(fixed).single()
        assertEquals("ref:carrot", carrot.key)
        assertEquals(2, carrot.timesOffered)
    }
}
