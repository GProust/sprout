import XCTest
@testable import SproutData

/// When the guided tour shows, and when it gets out of the way (BDR-27).
///
/// The same cases as `TourTest.kt`: one household with a phone of each should
/// not have one of them showing a newcomer round and the other not.
final class TourTests: XCTestCase {

    func testAPhoneThatHasJustFinishedOnboardingGetsTheTour() {
        XCTAssertTrue(tourStarts(.arrival, mark: .pending, openedForSomethingElse: false))
    }

    /// Set up before the tour existed: a profile, and nothing stored.
    func testAnExistingUserNeverGetsIt() {
        XCTAssertFalse(tourStarts(.arrival, mark: nil, openedForSomethingElse: false))
    }

    /// A restored phone skips onboarding, because its profile came with it, and
    /// carries whatever its backup held: the spent mark, or nothing at all.
    func testARestoredPhoneNeverGetsIt() {
        XCTAssertFalse(tourStarts(.arrival, mark: .done, openedForSomethingElse: false))
        XCTAssertFalse(tourStarts(.arrival, mark: nil, openedForSomethingElse: false))
    }

    /// Finished, skipped, or killed half-way: the next launch is an ordinary one.
    func testOnceOfferedItNeverComesBackOnItsOwn() {
        let spent = tourMarkAfterArrival(.pending)
        XCTAssertEqual(spent, .done)
        XCTAssertFalse(tourStarts(.arrival, mark: spent, openedForSomethingElse: false))
    }

    func testAReplayStartsWhateverIsStored() {
        XCTAssertTrue(tourStarts(.replay, mark: .done, openedForSomethingElse: false))
        // Including on a phone that was set up before the tour existed.
        XCTAssertTrue(tourStarts(.replay, mark: nil, openedForSomethingElse: false))
    }

    /// The file wins, and the tour does not wait for a later launch either.
    func testALaunchFromAWidgetOrAFileDoesNotStartIt() {
        XCTAssertFalse(tourStarts(.arrival, mark: .pending, openedForSomethingElse: true))
        let spent = tourMarkAfterArrival(.pending)
        XCTAssertFalse(tourStarts(.arrival, mark: spent, openedForSomethingElse: false))
    }

    func testArrivingLeavesAnythingButAnOwedTourAsItWas() {
        XCTAssertNil(tourMarkAfterArrival(nil))
        XCTAssertEqual(tourMarkAfterArrival(.done), .done)
    }

    func testAFeedStartingEndsIt() {
        XCTAssertTrue(tourStepsAside(onDashboard: true, feedingBefore: [], feedingNow: ["lea"]))
    }

    /// Twins: the second feed starting beside the first is a feed starting.
    func testATwinsFeedStartingBesideTheOtherEndsIt() {
        XCTAssertTrue(tourStepsAside(onDashboard: true, feedingBefore: ["lea"], feedingNow: ["lea", "noah"]))
    }

    /// A replay asked for in the middle of a feed is not cut short by that feed.
    func testAFeedAlreadyRunningDoesNotEndIt() {
        XCTAssertFalse(tourStepsAside(onDashboard: true, feedingBefore: ["lea"], feedingNow: ["lea"]))
        XCTAssertFalse(tourStepsAside(onDashboard: true, feedingBefore: ["lea"], feedingNow: []))
        XCTAssertFalse(tourStepsAside(onDashboard: true, feedingBefore: [], feedingNow: []))
    }

    /// A tile, a tab, Settings, or a file arriving underneath it.
    func testLeavingTheDashboardEndsIt() {
        XCTAssertTrue(tourStepsAside(onDashboard: false, feedingBefore: [], feedingNow: []))
    }

    func testOneBabyIsShownEveryStep() {
        XCTAssertEqual(tourSteps(babyCount: 1), [.logTiles, .breastfeed, .bottomBar, .settings])
    }

    /// No tiles and no feed buttons yet: the dashboard's own card asks for a baby.
    func testNoBabyYetLeavesOutWhatIsNotThere() {
        XCTAssertEqual(tourSteps(babyCount: 0), [.bottomBar, .settings])
    }

    /// The tiles have moved to each baby's own tab, which the tab bar's step names.
    func testTwoBabiesLeaveOutTheTiles() {
        let expected: [TourStep] = [.breastfeed, .bottomBar, .settings]
        XCTAssertEqual(tourSteps(babyCount: 2), expected)
        XCTAssertEqual(tourSteps(babyCount: 3), expected)
    }

    func testNextWalksTheStepsAndEndsAfterTheLast() {
        let steps = tourSteps(babyCount: 1)
        XCTAssertEqual(tourStepAfter(.logTiles, in: steps), .breastfeed)
        XCTAssertEqual(tourStepAfter(.breastfeed, in: steps), .bottomBar)
        XCTAssertEqual(tourStepAfter(.bottomBar, in: steps), .settings)
        XCTAssertNil(tourStepAfter(.settings, in: steps))
    }

    func testAStepTheDashboardCannotShowEndsTheTour() {
        XCTAssertNil(tourStepAfter(.logTiles, in: tourSteps(babyCount: 0)))
    }

    /// What is already in a parent's settings has to keep reading the same, and
    /// it is spelled the way Android spells it.
    func testTheStoredSpellingsArePinned() {
        XCTAssertEqual(TourMark.pending.rawValue, "pending")
        XCTAssertEqual(TourMark.done.rawValue, "done")
    }

    func testTheSettingsRememberTheMark() {
        let store = InMemoryStore()
        let settings = TourSettings(store: store)
        XCTAssertNil(settings.mark)

        settings.markPending()
        XCTAssertEqual(settings.mark, .pending)

        settings.set(tourMarkAfterArrival(settings.mark))
        XCTAssertEqual(settings.mark, .done)

        settings.set(nil)
        XCTAssertNil(settings.mark)
    }

    func testAnythingElseStoredReadsAsNothing() {
        let store = InMemoryStore()
        store.setString("PENDING", forKey: "tour")
        XCTAssertNil(TourSettings(store: store).mark)
        store.setString("", forKey: "tour")
        XCTAssertNil(TourSettings(store: store).mark)
    }
}
