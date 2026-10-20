package com.gproust.sprout.data

/**
 * The shelf a food on the list sits on. Vegetables, fruit, meat and fish: the
 * list is a convenience for the foods every family names, not a map of what a
 * baby should eat (BDR-20).
 */
enum class FoodCategory { VEGETABLE, FRUIT, MEAT, FISH }

/**
 * One food on Sprout's list. [id] is what travels with the meal
 * ([com.gproust.sprout.data.local.MealFood.ref]), so a carrot logged on a phone
 * in French and one logged on a phone in English are the same carrot; the name
 * a parent reads is looked up from it in their own language.
 */
data class CatalogFood(val id: String, val category: FoodCategory)

/**
 * The list the meal form searches (BDR-20): plain names, in seven languages,
 * and nothing else — no ages, no order of introduction, no allergen marks,
 * nothing suggested. Anything not on it is typed in and kept as the household's
 * own, which is what a ratatouille or a grandmother's soup is.
 *
 * The ids are pinned by `spec/vectors/food-catalog.json`, which
 * `SproutData/FoodCatalog.swift` is held to as well: an id that exists on one
 * platform and not the other is a food one phone names and the other cannot.
 * Add to the end of a category, never rename or remove an id — it is in every
 * meal that used it.
 */
object FoodCatalog {
    val foods: List<CatalogFood> = listOf(
        CatalogFood("artichoke", FoodCategory.VEGETABLE),
        CatalogFood("asparagus", FoodCategory.VEGETABLE),
        CatalogFood("aubergine", FoodCategory.VEGETABLE),
        CatalogFood("avocado", FoodCategory.VEGETABLE),
        CatalogFood("beetroot", FoodCategory.VEGETABLE),
        CatalogFood("broccoli", FoodCategory.VEGETABLE),
        CatalogFood("butternut", FoodCategory.VEGETABLE),
        CatalogFood("cabbage", FoodCategory.VEGETABLE),
        CatalogFood("carrot", FoodCategory.VEGETABLE),
        CatalogFood("cauliflower", FoodCategory.VEGETABLE),
        CatalogFood("celeriac", FoodCategory.VEGETABLE),
        CatalogFood("chickpeas", FoodCategory.VEGETABLE),
        CatalogFood("courgette", FoodCategory.VEGETABLE),
        CatalogFood("cucumber", FoodCategory.VEGETABLE),
        CatalogFood("fennel", FoodCategory.VEGETABLE),
        CatalogFood("green_beans", FoodCategory.VEGETABLE),
        CatalogFood("leek", FoodCategory.VEGETABLE),
        CatalogFood("lentils", FoodCategory.VEGETABLE),
        CatalogFood("mushrooms", FoodCategory.VEGETABLE),
        CatalogFood("parsnip", FoodCategory.VEGETABLE),
        CatalogFood("peas", FoodCategory.VEGETABLE),
        CatalogFood("potato", FoodCategory.VEGETABLE),
        CatalogFood("pumpkin", FoodCategory.VEGETABLE),
        CatalogFood("spinach", FoodCategory.VEGETABLE),
        CatalogFood("sweet_pepper", FoodCategory.VEGETABLE),
        CatalogFood("sweet_potato", FoodCategory.VEGETABLE),
        CatalogFood("sweetcorn", FoodCategory.VEGETABLE),
        CatalogFood("tomato", FoodCategory.VEGETABLE),
        CatalogFood("turnip", FoodCategory.VEGETABLE),
        CatalogFood("apple", FoodCategory.FRUIT),
        CatalogFood("apricot", FoodCategory.FRUIT),
        CatalogFood("banana", FoodCategory.FRUIT),
        CatalogFood("blueberries", FoodCategory.FRUIT),
        CatalogFood("cherries", FoodCategory.FRUIT),
        CatalogFood("clementine", FoodCategory.FRUIT),
        CatalogFood("fig", FoodCategory.FRUIT),
        CatalogFood("grapes", FoodCategory.FRUIT),
        CatalogFood("kiwi", FoodCategory.FRUIT),
        CatalogFood("mango", FoodCategory.FRUIT),
        CatalogFood("melon", FoodCategory.FRUIT),
        CatalogFood("nectarine", FoodCategory.FRUIT),
        CatalogFood("orange", FoodCategory.FRUIT),
        CatalogFood("peach", FoodCategory.FRUIT),
        CatalogFood("pear", FoodCategory.FRUIT),
        CatalogFood("pineapple", FoodCategory.FRUIT),
        CatalogFood("plum", FoodCategory.FRUIT),
        CatalogFood("raspberries", FoodCategory.FRUIT),
        CatalogFood("strawberries", FoodCategory.FRUIT),
        CatalogFood("watermelon", FoodCategory.FRUIT),
        CatalogFood("blackberries", FoodCategory.FRUIT),
        CatalogFood("blackcurrants", FoodCategory.FRUIT),
        CatalogFood("coconut", FoodCategory.FRUIT),
        CatalogFood("dates", FoodCategory.FRUIT),
        CatalogFood("grapefruit", FoodCategory.FRUIT),
        CatalogFood("guava", FoodCategory.FRUIT),
        CatalogFood("lemon", FoodCategory.FRUIT),
        CatalogFood("lychee", FoodCategory.FRUIT),
        CatalogFood("mandarin", FoodCategory.FRUIT),
        CatalogFood("papaya", FoodCategory.FRUIT),
        CatalogFood("passion_fruit", FoodCategory.FRUIT),
        CatalogFood("persimmon", FoodCategory.FRUIT),
        CatalogFood("pomegranate", FoodCategory.FRUIT),
        CatalogFood("prunes", FoodCategory.FRUIT),
        CatalogFood("quince", FoodCategory.FRUIT),
        CatalogFood("raisins", FoodCategory.FRUIT),
        CatalogFood("redcurrants", FoodCategory.FRUIT),
        CatalogFood("rhubarb", FoodCategory.FRUIT),
        CatalogFood("beef", FoodCategory.MEAT),
        CatalogFood("chicken", FoodCategory.MEAT),
        CatalogFood("duck", FoodCategory.MEAT),
        CatalogFood("ham", FoodCategory.MEAT),
        CatalogFood("lamb", FoodCategory.MEAT),
        CatalogFood("pork", FoodCategory.MEAT),
        CatalogFood("rabbit", FoodCategory.MEAT),
        CatalogFood("turkey", FoodCategory.MEAT),
        CatalogFood("veal", FoodCategory.MEAT),
        CatalogFood("cod", FoodCategory.FISH),
        CatalogFood("hake", FoodCategory.FISH),
        CatalogFood("mackerel", FoodCategory.FISH),
        CatalogFood("salmon", FoodCategory.FISH),
        CatalogFood("sardines", FoodCategory.FISH),
        CatalogFood("sea_bass", FoodCategory.FISH),
        CatalogFood("sea_bream", FoodCategory.FISH),
        CatalogFood("sole", FoodCategory.FISH),
        CatalogFood("trout", FoodCategory.FISH),
        CatalogFood("tuna", FoodCategory.FISH),
    )

    private val byId = foods.associateBy { it.id }

    /** The food behind [id], or null for a food this version does not know. */
    fun find(id: String?): CatalogFood? = id?.let { byId[it] }
}
