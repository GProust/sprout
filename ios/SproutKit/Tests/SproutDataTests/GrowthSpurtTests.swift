import XCTest
@testable import SproutData

final class GrowthSpurtTests: XCTestCase {

    private var calendar = Calendar(identifier: .gregorian)

    override func setUp() {
        super.setUp()
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich")!
        SproutFormat.calendar = calendar
    }

    override func tearDown() {
        SproutFormat.calendar = .autoupdatingCurrent
        super.tearDown()
    }

    private let day: Int64 = 24 * 60 * 60 * 1000

    // MARK: - The table

    /// The windows are reference data. They must stay in age order and must not
    /// overlap, or "the window you are in" stops being a single answer.
    func testTheWindowsAreInOrderAndDoNotOverlap() {
        for (earlier, later) in zip(growthSpurtWindows, growthSpurtWindows.dropFirst()) {
            XCTAssertLessThan(earlier.endDay, later.startDay, "windows overlap or are out of order")
        }
    }

    func testEveryWindowIsLabelledExactlyOneWay() {
        for window in growthSpurtWindows {
            let labels = [window.approxWeeks, window.approxMonths].compactMap { $0 }
            XCTAssertEqual(
                labels.count, 1,
                "a window labelled both ways, or neither, has no sentence to show"
            )
        }
    }

    func testEveryWindowIsANonEmptySpan() {
        for window in growthSpurtWindows {
            XCTAssertLessThanOrEqual(window.startDay, window.endDay)
        }
    }

    // MARK: - Age

    /// Calendar days, not elapsed hours — a baby born at 23:00 is a day old at
    /// 01:00 the next night, which is what a parent would say.
    func testAgeCountsCalendarDays() {
        let birth = Int64(1_757_400_000_000)

        XCTAssertEqual(ageInDays(birthDate: birth, now: birth), 0)
        XCTAssertEqual(ageInDays(birthDate: birth, now: birth + 9 * day), 9)
    }

    // MARK: - Which window

    func testInsideAWindow() {
        // The three-week window is days 14–21, inclusive at both ends.
        XCTAssertNil(currentGrowthSpurt(ageDays: 13))
        XCTAssertEqual(currentGrowthSpurt(ageDays: 14)?.approxWeeks, 3)
        XCTAssertEqual(currentGrowthSpurt(ageDays: 21)?.approxWeeks, 3)
        XCTAssertNil(currentGrowthSpurt(ageDays: 22))
    }

    func testBetweenWindowsIsNotInOne() {
        XCTAssertNil(currentGrowthSpurt(ageDays: 30))
    }

    func testTheNextWindowIsTheFirstOneNotYetStarted() {
        XCTAssertEqual(nextGrowthSpurt(ageDays: 0)?.approxWeeks, 1)
        // Inside the three-week window, the *next* one is still ahead.
        XCTAssertEqual(nextGrowthSpurt(ageDays: 15)?.approxWeeks, 6)
    }

    /// Past the last window there is nothing left to promise, and the app says
    /// nothing rather than inventing a milestone.
    func testPastTheLastWindowThereIsNoNextOne() {
        XCTAssertNil(nextGrowthSpurt(ageDays: 400))
        XCTAssertNil(upcomingGrowthSpurt(ageDays: 400))
        XCTAssertNil(currentGrowthSpurt(ageDays: 400))
    }

    // MARK: - The heads-up

    func testAHeadsUpOnlyInsideItsWindow() {
        // The six-week window starts on day 40, so the notice is days 37–39.
        XCTAssertNil(upcomingGrowthSpurt(ageDays: 36))
        XCTAssertEqual(upcomingGrowthSpurt(ageDays: 37)?.approxWeeks, 6)
        XCTAssertEqual(upcomingGrowthSpurt(ageDays: 39)?.approxWeeks, 6)
    }

    /// On the first day of a window the baby is *in* it, so the heads-up has
    /// nothing left to warn about — showing both at once would say the same
    /// thing twice in two tenses.
    func testOnceItHasStartedItIsNoLongerUpcoming() {
        XCTAssertNotNil(currentGrowthSpurt(ageDays: 40))
        XCTAssertNotEqual(upcomingGrowthSpurt(ageDays: 40)?.approxWeeks, 6)
    }

    func testANewbornIsNotToldAboutAWindowWeeksAway() {
        XCTAssertNil(upcomingGrowthSpurt(ageDays: 0))
    }
}
