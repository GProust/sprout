import SproutData

/// Each food on the list, by id, to its name in the phone's language (BDR-20).
///
/// A switch of literal keys rather than `"food_item_\(id)"`, so that
/// `check_string_keys.py` can see every one of them — a key it cannot read is a
/// food that renders as its own identifier in one language and nobody notices.
func catalogName(_ id: String?) -> String? {
    guard let id else { return nil }
    switch id {
        case "artichoke": return Str.t("food_item_artichoke")
        case "asparagus": return Str.t("food_item_asparagus")
        case "aubergine": return Str.t("food_item_aubergine")
        case "avocado": return Str.t("food_item_avocado")
        case "beetroot": return Str.t("food_item_beetroot")
        case "broccoli": return Str.t("food_item_broccoli")
        case "butternut": return Str.t("food_item_butternut")
        case "cabbage": return Str.t("food_item_cabbage")
        case "carrot": return Str.t("food_item_carrot")
        case "cauliflower": return Str.t("food_item_cauliflower")
        case "celeriac": return Str.t("food_item_celeriac")
        case "chickpeas": return Str.t("food_item_chickpeas")
        case "courgette": return Str.t("food_item_courgette")
        case "cucumber": return Str.t("food_item_cucumber")
        case "fennel": return Str.t("food_item_fennel")
        case "green_beans": return Str.t("food_item_green_beans")
        case "leek": return Str.t("food_item_leek")
        case "lentils": return Str.t("food_item_lentils")
        case "mushrooms": return Str.t("food_item_mushrooms")
        case "parsnip": return Str.t("food_item_parsnip")
        case "peas": return Str.t("food_item_peas")
        case "potato": return Str.t("food_item_potato")
        case "pumpkin": return Str.t("food_item_pumpkin")
        case "spinach": return Str.t("food_item_spinach")
        case "sweet_pepper": return Str.t("food_item_sweet_pepper")
        case "sweet_potato": return Str.t("food_item_sweet_potato")
        case "sweetcorn": return Str.t("food_item_sweetcorn")
        case "tomato": return Str.t("food_item_tomato")
        case "turnip": return Str.t("food_item_turnip")
        case "beef": return Str.t("food_item_beef")
        case "chicken": return Str.t("food_item_chicken")
        case "duck": return Str.t("food_item_duck")
        case "ham": return Str.t("food_item_ham")
        case "lamb": return Str.t("food_item_lamb")
        case "pork": return Str.t("food_item_pork")
        case "rabbit": return Str.t("food_item_rabbit")
        case "turkey": return Str.t("food_item_turkey")
        case "veal": return Str.t("food_item_veal")
        case "cod": return Str.t("food_item_cod")
        case "hake": return Str.t("food_item_hake")
        case "mackerel": return Str.t("food_item_mackerel")
        case "salmon": return Str.t("food_item_salmon")
        case "sardines": return Str.t("food_item_sardines")
        case "sea_bass": return Str.t("food_item_sea_bass")
        case "sea_bream": return Str.t("food_item_sea_bream")
        case "sole": return Str.t("food_item_sole")
        case "trout": return Str.t("food_item_trout")
        case "tuna": return Str.t("food_item_tuna")
    default: return nil
    }
}

extension FoodCategory {
    var label: String {
        switch self {
        case .VEGETABLE: return Str.t("food_category_vegetable")
        case .MEAT: return Str.t("food_category_meat")
        case .FISH: return Str.t("food_category_fish")
        }
    }
}

extension MealFood {
    /// What a food on a meal is called on this phone: a food from the list in
    /// this phone's language — the other parent may have logged it in theirs —
    /// and one the household added in the words it was typed in.
    var displayName: String { catalogName(ref) ?? name }
}

extension FoodHistory {
    var displayName: String { catalogName(ref) ?? name }
}
