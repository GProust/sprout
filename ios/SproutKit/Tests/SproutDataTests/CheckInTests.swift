import XCTest
@testable import SproutData

/// The counterpart of `CheckInLogicTest.kt`, question for question.
///
/// It is worth having twice because the *shape* of the check-in is a product
/// decision (BDR-0001, BDR-0006), not an implementation detail: a parent asked a
/// question that does not apply to them, or not asked one that does, is the
/// screen failing at the only thing it does.
final class CheckInTests: XCTestCase {

    private let t: Int64 = 1_700_000_000_000
    private let twoDays: Int64 = 2 * 24 * 60 * 60_000

    func testNeedsCheckInWhenNeverOrOnAnotherDay() {
        XCTAssertTrue(needsCheckIn(lastCheckIn: nil, now: t), "never checked in")
        XCTAssertTrue(needsCheckIn(lastCheckIn: t, now: t + twoDays), "checked in two days ago")
        XCTAssertFalse(needsCheckIn(lastCheckIn: t, now: t), "already checked in today")
    }

    func testOfferedOnlyWhileTrackingAndNotDoneToday() {
        XCTAssertTrue(shouldOfferCheckIn(trackWellbeing: true, lastCheckIn: nil, now: t))
        XCTAssertTrue(shouldOfferCheckIn(trackWellbeing: true, lastCheckIn: t, now: t + twoDays))
        XCTAssertFalse(shouldOfferCheckIn(trackWellbeing: true, lastCheckIn: t, now: t))

        // And never once the parent has stopped tracking their own wellbeing.
        XCTAssertFalse(shouldOfferCheckIn(trackWellbeing: false, lastCheckIn: nil, now: t))
        XCTAssertFalse(shouldOfferCheckIn(trackWellbeing: false, lastCheckIn: t, now: t + twoDays))
    }

    /// Capability, not role: the same flow has to fit an adoptive father, a
    /// birth mother, and a partner who induced lactation.
    func testTheQuestionsFollowTheCapabilities() {
        XCTAssertEqual(
            checkInQuestions(gaveBirth: false, breastfeeding: false),
            [.mood, .notes],
            "everyone gets mood and notes; nobody is left out"
        )
        XCTAssertEqual(
            checkInQuestions(gaveBirth: true, breastfeeding: false),
            [.mood, .healing, .bleeding, .notes]
        )
        XCTAssertEqual(
            checkInQuestions(gaveBirth: false, breastfeeding: true),
            [.mood, .breasts, .notes],
            "induced lactation without having given birth"
        )
        XCTAssertEqual(
            checkInQuestions(gaveBirth: true, breastfeeding: true),
            [.mood, .healing, .bleeding, .breasts, .notes]
        )
    }

    func testABodyQuestionCanBeRetiredWithoutTakingTheRestWithIt() {
        XCTAssertEqual(
            checkInQuestions(gaveBirth: true, breastfeeding: false, askHealing: false),
            [.mood, .bleeding, .notes]
        )
        XCTAssertEqual(
            checkInQuestions(gaveBirth: true, breastfeeding: false, askBleeding: false),
            [.mood, .healing, .notes]
        )
        XCTAssertEqual(
            checkInQuestions(gaveBirth: false, breastfeeding: true, askBreasts: false),
            [.mood, .notes]
        )
    }

    /// Opting out of everything still leaves a check-in. Mood and notes are what
    /// it *is*; without them there is no screen to show.
    func testTheCoreCannotBeOptedOutOf() {
        XCTAssertEqual(
            checkInQuestions(
                gaveBirth: true,
                breastfeeding: true,
                askHealing: false,
                askBleeding: false,
                askBreasts: false
            ),
            [.mood, .notes]
        )
        let core: [CheckInQuestion] = [.mood, .notes]
        for question in CheckInQuestion.allCases {
            XCTAssertEqual(
                question.isOptional,
                !core.contains(question),
                "\(question) is on the wrong side of the opt-out line"
            )
        }
    }
}
