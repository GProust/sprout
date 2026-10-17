package com.gproust.sprout.ui.foods

import android.content.Context
import com.gproust.sprout.R
import com.gproust.sprout.data.FoodCategory
import com.gproust.sprout.data.FoodHistory
import com.gproust.sprout.data.local.MealFood

/** Each food on the list, by id, to its name in the phone's language (BDR-20). */
private val itemNames: Map<String, Int> = mapOf(
    "artichoke" to R.string.food_item_artichoke,
    "asparagus" to R.string.food_item_asparagus,
    "aubergine" to R.string.food_item_aubergine,
    "avocado" to R.string.food_item_avocado,
    "beetroot" to R.string.food_item_beetroot,
    "broccoli" to R.string.food_item_broccoli,
    "butternut" to R.string.food_item_butternut,
    "cabbage" to R.string.food_item_cabbage,
    "carrot" to R.string.food_item_carrot,
    "cauliflower" to R.string.food_item_cauliflower,
    "celeriac" to R.string.food_item_celeriac,
    "chickpeas" to R.string.food_item_chickpeas,
    "courgette" to R.string.food_item_courgette,
    "cucumber" to R.string.food_item_cucumber,
    "fennel" to R.string.food_item_fennel,
    "green_beans" to R.string.food_item_green_beans,
    "leek" to R.string.food_item_leek,
    "lentils" to R.string.food_item_lentils,
    "mushrooms" to R.string.food_item_mushrooms,
    "parsnip" to R.string.food_item_parsnip,
    "peas" to R.string.food_item_peas,
    "potato" to R.string.food_item_potato,
    "pumpkin" to R.string.food_item_pumpkin,
    "spinach" to R.string.food_item_spinach,
    "sweet_pepper" to R.string.food_item_sweet_pepper,
    "sweet_potato" to R.string.food_item_sweet_potato,
    "sweetcorn" to R.string.food_item_sweetcorn,
    "tomato" to R.string.food_item_tomato,
    "turnip" to R.string.food_item_turnip,
    "beef" to R.string.food_item_beef,
    "chicken" to R.string.food_item_chicken,
    "duck" to R.string.food_item_duck,
    "ham" to R.string.food_item_ham,
    "lamb" to R.string.food_item_lamb,
    "pork" to R.string.food_item_pork,
    "rabbit" to R.string.food_item_rabbit,
    "turkey" to R.string.food_item_turkey,
    "veal" to R.string.food_item_veal,
    "cod" to R.string.food_item_cod,
    "hake" to R.string.food_item_hake,
    "mackerel" to R.string.food_item_mackerel,
    "salmon" to R.string.food_item_salmon,
    "sardines" to R.string.food_item_sardines,
    "sea_bass" to R.string.food_item_sea_bass,
    "sea_bream" to R.string.food_item_sea_bream,
    "sole" to R.string.food_item_sole,
    "trout" to R.string.food_item_trout,
    "tuna" to R.string.food_item_tuna,
)

/**
 * The name a food on the list goes by here, or null for one this version has no
 * name for — the caller then shows the name the meal was saved with.
 */
fun catalogName(context: Context, id: String?): String? =
    id?.let { itemNames[it] }?.let { context.getString(it) }

fun FoodCategory.label(context: Context): String = context.getString(
    when (this) {
        FoodCategory.VEGETABLE -> R.string.food_category_vegetable
        FoodCategory.MEAT -> R.string.food_category_meat
        FoodCategory.FISH -> R.string.food_category_fish
    },
)

/**
 * What a food on a meal is called on this phone: a food from the list in this
 * phone's language — the other parent may have logged it in theirs — and one
 * the household added in the words it was typed in.
 */
fun MealFood.displayName(context: Context): String = catalogName(context, ref) ?: name

fun FoodHistory.displayName(context: Context): String = catalogName(context, ref) ?: name
