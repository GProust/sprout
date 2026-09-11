import XCTest
@testable import SproutData

/// Which days a report actually covers — the counterpart of `ReportRangeTest.kt`.
///
/// The two clamps are the whole of it: never past today, never before the birth.
/// Both exist so that a figure printed as "per day" is divided by days the baby
/// lived — the same rule the Statistics window follows, for the same reason.
final class ReportRangeTests: XCTestCase {

    private let today = CalendarDay(year: 2026, month: 9, day: 8)

    private func day(_ year: Int, _ month: Int, _ dayOfMonth: Int) -> CalendarDay {
        CalendarDay(year: year, month: month, day: dayOfMonth)
    }

    func testSevenDaysIsThisWeekIncludingToday() {
        let range = reportRange(period: .week, today: today, birthDay: day(2026, 1, 1))

        XCTAssertEqual(range.from, day(2026, 9, 2))
        XCTAssertEqual(range.to, today)
        XCTAssertEqual(range.dayCount, 7)
    }

    func testThirtyDaysIsThirtyColumns() {
        let range = reportRange(period: .month, today: today, birthDay: day(2025, 1, 1))

        XCTAssertEqual(range.dayCount, 30)
        XCTAssertEqual(range.from, day(2026, 8, 10))
    }

    func testTheRangeNeverReachesBackPastTheBirth() {
        let born = day(2026, 8, 27)
        let range = reportRange(period: .quarter, today: today, birthDay: born)

        XCTAssertEqual(range.from, born)
        XCTAssertEqual(range.dayCount, 13)
    }

    func testSinceBirthStartsAtTheBirth() {
        let born = day(2026, 6, 2)
        let range = reportRange(period: .sinceBirth, today: today, birthDay: born)

        XCTAssertEqual(range.from, born)
        XCTAssertEqual(range.to, today)
        XCTAssertEqual(range.dayCount, 99)
    }

    func testACustomRangeIsHonoured() {
        let range = reportRange(
            period: .custom,
            today: today,
            birthDay: day(2026, 1, 1),
            customFrom: day(2026, 8, 1),
            customTo: day(2026, 8, 31)
        )

        XCTAssertEqual(range.from, day(2026, 8, 1))
        XCTAssertEqual(range.to, day(2026, 8, 31))
        XCTAssertEqual(range.dayCount, 31)
    }

    func testACustomRangeCannotEndAfterToday() {
        let range = reportRange(
            period: .custom,
            today: today,
            birthDay: day(2026, 1, 1),
            customFrom: day(2026, 9, 1),
            customTo: day(2026, 12, 25)
        )

        XCTAssertEqual(range.to, today, "tomorrow has not been lived")
    }

    func testACustomRangeCannotStartBeforeTheBirth() {
        let born = day(2026, 8, 20)
        let range = reportRange(
            period: .custom,
            today: today,
            birthDay: born,
            customFrom: day(2026, 1, 1),
            customTo: today
        )

        XCTAssertEqual(range.from, born)
    }

    func testACustomRangeTypedBackToFrontStillCoversADay() {
        let range = reportRange(
            period: .custom,
            today: today,
            birthDay: day(2026, 1, 1),
            customFrom: day(2026, 9, 5),
            customTo: day(2026, 9, 1)
        )

        XCTAssertEqual(range.dayCount, 1)
        XCTAssertEqual(range.from, range.to)
    }

    func testABirthDateInTheFutureLeavesToday() {
        let range = reportRange(period: .month, today: today, birthDay: today.adding(days: 20))

        XCTAssertEqual(range.from, today)
        XCTAssertEqual(range.to, today)
        XCTAssertEqual(range.dayCount, 1)
    }

    func testDaysAreIndexedFromTheStartOfTheRange() {
        let range = reportRange(period: .week, today: today, birthDay: day(2026, 1, 1))

        XCTAssertEqual(range.index(of: range.from), 0)
        XCTAssertEqual(range.index(of: today), 6)
        XCTAssertNil(range.index(of: today.adding(days: 1)))
        XCTAssertNil(range.index(of: range.from.adding(days: -1)))
        XCTAssertEqual(range.days().count, 7)
    }
}
