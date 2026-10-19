package com.gproust.sprout.data

import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.FoodPortion
import com.gproust.sprout.data.local.FoodReception
import com.gproust.sprout.data.local.MealFood
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
fun foodKey(name: String): String {
    // A scan rather than a regex: Android's regex engine is ICU, which refuses
    // the `(?U)` that makes Java's `\s` Unicode-aware — a pattern the JVM tests
    // accept and the phone throws on. `isWhitespace` is Unicode's own, a
    // no-break space included, which is the property Swift's
    // `Character.isWhitespace` reads, so a name pasted from somewhere with odd
    // spacing keys the same on both phones.
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (c in name) {
        if (c.isWhitespace()) {
            if (word.isNotEmpty()) words += word.toString().also { word.clear() }
        } else {
            word.append(c)
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words.joinToString(" ").lowercase(Locale.ROOT)
}

/**
 * What makes two foods on two meals the same food: the list's id for a food
 * from Sprout's list — "Carotte" logged in French and "Carrot" logged in
 * English are one carrot — and [foodKey] of the name for a food the household
 * typed in. Never the phone's language, so both phones count alike.
 */
fun foodIdentity(food: MealFood): String = food.ref?.let { "ref:$it" } ?: foodKey(food.name)

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
 * [key] is its [foodIdentity]. [name] is how it was written most recently — a
 * parent who corrects "carot" to "Carrot" sees the correction, not the first
 * spelling — and [ref] is its id on Sprout's list, when it is from there, which
 * is what the page names it by.
 */
data class FoodHistory(
    val key: String,
    val name: String,
    val offers: List<FoodOffer>,
    val ref: String? = null,
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

    val names = LinkedHashMap<String, MealFood>()
    val offers = HashMap<String, MutableList<FoodOffer>>()
    for (meal in meals) {
        // One offer per food per meal, in the order the meal listed them.
        val inMeal = LinkedHashMap<String, FoodOffer>()
        for (food in meal.foods) {
            if (foodKey(food.name).isEmpty() && food.ref == null) continue
            val key = foodIdentity(food)
            if (key !in names) names[key] = food
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
    return names.map { (key, latest) ->
        FoodHistory(key, latest.name.trim(), offers.getValue(key), latest.ref)
    }
}

/**
 * A meal's foods once the food [from] (a [foodIdentity]) has been corrected to
 * [to] — "carottes" typed by hand made into the carrot from the list, say, or a
 * typo put right. Null when the meal does not name [from], so the caller can
 * leave that row alone.
 *
 * The corrected food keeps the portion and the "how it went" it was logged
 * with: only what it *is* changes. Where the meal already named the food it
 * becomes, the two are one offer, read the way [foodHistories] reads a food
 * named twice — a serving if either was, the first answer that was noted.
 */
fun relabelledFoods(foods: List<MealFood>, from: String, to: MealFood): List<MealFood>? {
    if (foods.none { foodIdentity(it) == from }) return null
    val merged = LinkedHashMap<String, MealFood>()
    for (food in foods) {
        val corrected = if (foodIdentity(food) == from) food.copy(name = to.name, ref = to.ref) else food
        val key = foodIdentity(corrected)
        val seen = merged[key]
        merged[key] = if (seen == null) {
            corrected
        } else {
            seen.copy(
                portion = if (corrected.portion == FoodPortion.SERVING) FoodPortion.SERVING else seen.portion,
                reception = seen.reception ?: corrected.reception,
            )
        }
    }
    return merged.values.toList()
}
