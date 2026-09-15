import XCTest
@testable import SproutData

/// Which side of the treatments list a course lands on — the counterpart of
/// `TreatmentSectionsTest.kt`.
///
/// The end date is inclusive and compared by calendar day, so the boundaries are
/// the whole test: a course whose last dose is today, and one that ended at one
/// minute past midnight.
final class TreatmentSectionsTests: XCTestCase {

    private let calendar = Calendar.current

    /// 15 March 2026, 14:30 local.
    private lazy var today: Date = {
        calendar.date(from: DateComponents(year: 2026, month: 3, day: 15))!
    }()

    private lazy var now: Int64 = millis(at: today, hour: 14, minute: 30)

    private func millis(at day: Date, hour: Int = 12, minute: Int = 0) -> Int64 {
        let point = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)!
        return SproutFormat.millis(from: point)
    }

    private func day(offset: Int) -> Date {
        calendar.date(byAdding: .day, value: offset, to: today)!
    }

    private func treatment(endDate: Int64?) -> Treatment {
        Treatment(name: "Vitamin D", startDate: millis(at: day(offset: -30)), endDate: endDate)
    }

    func testNoEndDateIsNeverPast() {
        XCTAssertFalse(hasEnded(treatment(endDate: nil), now: now))
    }

    /// One dose left to give is not something a parent should have to look for
    /// under "finished".
    func testACourseEndingTodayStaysActive() {
        XCTAssertFalse(hasEnded(treatment(endDate: millis(at: today)), now: now))
        XCTAssertFalse(
            hasEnded(treatment(endDate: millis(at: today, hour: 0, minute: 1)), now: now),
            "an instant earlier today is still today"
        )
    }

    func testACourseThatEndedYesterdayIsPast() {
        XCTAssertTrue(hasEnded(treatment(endDate: millis(at: day(offset: -1))), now: now))
        XCTAssertTrue(
            hasEnded(treatment(endDate: millis(at: day(offset: -1), hour: 23, minute: 59)), now: now),
            "right up to the last minute of yesterday"
        )
    }

    func testACourseEndingLaterIsActive() {
        XCTAssertFalse(hasEnded(treatment(endDate: millis(at: day(offset: 1))), now: now))
        XCTAssertFalse(hasEnded(treatment(endDate: millis(at: day(offset: 365))), now: now))
    }

    /// Ahead, not behind. Nothing about a start date can put a course in the
    /// past.
    func testACourseThatHasNotStartedIsActive() {
        let upcoming = Treatment(
            name: "Iron",
            startDate: millis(at: day(offset: 7)),
            endDate: millis(at: day(offset: 21))
        )

        XCTAssertFalse(hasEnded(upcoming, now: now))
    }

    /// The finished ones come out newest first, so the most recently stopped
    /// course is the one at the top of that section.
    func testSectionsSplitAndOrder() {
        let ongoing = treatment(endDate: nil)
        let endsToday = treatment(endDate: millis(at: today))
        let endedLastWeek = treatment(endDate: millis(at: day(offset: -7)))
        let endedYesterday = treatment(endDate: millis(at: day(offset: -1)))

        let sections = treatmentSections(
            [endedLastWeek, ongoing, endedYesterday, endsToday],
            now: now
        )

        XCTAssertEqual(sections.current.map(\.endDate), [ongoing.endDate, endsToday.endDate])
        XCTAssertEqual(
            sections.past.map(\.endDate),
            [endedYesterday.endDate, endedLastWeek.endDate]
        )
    }
}
