import XCTest
@testable import SproutData

/// What a report says, before anything draws it — the counterpart of
/// `ReportDataTest.kt`.
///
/// The cases worth pinning down are the ones where a smaller number could be
/// passed off as the same kind of fact: an amount nobody recorded, a day nobody
/// logged on, a course clamped to a range it started before.
final class ReportDataTests: XCTestCase {

    private let zone = TimeZone(identifier: "Europe/Paris")!
    private var savedCalendar: Calendar!

    private let born = CalendarDay(year: 2026, month: 6, day: 2)
    private let today = CalendarDay(year: 2026, month: 9, day: 8)
    private var now: Int64 = 0
    private var baby = Baby(name: "Louise", birthDate: 0)

    private let hour: Int64 = 3_600_000

    override func setUp() {
        super.setUp()
        savedCalendar = SproutFormat.calendar
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        SproutFormat.calendar = calendar

        now = at(today, 11)
        baby = Baby(id: 1, name: "Louise", birthDate: born.startMillis())
    }

    override func tearDown() {
        SproutFormat.calendar = savedCalendar
        super.tearDown()
    }

    private func at(_ date: CalendarDay, _ hour: Int, _ minute: Int = 0) -> Int64 {
        SproutFormat.settingTime(hour: hour, minute: minute, on: date.startMillis())
    }

    private func report(
        feedings: [Feeding] = [],
        sleeps: [Sleep] = [],
        diapers: [Diaper] = [],
        growth: [Growth] = [],
        treatments: [Treatment] = [],
        options: ReportOptions = ReportOptions(period: .week)
    ) -> ReportContent {
        buildReport(
            baby: baby,
            feedings: feedings,
            sleeps: sleeps,
            diapers: diapers,
            growth: growth,
            treatments: treatments,
            options: options,
            now: now
        )
    }

    private func bottle(_ date: CalendarDay, _ hour: Int, ml: Int?) -> Feeding {
        Feeding(babyId: 1, type: .BOTTLE, amountMl: ml, startTime: at(date, hour))
    }

    // MARK: - The days

    func testEveryDayInTheRangeIsThereIncludingTheEmptyOnes() {
        let content = report(feedings: [bottle(today, 9, ml: 120)])

        XCTAssertEqual(content.days.count, 7)
        XCTAssertEqual(content.daysWithEntries, 1)
        XCTAssertEqual(content.bottleMlTotal, 120)
    }

    func testEntriesOutsideTheRangeAreLeftOut() {
        let content = report(feedings: [
            bottle(today, 9, ml: 120),
            bottle(today.adding(days: -30), 9, ml: 500),
        ])

        XCTAssertEqual(content.feedTotal, 1)
        XCTAssertEqual(content.bottleMlTotal, 120)
    }

    /// A bottle logged without its millilitres happened; it was just not
    /// measured. Counting it as zero would report a smaller total as the same
    /// kind of fact.
    func testABottleWithNoVolumeCountsAsAFeedAndNotAsZeroMillilitres() {
        let content = report(feedings: [
            bottle(today, 9, ml: 120),
            bottle(today, 13, ml: nil),
        ])

        XCTAssertEqual(content.bottleCount, 2)
        XCTAssertEqual(content.bottlesWithVolume, 1)
        XCTAssertEqual(content.bottleMlTotal, 120)
    }

    func testAnEmptyPeriodSaysSoRatherThanPretendingToHaveFigures() {
        let content = report()

        XCTAssertFalse(content.hasEntries)
        XCTAssertEqual(content.feedTotal, 0)
        XCTAssertEqual(content.days.count, 7)
    }

    // MARK: - Sleep

    func testTheReportSaysWhereTheySleptAndHowTheyWereLying() {
        let content = report(sleeps: [
            Sleep(
                babyId: 1,
                startTime: at(today.adding(days: -1), 20),
                endTime: at(today, 6),
                position: .BACK,
                place: .BEDSIDE_COT
            ),
            Sleep(
                babyId: 1,
                startTime: at(today, 9),
                endTime: at(today, 10),
                position: .BACK,
                place: .ON_A_PARENT
            ),
        ])

        let breakdown = content.sleepBreakdown
        XCTAssertTrue(breakdown.hasPlaces)
        XCTAssertTrue(breakdown.hasPositions)

        // Ten hours in the bedside cot against one on a parent, longest first.
        let places: [SleepWhere?] = [.offered(.BEDSIDE_COT), .offered(.ON_A_PARENT)]
        XCTAssertEqual(breakdown.byPlace.map(\.value), places)

        // Both sleeps were on their back, so the positions are one line of two.
        let positions: [SleepPosition?] = [.BACK]
        XCTAssertEqual(breakdown.byPosition.map(\.value), positions)
        XCTAssertEqual(breakdown.byPosition.first?.count, 2)
        XCTAssertEqual(breakdown.totalMillis, 11 * hour)
    }

    /// A document that dropped them would report every nap as being in their own
    /// bed (BDR-0014).
    func testSleepsThatSaidNothingAreALineOfTheirOwnNotLeftOut() {
        let content = report(sleeps: [
            Sleep(babyId: 1, startTime: at(today, 9), endTime: at(today, 10), place: .OWN_BED),
            // Three hours nobody said anything about.
            Sleep(
                babyId: 1,
                startTime: at(today.adding(days: -2), 13),
                endTime: at(today.adding(days: -2), 16)
            ),
        ])

        let places = content.sleepBreakdown.byPlace
        XCTAssertNil(places.last?.value, "the sleeps that said nothing come last")
        XCTAssertEqual(places.last?.millis, 3 * hour)
        XCTAssertEqual(
            places.reduce(Int64(0)) { $0 + $1.millis },
            content.sleepBreakdown.totalMillis
        )
    }

    func testANightIsSplitAcrossTheTwoDaysItCovers() throws {
        let content = report(sleeps: [
            Sleep(babyId: 1, startTime: at(today.adding(days: -1), 20), endTime: at(today, 6)),
        ])

        let evening = try XCTUnwrap(content.days.first { $0.date == today.adding(days: -1) })
        let morning = try XCTUnwrap(content.days.first { $0.date == today })

        XCTAssertEqual(evening.sleepMillis, 4 * hour)
        XCTAssertEqual(morning.sleepMillis, 6 * hour)
        // The count stays on the evening: "three naps" means three times settled.
        XCTAssertEqual(evening.sleepCount, 1)
        XCTAssertEqual(morning.sleepCount, 0)
        XCTAssertEqual(content.longestSleepMillis, 10 * hour)
    }

    func testANightThatBeganBeforeTheRangeStillCounts() {
        let range = reportRange(period: .week, today: today, birthDay: born)
        let content = report(sleeps: [
            Sleep(
                babyId: 1,
                startTime: at(range.from.adding(days: -1), 21),
                endTime: at(range.from, 7)
            ),
        ])

        XCTAssertEqual(content.days.first?.sleepMillis, 7 * hour)
    }

    // MARK: - Nappies

    func testStoolColoursAreCountedWhereTheyWereRecorded() {
        let content = report(diapers: [
            Diaper(babyId: 1, time: at(today, 8), wet: true, dirty: true, stoolColor: .YELLOW),
            Diaper(babyId: 1, time: at(today, 10), wet: true, dirty: true, stoolColor: .YELLOW),
            Diaper(babyId: 1, time: at(today, 12), wet: true, dirty: true, stoolColor: .GREEN),
            Diaper(babyId: 1, time: at(today, 14), wet: true),
        ])

        XCTAssertEqual(content.stoolColours.map(\.colour), [.YELLOW, .GREEN])
        XCTAssertEqual(content.stoolColours.map(\.count), [2, 1])
        XCTAssertEqual(content.diaperTotal, 4)
        XCTAssertEqual(content.dirtyTotal, 3)
    }

    // MARK: - Growth

    /// A head circumference nobody measured is a gap in the line, not a head of
    /// no size.
    func testAMeasureThatWasNotTakenHasNoCentile() throws {
        let content = report(growth: [
            Growth(babyId: 1, time: at(today, 9), weightGrams: 6100, heightMm: 594),
        ])
        let reading = try XCTUnwrap(content.growth.first)

        XCTAssertNotNil(reading.weight)
        XCTAssertNotNil(reading.length)
        XCTAssertNil(reading.head)
    }

    func testPickingOneReferenceCollapsesTheSpanToASingleFigure() throws {
        let entry = Growth(babyId: 1, time: at(today, 9), weightGrams: 6100)
        let both = try XCTUnwrap(report(growth: [entry]).growth.first?.weight)
        let girls = try XCTUnwrap(
            report(
                growth: [entry],
                options: ReportOptions(period: .week, reference: .girls)
            ).growth.first?.weight
        )

        XCTAssertTrue(both.highPercentile > both.lowPercentile)
        XCTAssertEqual(girls.lowPercentile, girls.highPercentile, accuracy: 1e-9)
        XCTAssertEqual(both.highPercentile, girls.highPercentile, accuracy: 1e-9)
    }

    /// A curve is only worth reading over months, so it is not clipped to the
    /// range the rest of the report covers.
    func testGrowthIsTheWholeHistoryNotJustTheRange() {
        let content = report(growth: [
            Growth(babyId: 1, time: at(born, 12), weightGrams: 3240),
            Growth(babyId: 1, time: at(today, 9), weightGrams: 6100),
        ])

        XCTAssertEqual(content.growth.count, 2)
        XCTAssertEqual(content.growth.first?.entry.weightGrams, 3240)
    }

    // MARK: - Treatments

    private func treatment(
        _ name: String,
        _ start: CalendarDay,
        _ end: CalendarDay?,
        intervalDays: Int = 1
    ) -> Treatment {
        Treatment(
            babyId: 1,
            name: name,
            intervalDays: intervalDays,
            startDate: at(start, 9),
            endDate: end.map { at($0, 9) }
        )
    }

    func testACourseThatStartedBeforeTheRangeIsClampedAndSaysSo() throws {
        let range = reportRange(period: .week, today: today, birthDay: born)
        let course = try XCTUnwrap(
            treatmentCourses([treatment("Vitamin D", born, nil)], range: range).first
        )

        XCTAssertEqual(course.startIndex, 0)
        XCTAssertEqual(course.endIndex, range.dayCount)
        XCTAssertTrue(course.startsBefore)
        XCTAssertTrue(course.runsPast)
    }

    func testACourseWhollyInsideTheRangeClaimsNeitherEnd() throws {
        let range = reportRange(period: .week, today: today, birthDay: born)
        let course = try XCTUnwrap(
            treatmentCourses(
                [treatment("Amoxicillin", range.from.adding(days: 1), range.from.adding(days: 5))],
                range: range
            ).first
        )

        XCTAssertEqual(course.startIndex, 1)
        XCTAssertEqual(course.endIndex, 6)
        XCTAssertFalse(course.startsBefore)
        XCTAssertFalse(course.runsPast)
    }

    func testACourseThatEndedBeforeTheRangeIsNotShown() {
        let range = reportRange(period: .week, today: today, birthDay: born)
        let courses = treatmentCourses(
            [treatment("Vitamin K", born, range.from.adding(days: -1))],
            range: range
        )

        XCTAssertTrue(courses.isEmpty)
    }

    /// Marking every day of a daily course would say nothing and draw the eye to
    /// nothing, so only the less-than-daily ones carry dose marks.
    func testAWeeklyCourseMarksItsDosingDaysAndADailyOneDoesNot() throws {
        let range = reportRange(period: .month, today: today, birthDay: born)
        let start = range.from.adding(days: -3)
        let courses = treatmentCourses(
            [
                treatment("Vitamin K", start, nil, intervalDays: 7),
                treatment("Vitamin D", start, nil, intervalDays: 1),
            ],
            range: range
        )

        let weekly = try XCTUnwrap(courses.first { $0.treatment.name == "Vitamin K" })
        let daily = try XCTUnwrap(courses.first { $0.treatment.name == "Vitamin D" })

        XCTAssertTrue(daily.doseDays.isEmpty)
        // Doses fall every seventh day from the start, three days before the range.
        XCTAssertEqual(weekly.doseDays, [4, 11, 18, 25])
    }

    func testTreatmentsAreLeftOutEntirelyWhenTheSwitchIsOff() {
        let content = report(
            treatments: [treatment("Vitamin D", born, nil)],
            options: ReportOptions(period: .week, includeTreatments: false)
        )

        XCTAssertTrue(content.treatments.isEmpty)
    }
}
