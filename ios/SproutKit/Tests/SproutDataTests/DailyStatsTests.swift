import XCTest
@testable import SproutData

/// The counterpart of `DailyStatsTest.kt`, test for test.
///
/// These are the rules BDR-0008 says break quietly: empty days counting, today
/// not counting, the window stopping at the birth, a night divided between the
/// two days it covers. None of them show up as a crash — they show up as a
/// number that is wrong by a plausible amount, which is worse.
final class DailyStatsTests: XCTestCase {

    /// A zone with daylight saving, so the short and long days are testable.
    private let zone = TimeZone(identifier: "Europe/Paris")!
    private var savedCalendar: Calendar!

    private let day1 = CalendarDay(year: 2026, month: 3, day: 10)
    private let day2 = CalendarDay(year: 2026, month: 3, day: 11)
    private let day3 = CalendarDay(year: 2026, month: 3, day: 12)

    private let minute: Int64 = 60_000
    private var hour: Int64 { 60 * minute }

    override func setUp() {
        super.setUp()
        savedCalendar = SproutFormat.calendar
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        SproutFormat.calendar = calendar
    }

    override func tearDown() {
        SproutFormat.calendar = savedCalendar
        super.tearDown()
    }

    private func at(_ date: CalendarDay, _ hour: Int, _ minute: Int = 0) -> Int64 {
        SproutFormat.settingTime(hour: hour, minute: minute, on: date.startMillis())
    }

    private func stats(
        feedings: [Feeding] = [],
        sleeps: [Sleep] = [],
        diapers: [Diaper] = [],
        from: CalendarDay? = nil,
        to: CalendarDay? = nil,
        now: Int64? = nil
    ) -> [DayStats] {
        dailyStats(
            feedings: feedings,
            sleeps: sleeps,
            diapers: diapers,
            from: from ?? day1,
            to: to ?? day3,
            now: now ?? at(day3, 23, 59)
        )
    }

    private func bottle(_ date: CalendarDay, _ hour: Int, minute: Int = 0, ml: Int?) -> Feeding {
        Feeding(type: .BOTTLE, amountMl: ml, startTime: at(date, hour, minute))
    }

    private func solid(_ date: CalendarDay, _ hour: Int, grams: Int?) -> Feeding {
        Feeding(type: .SOLID, amountGrams: grams, startTime: at(date, hour))
    }

    // MARK: - The days themselves

    func testEveryDayInTheWindowIsThereEvenTheEmptyOnes() {
        let days = stats(feedings: [bottle(day2, 10, ml: 120)])

        XCTAssertEqual(days.map(\.date), [day1, day2, day3])
        XCTAssertEqual(days[0].feedCount, 0)
        XCTAssertEqual(days[1].feedCount, 1)
        XCTAssertEqual(days[2].feedCount, 0)
    }

    func testAnEmptyWindowIsEmpty() {
        XCTAssertTrue(stats(from: day3, to: day1).isEmpty)
    }

    func testBottlesCountTheirMillilitresAndSolidsTheirGrams() {
        let days = stats(feedings: [
            bottle(day2, 8, ml: 120),
            bottle(day2, 12, ml: 90),
            // Logged without an amount: it happened, but nothing was measured.
            bottle(day2, 16, ml: nil),
            solid(day2, 18, grams: 60),
            solid(day2, 20, grams: nil),
        ])

        XCTAssertEqual(days[1].bottleCount, 3)
        XCTAssertEqual(days[1].bottleMl, 210)
        XCTAssertEqual(days[1].solidCount, 2)
        XCTAssertEqual(days[1].solidGrams, 60)
        XCTAssertEqual(days[1].feedCount, 5)
    }

    func testBreastfeedsCountTheirTimeAtTheBreast() {
        let start = at(day2, 9)
        let segmented = Feeding(
            type: .BREAST,
            side: .BOTH,
            startTime: start,
            endTime: start + 13 * minute,
            leftDurationMs: 9 * minute,
            rightDurationMs: 4 * minute,
            segments: [
                NursingSegment(side: .LEFT, startTime: start, endTime: start + 6 * minute),
                NursingSegment(side: .RIGHT, startTime: start + 6 * minute, endTime: start + 10 * minute),
                NursingSegment(side: .LEFT, startTime: start + 10 * minute, endTime: start + 13 * minute),
            ]
        )

        let days = stats(feedings: [segmented])

        XCTAssertEqual(days[1].breastCount, 1)
        XCTAssertEqual(days[1].breastMillis, 13 * minute)
    }

    /// Three generations of the record, each answering when the newer one has
    /// nothing to say.
    func testBreastfeedDurationFallsBackThroughEveryGeneration() {
        let start = at(day2, 9)

        // Timed stretch by stretch — the segments win, even against the totals.
        XCTAssertEqual(
            breastfeedMillis(Feeding(
                type: .BREAST,
                startTime: start,
                endTime: start + 30 * minute,
                leftDurationMs: 99 * minute,
                segments: [NursingSegment(side: .LEFT, startTime: start, endTime: start + 10 * minute)]
            )),
            10 * minute
        )

        // Per-side totals, from before segments were recorded.
        XCTAssertEqual(
            breastfeedMillis(Feeding(
                type: .BREAST,
                startTime: start,
                leftDurationMs: 8 * minute,
                rightDurationMs: 4 * minute
            )),
            12 * minute
        )

        // Nothing but a start and an end.
        XCTAssertEqual(
            breastfeedMillis(Feeding(type: .BREAST, startTime: start, endTime: start + 7 * minute)),
            7 * minute
        )

        // A session still running has no duration yet, and an end before its
        // start does not subtract time from the day.
        XCTAssertEqual(breastfeedMillis(Feeding(type: .BREAST, startTime: start)), 0)
        XCTAssertEqual(
            breastfeedMillis(Feeding(type: .BREAST, startTime: start, endTime: start - hour)),
            0
        )
    }

    // MARK: - Sleep, which is the one that is split

    func testANightIsSplitAcrossTheTwoDaysItCovers() {
        let days = stats(sleeps: [Sleep(startTime: at(day1, 20), endTime: at(day2, 6))])

        XCTAssertEqual(days[0].sleepMillis, 4 * hour)
        XCTAssertEqual(days[1].sleepMillis, 6 * hour)
        // But the baby only settled once, on the evening it started.
        XCTAssertEqual(days[0].sleepCount, 1)
        XCTAssertEqual(days[1].sleepCount, 0)
    }

    func testNapsAddUpWithinTheirDay() {
        let days = stats(sleeps: [
            Sleep(startTime: at(day2, 9), endTime: at(day2, 10, 30)),
            Sleep(startTime: at(day2, 14), endTime: at(day2, 15)),
        ])

        XCTAssertEqual(days[1].sleepCount, 2)
        XCTAssertEqual(days[1].sleepMillis, 150 * minute)
    }

    func testASleepStillRunningCountsUpToNowAndNoFurther() {
        let days = stats(sleeps: [Sleep(startTime: at(day3, 8))], now: at(day3, 10))

        XCTAssertEqual(days[2].sleepMillis, 2 * hour)
    }

    func testASleepEndingBeforeItStartedContributesNothing() {
        let days = stats(sleeps: [Sleep(startTime: at(day2, 10), endTime: at(day2, 8))])

        XCTAssertEqual(days[1].sleepMillis, 0)
        XCTAssertEqual(days[1].sleepCount, 1)
    }

    func testSleepOutsideTheWindowIsIgnoredButItsOverlapIsNot() {
        // Starts the evening before the window and runs into its first morning.
        let days = stats(sleeps: [
            Sleep(startTime: at(day1.adding(days: -1), 22), endTime: at(day1, 5)),
        ])

        XCTAssertEqual(days[0].sleepMillis, 5 * hour)
        // It did not start inside the window, so it is not one of its sleeps.
        XCTAssertEqual(days[0].sleepCount, 0)
    }

    /// Europe/Paris springs forward on 29 March 2026: 02:00 becomes 03:00.
    ///
    /// This is what the calendar arithmetic is for. Counted in 24-hour steps the
    /// second day would come out at seven hours of sleep, an hour that did not
    /// happen.
    func testTheShortDayOfADaylightSavingChangeIsCountedAsItWasLived() {
        let spring = CalendarDay(year: 2026, month: 3, day: 29)
        let eve = spring.adding(days: -1)

        let days = dailyStats(
            feedings: [],
            sleeps: [Sleep(startTime: at(eve, 23), endTime: at(spring, 7))],
            diapers: [],
            from: eve,
            to: spring,
            now: at(spring, 23)
        )

        XCTAssertEqual(days[0].sleepMillis, hour)
        // 00:00 to 07:00 on the clock, but only six hours actually happened.
        XCTAssertEqual(days[1].sleepMillis, 6 * hour)
    }

    // MARK: - Nappies

    func testAChangeCanBeBothWetAndDirty() {
        let days = stats(diapers: [
            Diaper(time: at(day2, 8), wet: true),
            Diaper(time: at(day2, 11), wet: true, dirty: true),
            Diaper(time: at(day2, 15), dirty: true),
        ])

        XCTAssertEqual(days[1].diaperCount, 3)
        XCTAssertEqual(days[1].wetCount, 2)
        XCTAssertEqual(days[1].dirtyCount, 2)
    }

    func testTheStackedSegmentsAddUpToTheNumberOfChanges() {
        let days = stats(diapers: [
            Diaper(time: at(day2, 8), wet: true),
            Diaper(time: at(day2, 11), wet: true, dirty: true),
            Diaper(time: at(day2, 13), wet: true, dirty: true),
            Diaper(time: at(day2, 15), dirty: true),
        ])
        let day = days[1]

        XCTAssertEqual(day.bothCount, 2)
        XCTAssertEqual(day.wetOnlyCount, 1)
        XCTAssertEqual(day.dirtyOnlyCount, 1)
        // Which is the whole point: stacking `wet` and `dirty` as they come
        // would draw six changes where four happened.
        XCTAssertEqual(day.diaperCount, day.wetOnlyCount + day.bothCount + day.dirtyOnlyCount)
    }

    func testAFeedJustBeforeMidnightBelongsToTheDayItStarted() {
        let days = stats(feedings: [bottle(day1, 23, minute: 55, ml: 100)])

        XCTAssertEqual(days[0].bottleMl, 100)
        XCTAssertEqual(days[1].bottleMl, 0)
    }

    // MARK: - Averages

    func testAveragesLeaveOutTheDayStillBeingLived() {
        let days = stats(feedings: [
            bottle(day1, 9, ml: 100),
            bottle(day1, 15, ml: 100),
            bottle(day2, 9, ml: 200),
            bottle(day2, 15, ml: 200),
            // Today, half over: one feed so far.
            bottle(day3, 9, ml: 60),
        ])

        let averages = averagesOf(days, today: day3)

        XCTAssertEqual(averages.dayCount, 2)
        XCTAssertEqual(averages.feedsPerDay, 2.0, accuracy: 1e-9)
        XCTAssertEqual(averages.bottleMlPerDay, 300)
    }

    func testOnTheFirstDayTodayIsAllThereIs() {
        let days = stats(feedings: [bottle(day3, 9, ml: 60)], from: day3, to: day3)
        let averages = averagesOf(days, today: day3)

        XCTAssertEqual(averages.dayCount, 1)
        XCTAssertEqual(averages.bottleMlPerDay, 60)
    }

    func testWithNothingToAverageEverythingIsZeroRatherThanUndefined() {
        let averages = averagesOf([], today: day3)

        XCTAssertEqual(averages.dayCount, 0)
        XCTAssertEqual(averages.feedsPerDay, 0)
        XCTAssertEqual(averages.sleepMillisPerDay, 0)
        XCTAssertEqual(averages.bottleMlPerDay, 0)
    }

    // MARK: - The window

    func testTheWindowNeverReachesBackPastTheBirth() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)
        let born = CalendarDay(year: 2026, month: 3, day: 8) // twelve days old

        // A window that fits inside the baby's life is untouched.
        XCTAssertEqual(
            statsWindow(today: today, days: 7, birthDay: born),
            StatsWindow(from: today.adding(days: -6), to: today)
        )
        // One that does not stops at the birth, rather than averaging over
        // eighteen days the baby was not there for.
        XCTAssertEqual(
            statsWindow(today: today, days: 30, birthDay: born),
            StatsWindow(from: born, to: today)
        )
        XCTAssertEqual(
            statsWindow(today: today, days: 90, birthDay: born),
            StatsWindow(from: born, to: today)
        )
    }

    func testABirthDateInTheFutureStillLeavesToday() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)

        // Typed ahead of a due date: the window must not end before it starts.
        XCTAssertEqual(
            statsWindow(today: today, days: 30, birthDay: today.adding(days: 40)),
            StatsWindow(from: today, to: today)
        )
    }

    func testWithNoBirthDateTheWindowIsTheWholePeriod() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)

        XCTAssertEqual(
            statsWindow(today: today, days: 30, birthDay: nil),
            StatsWindow(from: today.adding(days: -29), to: today)
        )
        // A one-day window is today alone, not an empty one.
        XCTAssertEqual(
            statsWindow(today: today, days: 1, birthDay: nil),
            StatsWindow(from: today, to: today)
        )
    }

    func testAnOffsetWalksTheWholeWindowIntoThePast() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)
        let born = CalendarDay(year: 2026, month: 1, day: 1)

        XCTAssertEqual(
            statsWindow(today: today, days: 7, birthDay: born, offset: 0),
            StatsWindow(from: today.adding(days: -6), to: today)
        )
        // A week back: both ends move together, and the window keeps its width.
        XCTAssertEqual(
            statsWindow(today: today, days: 7, birthDay: born, offset: 7),
            StatsWindow(from: today.adding(days: -13), to: today.adding(days: -7))
        )
    }

    func testTheOffsetStopsAtTheBirthRatherThanRunningOffTheStartOfTheLife() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)
        let born = CalendarDay(year: 2026, month: 3, day: 8)

        XCTAssertEqual(maxStatsOffset(today: today, birthDay: born), 12)
        // Asked for more than there is, the window parks on the day of birth.
        XCTAssertEqual(
            statsWindow(today: today, days: 7, birthDay: born, offset: 99),
            StatsWindow(from: born, to: born)
        )
        // And an offset can never be negative — there is no data after today.
        XCTAssertEqual(
            statsWindow(today: today, days: 7, birthDay: born, offset: -5),
            statsWindow(today: today, days: 7, birthDay: born, offset: 0)
        )
    }

    func testWithNoBabyThereIsNowhereToWalkTo() {
        let today = CalendarDay(year: 2026, month: 3, day: 20)

        XCTAssertEqual(maxStatsOffset(today: today, birthDay: nil), 0)
        XCTAssertEqual(maxStatsOffset(today: today, birthDay: today), 0)
        XCTAssertEqual(maxStatsOffset(today: today, birthDay: today.adding(days: 10)), 0)
    }

    /// The bug this guards: thirty days of window over twelve days of life used
    /// to divide a newborn's ten feeds a day down to four.
    func testAShortenedWindowAveragesOverTheDaysItActuallyCovers() {
        let window = statsWindow(today: day3, days: 30, birthDay: day1)
        let feeds = [day1, day2, day3].flatMap { date in
            (0..<10).map { bottle(date, 6 + $0, ml: 60) }
        }

        let days = dailyStats(
            feedings: feeds,
            sleeps: [],
            diapers: [],
            from: window.from,
            to: window.to,
            now: at(day3, 23, 59)
        )
        let averages = averagesOf(days, today: day3)

        XCTAssertEqual(days.count, 3)
        XCTAssertEqual(averages.dayCount, 2)
        XCTAssertEqual(averages.feedsPerDay, 10.0, accuracy: 1e-9)
    }

    // MARK: - Where the sleeps happened, and how they were lying (BDR-0014)

    private func breakdown(
        _ sleeps: [Sleep],
        from: CalendarDay? = nil,
        to: CalendarDay? = nil,
        now: Int64? = nil
    ) -> SleepBreakdown {
        sleepBreakdown(
            sleeps,
            from: from ?? day1,
            to: to ?? day3,
            now: now ?? at(day3, 23, 59)
        )
    }

    func testSleepsAreGroupedByPlaceLongestFirst() {
        let result = breakdown([
            Sleep(startTime: at(day1, 9), endTime: at(day1, 10), place: .ON_A_PARENT),
            Sleep(startTime: at(day2, 20), endTime: at(day2, 23), place: .OWN_BED),
            Sleep(startTime: at(day3, 14), endTime: at(day3, 15), place: .OWN_BED),
        ])

        let expected: [SleepWhere?] = [.offered(.OWN_BED), .offered(.ON_A_PARENT)]
        XCTAssertEqual(result.byPlace.map(\.value), expected)
        XCTAssertEqual(result.byPlace[0].count, 2)
        XCTAssertEqual(result.byPlace[0].millis, 4 * hour)
        XCTAssertEqual(result.byPlace[1].count, 1)
        XCTAssertEqual(result.byPlace[1].millis, hour)
        XCTAssertEqual(result.totalCount, 3)
        XCTAssertEqual(result.totalMillis, 5 * hour)
    }

    func testPositionsAreGroupedTheSameWay() {
        let result = breakdown([
            Sleep(startTime: at(day1, 9), endTime: at(day1, 10), position: .BACK),
            Sleep(startTime: at(day2, 9), endTime: at(day2, 12), position: .BELLY),
            Sleep(startTime: at(day3, 9), endTime: at(day3, 10), position: .BACK),
        ])

        XCTAssertTrue(result.hasPositions)
        let expected: [SleepPosition?] = [.BELLY, .BACK]
        XCTAssertEqual(result.byPosition.map(\.value), expected)
        XCTAssertEqual(result.byPosition[0].millis, 3 * hour)
        XCTAssertEqual(result.byPosition[1].count, 2)
    }

    /// The shares have to be shares of the sleep the card is already showing. A
    /// screen that dropped the unrecorded ones would report "80% in their own
    /// bed" off two logged naps out of ten.
    func testSleepsThatSayNothingAreTheLastLineNotDroppedFromTheTotal() {
        let result = breakdown([
            Sleep(startTime: at(day1, 9), endTime: at(day1, 10), place: .OWN_BED),
            // Three hours nobody said anything about — longer than the one that
            // was recorded, and still last.
            Sleep(startTime: at(day2, 9), endTime: at(day2, 12)),
        ])

        XCTAssertTrue(result.hasPlaces)
        XCTAssertNil(result.byPlace.last?.value, "the unrecorded line comes last")
        XCTAssertEqual(result.byPlace.last?.millis, 3 * hour)
        XCTAssertEqual(result.byPlace.reduce(Int64(0)) { $0 + $1.millis }, result.totalMillis)
        XCTAssertEqual(result.byPlace.reduce(0) { $0 + $1.count }, result.totalCount)
    }

    func testNothingRecordedAtAllIsNothingToShow() {
        let result = breakdown([Sleep(startTime: at(day2, 9), endTime: at(day2, 10))])

        XCTAssertEqual(result.byPlace.count, 1)
        XCTAssertNil(result.byPlace.first?.value, "one line, and it is the unrecorded one")
        XCTAssertFalse(result.hasPlaces)
        XCTAssertFalse(result.hasPositions)
    }

    /// Someone who logs "pram" a dozen times wants to see "pram", not a dozen
    /// sleeps filed under "somewhere else".
    func testAPlaceTheParentNamedGroupsByThatNameWhateverTheSpelling() {
        let result = breakdown([
            Sleep(startTime: at(day1, 9), endTime: at(day1, 10), place: .OTHER, placeNote: "Pram"),
            Sleep(startTime: at(day2, 9), endTime: at(day2, 10), place: .OTHER, placeNote: "  pram "),
        ])

        // One line, spelled the way it was first typed.
        let expected: [SleepWhere?] = [.named("Pram")]
        XCTAssertEqual(result.byPlace.map(\.value), expected)
        XCTAssertEqual(result.byPlace.first?.count, 2)
    }

    func testSomewhereElseWithNoNameIsJustSomewhereElse() {
        let result = breakdown([
            Sleep(startTime: at(day2, 9), endTime: at(day2, 10), place: .OTHER, placeNote: "   "),
        ])

        let expected: [SleepWhere?] = [.offered(.OTHER)]
        XCTAssertEqual(result.byPlace.map(\.value), expected)
        XCTAssertTrue(result.hasPlaces, "a place was recorded, even if it was not named")
    }

    func testANightBegunBeforeTheWindowBringsItsHoursButNotItsCount() {
        let result = breakdown([
            Sleep(
                startTime: at(day1.adding(days: -1), 20),
                endTime: at(day1, 6),
                place: .PARENTS_BED
            ),
        ])

        // Six of the ten hours were slept inside the window; the baby settled
        // outside it, so the times-settled tally leaves it alone — the same rule
        // the daily figures follow.
        XCTAssertEqual(result.byPlace.first?.millis, 6 * hour)
        XCTAssertEqual(result.byPlace.first?.count, 0)
        XCTAssertEqual(result.totalCount, 0)
        XCTAssertEqual(result.totalMillis, 6 * hour)
    }

    func testASleepStillRunningIsCountedUpToNow() {
        let result = breakdown(
            [Sleep(startTime: at(day3, 8), place: .AT_BREAST)],
            now: at(day3, 10)
        )

        XCTAssertEqual(result.byPlace.first?.millis, 2 * hour)
        XCTAssertEqual(result.byPlace.first?.count, 1)
    }

    func testSleepsOutsideTheWindowAreNotThere() {
        let after = day3.adding(days: 1)
        let result = breakdown(
            [Sleep(startTime: at(after, 9), endTime: at(after, 10), place: .OWN_BED)],
            now: at(after, 23)
        )

        XCTAssertTrue(result.byPlace.isEmpty)
        XCTAssertEqual(result.totalMillis, 0)
    }

    func testAnEmptyWindowBreaksNothingDown() {
        let result = breakdown(
            [Sleep(startTime: at(day2, 9), endTime: at(day2, 10), place: .OWN_BED)],
            from: day3,
            to: day1
        )

        XCTAssertTrue(result.byPlace.isEmpty)
        XCTAssertTrue(result.byPosition.isEmpty)
    }
}
