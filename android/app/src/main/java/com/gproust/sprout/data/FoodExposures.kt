package com.gproust.sprout.data

import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodReception
import java.util.Locale

/**
 * The *First foods* page's arithmetic (BDR-20): which foods a baby has been
 * offered, how many times, and how it went.
 *
 * Nothing here is stored. A solid meal lists its foods and these figures are
 * read back from the meals every time they are asked for, so correcting or
 * deleting a meal moves them — the same reason the medicine traffic light is
 * derived from its doses rather than kept.
 *
 * Pure functions, mirrored by `SproutData/FoodExposures.swift` with the same
 * cases: a household with one phone of each has to see the same count.
 */

/**
 * The name two spellings of one food share: trimmed, inner runs of spacing
 * collapsed to one space, and lower-cased without regard to the phone's
 * language — so "Carrot", "carrot" and " carrot " are one food on every phone.
 *
 * Nothing cleverer. "Carrots" stays apart from "carrot": Sprout does not know
 * the language well enough to say they are one, and guessing would merge foods
 * a parent meant apart.
 */
fun foodKey(name: String): String =
    name.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ").lowercase(Locale.ROOT)

/**
 * Unicode's own whitespace, a no-break space included — the same property
 * Swift's `Character.isWhitespace` reads, so a name pasted from somewhere with
 * odd spacing keys the same on both phones.
 */
private val WHITESPACE = Regex("(?U)\\s+")

/** One meal a food was part of. */
data class FoodOffer(
    /** The meal's [FeedingEntity.uid], so the page can open it to correct. */
    val feedUid: String,
    val time: Long,
    val portion: FoodPortion,
    val reception: FoodReception?,
)

/**
 * Everything one food has been through: the meals it was in, newest first.
 *
 * [name] is how it was written most recently — a parent who corrects
 * "carot" to "Carrot" sees the correction, not the first spelling.
 */
data class FoodHistory(
    val key: String,
    val name: String,
    val offers: List<FoodOffer>,
) {
    /** How many meals included it — a taste is an offer too. */
    val timesOffered: Int get() = offers.size
    val tastes: Int get() = offers.count { it.portion == FoodPortion.TASTE }
    val servings: Int get() = offers.count { it.portion == FoodPortion.SERVING }
    val firstOffered: Long get() = offers.last().time
    val lastOffered: Long get() = offers.first().time

    /** The portion it was given last time — what the form starts it on. */
    val lastPortion: FoodPortion get() = offers.first().portion

    /** How many meals it went [reception] at; meals that noted nothing count for none. */
    fun timesReceived(reception: FoodReception): Int = offers.count { it.reception == reception }
}

/**
 * Every food in [feeds], most recently offered first.
 *
 * Only solid meals that still exist count; the caller decides whose meals they
 * are (one baby for the page, the whole household for the form's suggestions).
 * A food named twice in one meal is one offer of it: a serving if either was,
 * and the first "how it went" that was noted. Two meals at the same moment are
 * taken in `uid` order, so both phones agree on which spelling is the latest.
 */
fun foodHistories(feeds: List<FeedingEntity>): List<FoodHistory> {
    val meals = feeds
        .filter { it.type == FeedType.SOLID && it.deletedAt == null && it.foods.isNotEmpty() }
        .sortedWith(compareByDescending<FeedingEntity> { it.startTime }.thenBy { it.uid })

    val names = LinkedHashMap<String, String>()
    val offers = HashMap<String, MutableList<FoodOffer>>()
    for (meal in meals) {
        // One offer per food per meal, in the order the meal listed them.
        val inMeal = LinkedHashMap<String, FoodOffer>()
        for (food in meal.foods) {
            val key = foodKey(food.name)
            if (key.isEmpty()) continue
            if (key !in names) names[key] = food.name.trim()
            val seen = inMeal[key]
            inMeal[key] = if (seen == null) {
                FoodOffer(meal.uid, meal.startTime, food.portion, food.reception)
            } else {
                seen.copy(
                    portion = if (food.portion == FoodPortion.SERVING) FoodPortion.SERVING else seen.portion,
                    reception = seen.reception ?: food.reception,
                )
            }
        }
        inMeal.forEach { (key, offer) -> offers.getOrPut(key) { mutableListOf() } += offer }
    }
    // `names` was filled newest meal first, so its order is already "most
    // recently offered first".
    return names.map { (key, name) -> FoodHistory(key, name, offers.getValue(key)) }
}
