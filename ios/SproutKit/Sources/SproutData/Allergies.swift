import Foundation

// Which food an allergy is about, in the terms the First foods page counts by
// (BDR-20, BDR-21). From `data/Allergies.kt`.

/// The list's id when the allergy has one, its name otherwise — a carrot marked
/// on a phone in French is the carrot on the meals logged in English.
public func allergyIdentity(_ allergy: Allergy) -> String {
    foodIdentity(MealFood(name: allergy.name, ref: allergy.ref))
}

/// The identities of the allergies that still stand — what the page and the meal
/// form mark a food with.
public func allergyIdentities(_ allergies: [Allergy]) -> Set<String> {
    Set(allergies.filter { $0.deletedAt == nil }.map(allergyIdentity))
}
