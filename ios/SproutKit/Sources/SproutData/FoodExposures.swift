import Foundation

// The *First foods* page's arithmetic (BDR-20): which foods a baby has been
// offered, how many times, and how it went. From `data/FoodExposures.kt`.
//
// Nothing here is stored. A solid meal lists its foods and these figures are
// read back from the meals every time they are asked for, so correcting or
// deleting a meal moves them — the same reason the medicine traffic light is
// derived from its doses rather than kept.
//
// Pure functions, tested with the same cases as `FoodExposuresTest.kt`: a
// household with one phone of each has to see the same count.

/// The name two spellings of one food share: trimmed, inner runs of spacing
/// collapsed to one space, and lower-cased without regard to the phone's
/// language — so "Carrot", "carrot" and " carrot " are one food on every phone.
///
/// Whitespace is Unicode's own, a no-break space included, which is what
/// Kotlin's `Char.isWhitespace` reads on the other phone.
///
/// Nothing cleverer. "Carrots" stays apart from "carrot": Sprout does not know
/// the language well enough to say they are one, and guessing would merge foods
/// a parent meant apart.
public func foodKey(_ name: String) -> String {
    name.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
}

/// What makes two foods on two meals the same food: the list's id for a food from
/// Sprout's list — "Carotte" logged in French and "Carrot" logged in English are
/// one carrot — and ``foodKey(_:)`` of the name for a food the household typed
/// in. Never the phone's language, so both phones count alike.
public func foodIdentity(_ food: MealFood) -> String {
    if let ref = food.ref { return "ref:\(ref)" }
    return foodKey(food.name)
}

/// One meal a food was part of.
public struct FoodOffer: Equatable, Sendable {
    /// The meal's uid, so the page can open it to correct.
    public let feedUid: String
    public let time: Int64
    public let portion: FoodPortion
    public let reception: FoodReception?
}

/// Everything one food has been through: the meals it was in, newest first.
///
/// `key` is its ``foodIdentity(_:)``. `name` is how it was written most
/// recently — a parent who corrects "carot" to "Carrot" sees the correction, not
/// the first spelling — and `ref` is its id on Sprout's list, when it is from
/// there, which is what the page names it by.
public struct FoodHistory: Equatable, Sendable, Identifiable {
    public let key: String
    public let name: String
    public let offers: [FoodOffer]
    public var ref: String?

    public var id: String { key }

    /// How many meals included it — a taste is an offer too.
    public var timesOffered: Int { offers.count }
    public var tastes: Int { offers.filter { $0.portion == .TASTE }.count }
    public var servings: Int { offers.filter { $0.portion == .SERVING }.count }
    public var firstOffered: Int64 { offers[offers.count - 1].time }
    public var lastOffered: Int64 { offers[0].time }

    /// The portion it was given last time — what the form starts it on.
    public var lastPortion: FoodPortion { offers[0].portion }

    /// How many meals it went `reception` at; meals that noted nothing count for
    /// none.
    public func timesReceived(_ reception: FoodReception) -> Int {
        offers.filter { $0.reception == reception }.count
    }
}

/// Every food in `feeds`, most recently offered first.
///
/// Only solid meals that still exist count; the caller decides whose meals they
/// are (one baby for the page, the whole household for the form's suggestions).
/// A food named twice in one meal is one offer of it: a serving if either was,
/// and the first "how it went" that was noted. Two meals at the same moment are
/// taken in `uid` order, so both phones agree on which spelling is the latest.
public func foodHistories(_ feeds: [Feeding]) -> [FoodHistory] {
    let meals = feeds
        .filter { $0.type == .SOLID && $0.deletedAt == nil && !$0.foods.isEmpty }
        .sorted { a, b in
            a.startTime != b.startTime ? a.startTime > b.startTime : a.uid < b.uid
        }

    var order: [String] = []
    var latest: [String: MealFood] = [:]
    var offers: [String: [FoodOffer]] = [:]
    for meal in meals {
        // One offer per food per meal, in the order the meal listed them.
        var inMealOrder: [String] = []
        var inMeal: [String: FoodOffer] = [:]
        for food in meal.mealFoods {
            if foodKey(food.name).isEmpty && food.ref == nil { continue }
            let key = foodIdentity(food)
            if latest[key] == nil {
                latest[key] = food
                order.append(key)
            }
            if let seen = inMeal[key] {
                inMeal[key] = FoodOffer(
                    feedUid: seen.feedUid,
                    time: seen.time,
                    portion: food.portion == .SERVING ? .SERVING : seen.portion,
                    reception: seen.reception ?? food.reception
                )
            } else {
                inMeal[key] = FoodOffer(
                    feedUid: meal.uid, time: meal.startTime,
                    portion: food.portion, reception: food.reception
                )
                inMealOrder.append(key)
            }
        }
        for key in inMealOrder {
            offers[key, default: []].append(inMeal[key]!)
        }
    }
    // `order` was filled newest meal first, so it is already "most recently
    // offered first".
    return order.map { key in
        let food = latest[key]!
        return FoodHistory(
            key: key,
            name: food.name.trimmingCharacters(in: .whitespacesAndNewlines),
            offers: offers[key]!,
            ref: food.ref
        )
    }
}

/// A meal's foods once the food `from` (a ``foodIdentity(_:)``) has been
/// corrected to `to` — "carottes" typed by hand made into the carrot from the
/// list, say, or a typo put right. Nil when the meal does not name `from`, so the
/// caller can leave that row alone.
///
/// The corrected food keeps the portion and the "how it went" it was logged
/// with: only what it *is* changes. Where the meal already named the food it
/// becomes, the two are one offer, read the way ``foodHistories(_:)`` reads a
/// food named twice — a serving if either was, the first answer that was noted.
public func relabelledFoods(_ foods: [MealFood], from: String, to: MealFood) -> [MealFood]? {
    guard foods.contains(where: { foodIdentity($0) == from }) else { return nil }
    var order: [String] = []
    var merged: [String: MealFood] = [:]
    for food in foods {
        var corrected = food
        if foodIdentity(food) == from {
            corrected.name = to.name
            corrected.ref = to.ref
        }
        let key = foodIdentity(corrected)
        if var seen = merged[key] {
            if corrected.portion == .SERVING { seen.portion = .SERVING }
            seen.reception = seen.reception ?? corrected.reception
            merged[key] = seen
        } else {
            merged[key] = corrected
            order.append(key)
        }
    }
    return order.map { merged[$0]! }
}
