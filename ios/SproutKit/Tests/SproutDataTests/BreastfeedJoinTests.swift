import XCTest
@testable import SproutData

/// Two breastfeeds saved apart can be joined into the one feed they were
/// (BDR-19), and the whole feature is one rule: **a join is a pause that
/// happened after the fact**. The gap between the two becomes a break, and a
/// break is not time at the breast.
///
/// The same cases as `BreastfeedJoinTest.kt`, because a household with one
/// phone of each has to offer the same join on the same two feeds, and get the
/// same feed out of it.
final class BreastfeedJoinTests: XCTestCase {

    private let start: Int64 = 1_750_000_000_000
    private func min(_ n: Int64) -> Int64 { n * 60_000 }

    /// A breastfeed timed stretch by stretch, the way the timer saves one.
    private func feed(_ segments: NursingSegment..., babyId: Int64 = 1, notes: String? = nil) -> Feeding {
        Feeding(
            babyId: babyId,
            type: .BREAST,
            startTime: segments.first!.startTime,
            endTime: segments.last!.endTime,
            segments: segments,
            notes: notes
        )
    }

    private func left(_ from: Int64, _ to: Int64) -> NursingSegment {
        NursingSegment(side: .LEFT, startTime: start + min(from), endTime: start + min(to))
    }

    private func right(_ from: Int64, _ to: Int64) -> NursingSegment {
        NursingSegment(side: .RIGHT, startTime: start + min(from), endTime: start + min(to))
    }

    // The example the feature was asked for: the left side, a burp, the right.
    private lazy var first: Feeding = feed(left(0, 9))
    private lazy var second: Feeding = feed(right(14, 20))

    func testTwoFeedsFiveMinutesApartBecomeOneWithTheGapAsABreak() {
        XCTAssertEqual(breastfeedJoinGap(first, second), min(5))

        let joined = joinedBreastfeed(first, second)

        XCTAssertEqual(joined.startTime, start)
        XCTAssertEqual(joined.endTime, start + min(20))
        XCTAssertEqual(joined.nursingSegments, [left(0, 9), right(14, 20)])
        XCTAssertEqual(joined.side, .BOTH)
        XCTAssertEqual(joined.leftDurationMs, min(9))
        XCTAssertEqual(joined.rightDurationMs, min(6))
        // Fifteen minutes at the breast, not twenty: the burp is not feeding.
        XCTAssertEqual(breastfeedMillis(joined), min(15))
        XCTAssertEqual(breastfeedPausedMillis(joined.nursingSegments), min(5))
    }

    func testTheEarlierFeedIsTheOneThatStays() {
        let joined = joinedBreastfeed(first, second)

        XCTAssertEqual(joined.uid, first.uid)
        XCTAssertEqual(joined.id, first.id)
        XCTAssertEqual(joined.type, .BREAST)
    }

    func testFeedsSavedBackToBackJoinWithNoBreak() {
        let next = feed(right(9, 15))

        XCTAssertEqual(breastfeedJoinGap(first, next), 0)
        XCTAssertEqual(breastfeedPausedMillis(joinedBreastfeed(first, next).nursingSegments), 0)
    }

    func testTheOfferReachesHalfAnHourAndNoFurther() {
        XCTAssertEqual(breastfeedJoinMaxGapMs, min(30))
        XCTAssertEqual(breastfeedJoinGap(first, feed(right(39, 45))), min(30))
        XCTAssertNil(
            breastfeedJoinGap(
                first,
                feed(NursingSegment(side: .RIGHT, startTime: start + min(39) + 1, endTime: start + min(45)))
            )
        )
    }

    func testFeedsThatOverlapCannotBeJoined() {
        XCTAssertNil(breastfeedJoinGap(first, feed(right(8, 12))))
    }

    func testTheOrderMatters() {
        XCTAssertNil(breastfeedJoinGap(second, first))
    }

    func testOnlyBreastfeedsJoin() {
        let bottle = Feeding(babyId: 1, type: .BOTTLE, amountMl: 90, startTime: start + min(14))

        XCTAssertNil(breastfeedJoinGap(first, bottle))
        var before = bottle
        before.startTime = start - min(5)
        XCTAssertNil(breastfeedJoinGap(before, first))
    }

    func testAFeedWithNoStretchesHasNothingToLineUp() {
        // Logged with a start and a length, before per-side timing existed.
        let untimed = Feeding(
            babyId: 1,
            type: .BREAST,
            side: .LEFT,
            startTime: start,
            endTime: start + min(9)
        )
        var laterUntimed = untimed
        laterUntimed.uid = "a-later-untimed-feed"
        laterUntimed.startTime = start + min(14)
        laterUntimed.endTime = start + min(20)

        XCTAssertNil(breastfeedJoinGap(untimed, second))
        XCTAssertNil(breastfeedJoinGap(first, laterUntimed))
    }

    func testTwinsFeedsAreNeverJoined() {
        XCTAssertNil(breastfeedJoinGap(first, feed(right(14, 20), babyId: 2)))
    }

    func testAFeedCannotBeJoinedToItself() {
        XCTAssertNil(breastfeedJoinGap(first, first))
    }

    func testTheSameSideTwiceStaysThatSide() {
        let joined = joinedBreastfeed(first, feed(left(14, 20)))

        XCTAssertEqual(joined.side, .LEFT)
        XCTAssertEqual(joined.leftDurationMs, min(15))
        XCTAssertNil(joined.rightDurationMs)
    }

    func testBreaksAlreadyInEitherFeedAreKept() {
        // The first feed was paused once already, for three minutes.
        let paused = feed(left(0, 5), left(8, 12))
        let next = feed(right(17, 23))

        let joined = joinedBreastfeed(paused, next)

        XCTAssertEqual(breastfeedPausedMillis(joined.nursingSegments), min(3 + 5))
        XCTAssertEqual(breastfeedMillis(joined), min(15))
    }

    func testNotesFromBothAreKept() {
        func with(_ feeding: Feeding, notes: String?) -> Feeding {
            var copy = feeding
            copy.notes = notes
            return copy
        }

        XCTAssertEqual(
            joinedBreastfeed(with(first, notes: "Sleepy"), with(second, notes: " Hiccups ")).notes,
            "Sleepy\nHiccups"
        )
        XCTAssertEqual(
            joinedBreastfeed(with(first, notes: " "), with(second, notes: "Hiccups")).notes,
            "Hiccups"
        )
        XCTAssertEqual(
            joinedBreastfeed(with(first, notes: "Sleepy"), with(second, notes: "Sleepy")).notes,
            "Sleepy"
        )
        XCTAssertNil(joinedBreastfeed(first, second).notes)
    }

    func testTheOfferIsOnTheLaterFeedAndNamesTheEarlierOne() {
        // Newest first, the way the history holds them.
        let offers = breastfeedJoinOffers([second, first])

        XCTAssertEqual(offers, [second.uid: first])
    }

    func testAnythingLoggedBetweenMeansTheyWereTwoFeeds() {
        let bottle = Feeding(babyId: 1, type: .BOTTLE, amountMl: 60, startTime: start + min(11))

        XCTAssertTrue(breastfeedJoinOffers([second, bottle, first]).isEmpty)
    }

    func testARunOfFeedsIsOfferedPairByPair() {
        let third = feed(left(25, 31))

        let offers = breastfeedJoinOffers([third, second, first])

        XCTAssertEqual(offers, [second.uid: first, third.uid: second])
    }

    func testFeedsFarApartAreNotOffered() {
        let later = feed(right(120, 130))

        XCTAssertTrue(breastfeedJoinOffers([later, first]).isEmpty)
    }
}
