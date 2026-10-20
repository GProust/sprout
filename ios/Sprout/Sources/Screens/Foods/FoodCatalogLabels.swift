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
        case "apple": return Str.t("food_item_apple")
        case "apricot": return Str.t("food_item_apricot")
        case "banana": return Str.t("food_item_banana")
        case "blueberries": return Str.t("food_item_blueberries")
        case "cherries": return Str.t("food_item_cherries")
        case "clementine": return Str.t("food_item_clementine")
        case "fig": return Str.t("food_item_fig")
        case "grapes": return Str.t("food_item_grapes")
        case "kiwi": return Str.t("food_item_kiwi")
        case "mango": return Str.t("food_item_mango")
        case "melon": return Str.t("food_item_melon")
        case "nectarine": return Str.t("food_item_nectarine")
        case "orange": return Str.t("food_item_orange")
        case "peach": return Str.t("food_item_peach")
        case "pear": return Str.t("food_item_pear")
        case "pineapple": return Str.t("food_item_pineapple")
        case "plum": return Str.t("food_item_plum")
        case "raspberries": return Str.t("food_item_raspberries")
        case "strawberries": return Str.t("food_item_strawberries")
        case "watermelon": return Str.t("food_item_watermelon")
        case "blackberries": return Str.t("food_item_blackberries")
        case "blackcurrants": return Str.t("food_item_blackcurrants")
        case "coconut": return Str.t("food_item_coconut")
        case "dates": return Str.t("food_item_dates")
        case "grapefruit": return Str.t("food_item_grapefruit")
        case "guava": return Str.t("food_item_guava")
        case "lemon": return Str.t("food_item_lemon")
        case "lychee": return Str.t("food_item_lychee")
        case "mandarin": return Str.t("food_item_mandarin")
        case "papaya": return Str.t("food_item_papaya")
        case "passion_fruit": return Str.t("food_item_passion_fruit")
        case "persimmon": return Str.t("food_item_persimmon")
        case "pomegranate": return Str.t("food_item_pomegranate")
        case "prunes": return Str.t("food_item_prunes")
        case "quince": return Str.t("food_item_quince")
        case "raisins": return Str.t("food_item_raisins")
        case "redcurrants": return Str.t("food_item_redcurrants")
        case "rhubarb": return Str.t("food_item_rhubarb")
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
        case .FRUIT: return Str.t("food_category_fruit")
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
