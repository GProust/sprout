import Foundation

/// The shelf a food on the list sits on. Vegetables, fruit, meat and fish: the list
/// is a convenience for the foods every family names, not a map of what a baby
/// should eat (BDR-20).
public enum FoodCategory: String, CaseIterable, Sendable { case VEGETABLE, FRUIT, MEAT, FISH }

/// One food on Sprout's list. `id` is what travels with the meal
/// (``MealFood/ref``), so a carrot logged on a phone in French and one logged on
/// a phone in English are the same carrot; the name a parent reads is looked up
/// from it in their own language.
public struct CatalogFood: Equatable, Hashable, Sendable {
    public let id: String
    public let category: FoodCategory
}

/// The list the meal form searches (BDR-20), from `data/FoodCatalog.kt`: plain
/// names in seven languages and nothing else — no ages, no order of
/// introduction, no allergen marks, nothing suggested. Anything not on it is
/// typed in and kept as the household's own.
///
/// The ids are pinned by `spec/vectors/food-catalog.json`, which the Android list
/// is held to as well. Add to the end of a category, never rename or remove an
/// id — it is in every meal that used it.
public enum FoodCatalog {
    public static let foods: [CatalogFood] = [
        CatalogFood(id: "artichoke", category: .VEGETABLE),
        CatalogFood(id: "asparagus", category: .VEGETABLE),
        CatalogFood(id: "aubergine", category: .VEGETABLE),
        CatalogFood(id: "avocado", category: .VEGETABLE),
        CatalogFood(id: "beetroot", category: .VEGETABLE),
        CatalogFood(id: "broccoli", category: .VEGETABLE),
        CatalogFood(id: "butternut", category: .VEGETABLE),
        CatalogFood(id: "cabbage", category: .VEGETABLE),
        CatalogFood(id: "carrot", category: .VEGETABLE),
        CatalogFood(id: "cauliflower", category: .VEGETABLE),
        CatalogFood(id: "celeriac", category: .VEGETABLE),
        CatalogFood(id: "chickpeas", category: .VEGETABLE),
        CatalogFood(id: "courgette", category: .VEGETABLE),
        CatalogFood(id: "cucumber", category: .VEGETABLE),
        CatalogFood(id: "fennel", category: .VEGETABLE),
        CatalogFood(id: "green_beans", category: .VEGETABLE),
        CatalogFood(id: "leek", category: .VEGETABLE),
        CatalogFood(id: "lentils", category: .VEGETABLE),
        CatalogFood(id: "mushrooms", category: .VEGETABLE),
        CatalogFood(id: "parsnip", category: .VEGETABLE),
        CatalogFood(id: "peas", category: .VEGETABLE),
        CatalogFood(id: "potato", category: .VEGETABLE),
        CatalogFood(id: "pumpkin", category: .VEGETABLE),
        CatalogFood(id: "spinach", category: .VEGETABLE),
        CatalogFood(id: "sweet_pepper", category: .VEGETABLE),
        CatalogFood(id: "sweet_potato", category: .VEGETABLE),
        CatalogFood(id: "sweetcorn", category: .VEGETABLE),
        CatalogFood(id: "tomato", category: .VEGETABLE),
        CatalogFood(id: "turnip", category: .VEGETABLE),
        CatalogFood(id: "apple", category: .FRUIT),
        CatalogFood(id: "apricot", category: .FRUIT),
        CatalogFood(id: "banana", category: .FRUIT),
        CatalogFood(id: "blueberries", category: .FRUIT),
        CatalogFood(id: "cherries", category: .FRUIT),
        CatalogFood(id: "clementine", category: .FRUIT),
        CatalogFood(id: "fig", category: .FRUIT),
        CatalogFood(id: "grapes", category: .FRUIT),
        CatalogFood(id: "kiwi", category: .FRUIT),
        CatalogFood(id: "mango", category: .FRUIT),
        CatalogFood(id: "melon", category: .FRUIT),
        CatalogFood(id: "nectarine", category: .FRUIT),
        CatalogFood(id: "orange", category: .FRUIT),
        CatalogFood(id: "peach", category: .FRUIT),
        CatalogFood(id: "pear", category: .FRUIT),
        CatalogFood(id: "pineapple", category: .FRUIT),
        CatalogFood(id: "plum", category: .FRUIT),
        CatalogFood(id: "raspberries", category: .FRUIT),
        CatalogFood(id: "strawberries", category: .FRUIT),
        CatalogFood(id: "watermelon", category: .FRUIT),
        CatalogFood(id: "blackberries", category: .FRUIT),
        CatalogFood(id: "blackcurrants", category: .FRUIT),
        CatalogFood(id: "coconut", category: .FRUIT),
        CatalogFood(id: "dates", category: .FRUIT),
        CatalogFood(id: "grapefruit", category: .FRUIT),
        CatalogFood(id: "guava", category: .FRUIT),
        CatalogFood(id: "lemon", category: .FRUIT),
        CatalogFood(id: "lychee", category: .FRUIT),
        CatalogFood(id: "mandarin", category: .FRUIT),
        CatalogFood(id: "papaya", category: .FRUIT),
        CatalogFood(id: "passion_fruit", category: .FRUIT),
        CatalogFood(id: "persimmon", category: .FRUIT),
        CatalogFood(id: "pomegranate", category: .FRUIT),
        CatalogFood(id: "prunes", category: .FRUIT),
        CatalogFood(id: "quince", category: .FRUIT),
        CatalogFood(id: "raisins", category: .FRUIT),
        CatalogFood(id: "redcurrants", category: .FRUIT),
        CatalogFood(id: "rhubarb", category: .FRUIT),
        CatalogFood(id: "beef", category: .MEAT),
        CatalogFood(id: "chicken", category: .MEAT),
        CatalogFood(id: "duck", category: .MEAT),
        CatalogFood(id: "ham", category: .MEAT),
        CatalogFood(id: "lamb", category: .MEAT),
        CatalogFood(id: "pork", category: .MEAT),
        CatalogFood(id: "rabbit", category: .MEAT),
        CatalogFood(id: "turkey", category: .MEAT),
        CatalogFood(id: "veal", category: .MEAT),
        CatalogFood(id: "cod", category: .FISH),
        CatalogFood(id: "hake", category: .FISH),
        CatalogFood(id: "mackerel", category: .FISH),
        CatalogFood(id: "salmon", category: .FISH),
        CatalogFood(id: "sardines", category: .FISH),
        CatalogFood(id: "sea_bass", category: .FISH),
        CatalogFood(id: "sea_bream", category: .FISH),
        CatalogFood(id: "sole", category: .FISH),
        CatalogFood(id: "trout", category: .FISH),
        CatalogFood(id: "tuna", category: .FISH),
    ]

    private static let byId = Dictionary(uniqueKeysWithValues: foods.map { ($0.id, $0) })

    /// The food behind `id`, or nil for a food this version does not know.
    public static func find(_ id: String?) -> CatalogFood? {
        guard let id else { return nil }
        return byId[id]
    }
}
