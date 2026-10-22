import XCTest
@testable import SproutData

/// Twins feed together, so the phone can time a feed for each baby at once — and
/// never two for one baby (BDR-23). These are the rules for what each tap does
/// to the list of running feeds.
///
/// The same cases as `NursingSessionsTest.kt`, because a household with one
/// phone of each has to agree on what a tap does.
final class NursingSessionsTests: XCTestCase {

    private let start: Int64 = 1_750_000_000_000
    private func min(_ n: Int64) -> Int64 { n * 60_000 }

    private func feed(_ babyUid: String?, _ side: BreastSide = .LEFT, at: Int64? = nil) -> NursingSession {
        let at = at ?? start
        return NursingSession(sessionStart: at, currentSide: side, segmentStart: at, babyUid: babyUid)
    }

    private var lea: NursingSession { feed("baby-lea", .LEFT, at: start) }
    private var noah: NursingSession { feed("baby-noah", .RIGHT, at: start + min(1)) }

    func testStartingWhileIdleStartsTheFeed() {
        let result = startingIfIdle([], lea)

        XCTAssertEqual(result.sessions, [lea])
        XCTAssertEqual(result.running, lea)
    }

    /// Two ways into the timer opening at once still time one baby once.
    func testStartingForABabyAlreadyFeedingHandsBackTheRunningFeed() {
        let again = feed("baby-lea", .RIGHT, at: start + min(3))

        let result = startingIfIdle([lea], again)

        XCTAssertEqual(result.sessions, [lea])
        XCTAssertEqual(result.running, lea)
    }

    /// The twin case: Léa's feed is running, and Noah's starts beside it.
    func testStartingForAnotherBabyStartsASecondFeed() {
        let result = startingIfIdle([lea], noah)

        XCTAssertEqual(result.sessions, [lea, noah])
        XCTAssertEqual(result.running, noah)
    }

    func testRunningFeedsAreListedOldestFirst() {
        XCTAssertEqual(startingIfIdle([noah], lea).sessions, [lea, noah])
    }

    func testEachBabyFindsItsOwnFeed() {
        let both = [lea, noah]

        XCTAssertEqual(both.runningFor("baby-lea"), lea)
        XCTAssertEqual(both.runningFor("baby-noah"), noah)
        XCTAssertNil(both.runningFor("baby-zoe"))
    }

    /// Pausing one feed does not pause the other.
    func testPausingOneFeedLeavesTheOtherNursing() {
        let after = updating([lea, noah], "baby-lea") { $0.paused(at: self.start + self.min(8)) }

        XCTAssertEqual(after.runningFor("baby-lea")?.isPaused, true)
        XCTAssertEqual(after.runningFor("baby-noah"), noah)
        // Léa's clock stopped at eight minutes; Noah's carried on.
        XCTAssertEqual(after.runningFor("baby-lea")?.nursedMs(at: start + min(20)), min(8))
        XCTAssertEqual(after.runningFor("baby-noah")?.nursedMs(at: start + min(20)), min(19))
    }

    /// And pausing twice still does not restart a break — per feed, as for one.
    func testPausingAFeedTwiceDoesNotRestartItsBreak() {
        let paused = updating([lea, noah], "baby-lea") { $0.paused(at: self.start + self.min(8)) }

        let again = updating(paused, "baby-lea") { $0.paused(at: self.start + self.min(11)) }

        XCTAssertEqual(again, paused)
    }

    func testSwitchingOneFeedLeavesTheOtherOnItsBreast() {
        let after = updating([lea, noah], "baby-noah") { $0.switched(at: self.start + self.min(6)) }

        XCTAssertEqual(after.runningFor("baby-noah")?.currentSide, .LEFT)
        XCTAssertEqual(after.runningFor("baby-lea"), lea)
    }

    func testChangingABabyWithNoFeedChangesNothing() {
        let both = [lea, noah]

        XCTAssertEqual(updating(both, "baby-zoe") { $0.paused(at: self.start + self.min(8)) }, both)
    }

    func testReplacingPutsTheFeedInPlaceOfItsBabysOwn() {
        let switched = lea.switched(at: start + min(4))

        XCTAssertEqual(replacing([lea, noah], switched), [switched, noah])
    }

    /// Stop & save on one leaves the other running.
    func testStoppingOneFeedLeavesTheOtherRunning() {
        let take = taking([lea, noah], "baby-lea")

        XCTAssertEqual(take.taken, lea)
        XCTAssertEqual(take.sessions, [noah])
    }

    /// The duplicate feeds: two screens stopping one timer.
    func testOnlyTheFirstStopIsHandedTheFeed() {
        let first = taking([lea, noah], "baby-lea")
        let second = taking(first.sessions, "baby-lea")

        XCTAssertNil(second.taken)
        XCTAssertEqual(second.sessions, [noah])
    }

    /// Each saved feed ends when its own last stretch ended.
    func testEachFeedSavesItsOwnStretches() {
        let both = updating([lea, noah], "baby-lea") { $0.paused(at: self.start + self.min(10)) }
        let saveAt = start + min(30)

        let leaFeed = both.runningFor("baby-lea")?.toFeeding(at: saveAt)
        let noahFeed = both.runningFor("baby-noah")?.toFeeding(at: saveAt)

        XCTAssertEqual(leaFeed?.endTime, start + min(10))
        XCTAssertEqual(leaFeed?.leftDurationMs, min(10))
        XCTAssertEqual(noahFeed?.endTime, saveAt)
        XCTAssertEqual(noahFeed?.rightDurationMs, min(29))
    }

    func testWithNobodyElseFeedingTheBabysOwnAlternationDecides() {
        XCTAssertEqual(sideForNextFeed([], babyUid: "baby-noah", fromHistory: .RIGHT), .RIGHT)
        XCTAssertNil(sideForNextFeed([], babyUid: "baby-noah", fromHistory: nil))
    }

    /// The second feed defaults to the breast the first is not on.
    func testASecondFeedIsOfferedTheFreeBreast() {
        XCTAssertEqual(sideForNextFeed([lea], babyUid: "baby-noah", fromHistory: .LEFT), .RIGHT)
        XCTAssertEqual(sideForNextFeed([lea], babyUid: "baby-noah", fromHistory: nil), .RIGHT)
    }

    /// A baby's own running feed is not "another baby at that breast".
    func testABabysOwnFeedDoesNotDecideItsSide() {
        XCTAssertEqual(sideForNextFeed([lea], babyUid: "baby-lea", fromHistory: .LEFT), .LEFT)
    }

    /// A feed on a break is at no breast at all.
    func testAFeedOnABreakLeavesBothBreastsFree() {
        let paused = lea.paused(at: start + min(5))

        XCTAssertEqual(sideForNextFeed([paused], babyUid: "baby-noah", fromHistory: .LEFT), .LEFT)
    }

    /// With both breasts taken there is nothing free to offer, so nothing is guessed.
    func testWithBothBreastsTakenTheAlternationDecides() {
        let zoe = feed("baby-zoe", .RIGHT)

        XCTAssertEqual(sideForNextFeed([lea, zoe], babyUid: "baby-noah", fromHistory: .LEFT), .LEFT)
    }

    /// A feed left running through the update keeps going, on the baby selected then.
    func testAFeedWithNoBabyIsGivenTheSelectedOne() {
        let unnamed = feed(nil)
        var expected = unnamed
        expected.babyUid = "baby-lea"

        XCTAssertEqual(adoptingUnnamed([unnamed], activeBabyUid: "baby-lea"), [expected])
    }

    func testWithNoBabySelectedAFeedWithNoBabyStaysAsItIs() {
        let unnamed = feed(nil)

        XCTAssertEqual(adoptingUnnamed([unnamed], activeBabyUid: nil), [unnamed])
    }

    func testFeedsThatNameTheirBabyAreLeftAlone() {
        XCTAssertEqual(adoptingUnnamed([lea, noah], activeBabyUid: "baby-lea"), [lea, noah])
    }

    /// Never two feeds for one baby, even by adoption.
    func testAFeedWithNoBabyIsNotGivenABabyAlreadyFeeding() {
        let unnamed = feed(nil, at: start - min(5))

        XCTAssertEqual(adoptingUnnamed([unnamed, lea], activeBabyUid: "baby-lea"), [unnamed, lea])
    }
}
