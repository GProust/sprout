import XCTest
@testable import SproutData

/// The First foods page's arithmetic (BDR-20), with the same cases as
/// `FoodExposuresTest.kt`: a household with one phone of each has to see the
/// same count for the same carrot.
final class FoodExposuresTests: XCTestCase {

    private let day: Int64 = 24 * 60 * 60_000
    private let t0: Int64 = 1_760_000_000_000

    private func meal(
        _ uid: String,
        _ at: Int64,
        _ foods: MealFood...,
        type: FeedType = .SOLID,
        deletedAt: Int64? = nil
    ) -> Feeding {
        Feeding(type: type, startTime: at, foods: foods, uid: uid, deletedAt: deletedAt)
    }

    private func food(
        _ name: String,
        _ portion: FoodPortion = .TASTE,
        _ reception: FoodReception? = nil
    ) -> MealFood {
        MealFood(name: name, portion: portion, reception: reception)
    }

    func testAFoodIsItsNameWithoutRegardToCaseOrSpacing() {
        XCTAssertEqual(foodKey("Carrot"), "carrot")
        XCTAssertEqual(foodKey("  CARROT "), "carrot")
        XCTAssertEqual(foodKey("Sweet \t  potato"), "sweet potato")
        // A no-break space, as a name pasted from a recipe might carry.
        XCTAssertEqual(foodKey("sweet\u{00A0}potato"), "sweet potato")
        XCTAssertEqual(foodKey("Œuf"), "œuf")
        XCTAssertEqual(foodKey("   "), "")
        // Sprout does not know the language well enough to merge these.
        XCTAssertNotEqual(foodKey("carrot"), foodKey("carrots"))
    }

    func testEveryMealAFoodWasInIsOneOfferTastesAndServingsApart() {
        let foods = foodHistories([
            meal("m1", t0, food("carrot")),
            meal("m2", t0 + day, food("carrot", .SERVING, .LIKED), food("Pea")),
            meal("m3", t0 + 2 * day, food("Carrot", .SERVING, .REFUSED)),
        ])

        XCTAssertEqual(foods.map(\.key), ["carrot", "pea"])
        let carrot = foods[0]
        XCTAssertEqual(carrot.timesOffered, 3)
        XCTAssertEqual(carrot.tastes, 1)
        XCTAssertEqual(carrot.servings, 2)
        XCTAssertEqual(carrot.timesReceived(.LIKED), 1)
        XCTAssertEqual(carrot.timesReceived(.REFUSED), 1)
        XCTAssertEqual(carrot.timesReceived(.MIXED), 0)
        XCTAssertEqual(carrot.firstOffered, t0)
        XCTAssertEqual(carrot.lastOffered, t0 + 2 * day)
        XCTAssertEqual(carrot.lastPortion, .SERVING)
        XCTAssertEqual(carrot.offers.map(\.feedUid), ["m3", "m2", "m1"])
    }

    func testAFoodIsShownAsItWasMostRecentlyWritten() {
        let foods = foodHistories([
            meal("m1", t0 + day, food("Carrot")),
            meal("m2", t0, food("carrot")),
        ])

        XCTAssertEqual(foods.map(\.name), ["Carrot"])
    }

    func testTheFoodsAreInTheOrderTheyWereLastOffered() {
        let foods = foodHistories([
            meal("m1", t0, food("Apple"), food("Pear")),
            meal("m2", t0 + day, food("Banana")),
            meal("m3", t0 + 2 * day, food("Pear")),
        ])

        // Pear was last offered most recently; Apple and Banana keep their own
        // last time, and one meal's foods keep the order it listed them.
        XCTAssertEqual(foods.map(\.name), ["Pear", "Banana", "Apple"])
    }

    func testAFoodNamedTwiceInOneMealIsOneOfferOfIt() throws {
        let pea = try XCTUnwrap(
            foodHistories([meal("m1", t0, food("Pea"), food(" pea ", .SERVING, .MIXED))]).first
        )

        XCTAssertEqual(pea.timesOffered, 1)
        XCTAssertEqual(pea.lastPortion, .SERVING, "a serving if either was")
        XCTAssertEqual(pea.timesReceived(.MIXED), 1, "and the first answer that was noted")
        XCTAssertEqual(pea.name, "Pea")
    }

    func testOnlySolidMealsThatStillExistCountAndABlankNameIsNoFood() {
        let foods = foodHistories([
            meal("bottle", t0, food("Carrot"), type: .BOTTLE),
            meal("gone", t0, food("Carrot"), deletedAt: t0 + 1),
            meal("blank", t0, food("   ")),
            meal("plain", t0),
        ])

        XCTAssertEqual(foods, [])
    }

    func testTwoMealsAtTheSameMomentAreReadInUidOrderSoBothPhonesAgree() throws {
        let carrot = try XCTUnwrap(
            foodHistories([
                meal("b", t0, food("Carrot")),
                meal("a", t0, food("carrot", .SERVING)),
            ]).first
        )

        XCTAssertEqual(carrot.name, "carrot")
        XCTAssertEqual(carrot.offers.map(\.feedUid), ["a", "b"])
        XCTAssertEqual(carrot.lastPortion, .SERVING)
    }

    func testAFoodFromTheListIsOneFoodInEveryLanguage() {
        let foods = foodHistories([
            meal("m1", t0, MealFood(name: "Carotte", portion: .TASTE, ref: "carrot")),
            meal("m2", t0 + day, MealFood(name: "Carrot", portion: .SERVING, ref: "carrot")),
            // Typed in, not picked from the list: the household's own food, which
            // a carrot from the list never silently becomes.
            meal("m3", t0 + 2 * day, food("Carrot")),
        ])

        XCTAssertEqual(foods.map(\.key), ["carrot", "ref:carrot"])
        let listed = foods[1]
        XCTAssertEqual(listed.timesOffered, 2)
        XCTAssertEqual(listed.ref, "carrot")
        XCTAssertEqual(listed.name, "Carrot", "the latest name it was saved with")
        XCTAssertNil(foods[0].ref)
        XCTAssertEqual(foodIdentity(MealFood(name: "Zanahoria", ref: "carrot")), "ref:carrot")
        XCTAssertEqual(foodIdentity(MealFood(name: " Carrot ")), "carrot")
    }

    /// Sprout's food list against the ids Node wrote down (BDR-20). A `ref` one
    /// phone knows and the other does not is a carrot one of them can name and
    /// the other shows by whatever it was called where it was logged.
    func testTheFoodListMatchesTheSpec() throws {
        let vector = try Vectors.load("food-catalog.json")
        XCTAssertEqual(vector["categories"] as? [String], FoodCategory.allCases.map(\.rawValue))
        let foods = try XCTUnwrap(vector["foods"] as? [[String: String]])
        XCTAssertEqual(foods.map { $0["id"] ?? "" }, FoodCatalog.foods.map(\.id))
        XCTAssertEqual(foods.map { $0["category"] ?? "" }, FoodCatalog.foods.map(\.category.rawValue))
    }

    func testNothingNotedIsNotAnAnswer() throws {
        let carrot = try XCTUnwrap(foodHistories([meal("m1", t0, food("Carrot"))]).first)

        XCTAssertNil(carrot.offers.first?.reception)
        for reception in FoodReception.allCases {
            XCTAssertEqual(carrot.timesReceived(reception), 0)
        }
        XCTAssertEqual(carrot.lastPortion, .TASTE, "a new food starts as a taste")
    }

    func testAFoodCorrectedToOneFromTheListTakesItsIdAndKeepsHowItWasGiven() {
        let corrected = relabelledFoods(
            [food("carottes", .SERVING, .LIKED), food("Pea")],
            from: "carottes",
            to: MealFood(name: "Carotte", ref: "carrot")
        )

        XCTAssertEqual(
            corrected,
            [MealFood(name: "Carotte", portion: .SERVING, reception: .LIKED, ref: "carrot"), food("Pea")]
        )
    }

    func testAMealThatAlreadyNamedTheFoodItBecomesKeepsOneOfferOfIt() {
        let corrected = relabelledFoods(
            [MealFood(name: "Carotte", portion: .TASTE, ref: "carrot"), food("carottes", .SERVING, .MIXED)],
            from: "carottes",
            to: MealFood(name: "Carotte", ref: "carrot")
        )

        XCTAssertEqual(corrected, [MealFood(name: "Carotte", portion: .SERVING, reception: .MIXED, ref: "carrot")])
    }

    func testAMealThatDoesNotNameTheFoodIsLeftAlone() {
        XCTAssertNil(relabelledFoods([food("Pea")], from: "carottes", to: food("Carrot")))
    }

    func testAfterACorrectionTheTwoLinesAreOneFood() throws {
        let meals = [
            meal("m1", t0, food("carottes")),
            meal("m2", t0 + day, MealFood(name: "Carotte", portion: .SERVING, ref: "carrot")),
        ]
        let fixed = meals.map { m -> Feeding in
            guard let foods = relabelledFoods(m.mealFoods, from: "carottes", to: MealFood(name: "Carotte", ref: "carrot"))
            else { return m }
            var copy = m
            copy.mealFoods = foods
            return copy
        }

        let carrot = try XCTUnwrap(foodHistories(fixed).first)
        XCTAssertEqual(foodHistories(fixed).count, 1)
        XCTAssertEqual(carrot.key, "ref:carrot")
        XCTAssertEqual(carrot.timesOffered, 2)
    }
}
