import XCTest
@testable import SproutData

/// The arithmetic behind every date and duration on screen.
///
/// Pinned to a fixed calendar and zone: "today" is the parent's today, and a
/// test that passes only in the runner's zone is a test that says nothing.
final class FormatTests: XCTestCase {

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

    private func at(_ iso: String) -> Int64 {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return SproutFormat.millis(from: formatter.date(from: iso)!)
    }

    // MARK: - Days

    func testStartOfDayIsLocalMidnight() {
        XCTAssertEqual(SproutFormat.startOfDay(at("2026-09-10 03:20:00")), at("2026-09-10 00:00:00"))
        // The 3 a.m. feed belongs to the night the parent is still awake in, and
        // that night's date is the one that has already started.
        XCTAssertEqual(SproutFormat.startOfDay(at("2026-09-10 23:59:59")), at("2026-09-10 00:00:00"))
    }

    /// Calendar arithmetic, not 86 400 000: the hour has to survive the clocks
    /// changing, or every duration around the end of October is an hour out.
    func testNextDayKeepsTheWallClockAcrossADaylightSavingChange() {
        // Europe/Zurich goes back one hour on 2026-10-25.
        let before = at("2026-10-24 09:00:00")

        let after = SproutFormat.nextDay(before)

        XCTAssertEqual(after, at("2026-10-25 09:00:00"))
        XCTAssertNotEqual(after, before + 24 * 60 * 60 * 1000, "a fixed 24 h would drift here")
    }

    func testSameDay() {
        XCTAssertTrue(SproutFormat.isSameDay(at("2026-09-10 00:00:01"), at("2026-09-10 23:59:59")))
        XCTAssertFalse(SproutFormat.isSameDay(at("2026-09-10 23:59:59"), at("2026-09-11 00:00:01")))
    }

    func testDayLabel() {
        let now = at("2026-09-10 14:00:00")

        XCTAssertEqual(SproutFormat.dayLabel(at("2026-09-10 02:00:00"), now: now), .today)
        XCTAssertEqual(SproutFormat.dayLabel(at("2026-09-09 23:00:00"), now: now), .yesterday)

        let older = at("2026-09-08 12:00:00")
        XCTAssertEqual(SproutFormat.dayLabel(older, now: now), .other(older))
    }

    // MARK: - Greeting

    func testGreetingBuckets() {
        XCTAssertEqual(SproutFormat.greeting(forHour: 5), .morning)
        XCTAssertEqual(SproutFormat.greeting(forHour: 11), .morning)
        XCTAssertEqual(SproutFormat.greeting(forHour: 12), .afternoon)
        XCTAssertEqual(SproutFormat.greeting(forHour: 17), .afternoon)
        XCTAssertEqual(SproutFormat.greeting(forHour: 18), .evening)
        XCTAssertEqual(SproutFormat.greeting(forHour: 21), .evening)
        // The hours this app is most used in get the neutral one.
        XCTAssertEqual(SproutFormat.greeting(forHour: 3), .hello)
        XCTAssertEqual(SproutFormat.greeting(forHour: 22), .hello)
    }

    // MARK: - Elapsed

    func testRelativeBuckets() {
        let now = at("2026-09-10 14:00:00")
        let minute: Int64 = 60_000

        XCTAssertEqual(SproutFormat.relative(now, now: now), .justNow)
        XCTAssertEqual(SproutFormat.relative(now - 59_999, now: now), .justNow)
        XCTAssertEqual(SproutFormat.relative(now - minute, now: now), .minutes(1))
        XCTAssertEqual(SproutFormat.relative(now - 59 * minute, now: now), .minutes(59))
        XCTAssertEqual(SproutFormat.relative(now - 60 * minute, now: now), .hours(1))
        XCTAssertEqual(SproutFormat.relative(now - 23 * 60 * minute, now: now), .hours(23))
        XCTAssertEqual(SproutFormat.relative(now - 24 * 60 * minute, now: now), .days(1))
    }

    func testDurationBuckets() {
        XCTAssertEqual(SproutFormat.duration(millis: 0), .seconds(0))
        XCTAssertEqual(SproutFormat.duration(millis: 30_000), .seconds(30))
        XCTAssertEqual(SproutFormat.duration(millis: 60_000), .minutes(1))
        XCTAssertEqual(SproutFormat.duration(millis: 45 * 60_000), .minutes(45))
        XCTAssertEqual(SproutFormat.duration(millis: 60 * 60_000), .hours(1))
        XCTAssertEqual(SproutFormat.duration(millis: 80 * 60_000), .hoursMinutes(1, 20))
    }

    /// A sleep whose end was logged slightly before its start is a typo, not a
    /// negative nap. It reads as its magnitude rather than as nonsense.
    func testDurationOfANegativeSpanReadsAsItsMagnitude() {
        XCTAssertEqual(SproutFormat.duration(millis: -80 * 60_000), .hoursMinutes(1, 20))
    }

    func testClockIsDigitsOnly() {
        XCTAssertEqual(SproutFormat.clock(millis: 0), "0:00")
        XCTAssertEqual(SproutFormat.clock(millis: 65_000), "1:05")
        XCTAssertEqual(SproutFormat.clock(millis: 59 * 60_000 + 59_000), "59:59")
        XCTAssertEqual(SproutFormat.clock(millis: 60 * 60_000), "1:00:00")
        XCTAssertEqual(SproutFormat.clock(millis: 3 * 3_600_000 + 4 * 60_000 + 5_000), "3:04:05")
    }

    // MARK: - Age

    func testAgeCountsInDaysForAFortnight() {
        let birth = at("2026-09-01 06:00:00")

        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-01 23:00:00")), .days(0))
        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-10 08:00:00")), .days(9))
        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-14 08:00:00")), .days(13))
    }

    func testAgeCountsInWeeksAfterAFortnight() {
        let birth = at("2026-09-01 06:00:00")

        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-15 08:00:00")), .weeks(2))
        XCTAssertEqual(
            SproutFormat.age(birthDate: birth, now: at("2026-09-18 08:00:00")),
            .weeksAndDays(2, 3)
        )
    }

    func testAgeCountsInMonthsAfterTwo() {
        let birth = at("2026-01-01 06:00:00")

        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-05-01 08:00:00")), .months(4))
    }

    /// The birth date can be in the future while a parent is entering it, and
    /// "-3 days old" is not a thing to show anyone.
    func testABabyNotYetBornSaysSo() {
        let birth = at("2026-12-01 06:00:00")

        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-10 08:00:00")), .notBornYet)
    }

    /// Age is counted in whole local days, so a birth at 23:00 and a "now" at
    /// 01:00 the next night is one day, not zero.
    func testAgeCountsCalendarDaysNotElapsedHours() {
        let birth = at("2026-09-09 23:00:00")

        XCTAssertEqual(SproutFormat.age(birthDate: birth, now: at("2026-09-10 01:00:00")), .days(1))
    }
}
