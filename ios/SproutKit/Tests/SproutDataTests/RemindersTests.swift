import Foundation
import XCTest
@testable import SproutData

/// When a reminder is due — the arithmetic, away from any way of delivering it.
/// The counterparts of `FeedingReminderLogicTest` and
/// `FeedingReminderOverrideTest`.
final class RemindersTests: XCTestCase {

    private var store: InMemoryStore!

    override func setUp() {
        super.setUp()
        store = InMemoryStore()
        // A fixed zone, so a machine in Auckland and one in CI agree about which
        // day 09:00 falls on.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Zurich") ?? .gmt
        SproutFormat.calendar = calendar
    }

    override func tearDown() {
        SproutFormat.calendar = .autoupdatingCurrent
        super.tearDown()
    }

    // MARK: - Feeding

    func testTriggerIsLastFeedPlusInterval() {
        let last: Int64 = 1_000_000
        XCTAssertEqual(feedingReminderTrigger(lastFeedTime: last, intervalMinutes: 180),
                       last + 180 * 60_000)
    }

    func testOverdueIsFalseBeforeTheInterval() {
        XCTAssertFalse(
            feedingReminderOverdue(lastFeedTime: 0, now: 119 * 60_000, intervalMinutes: 120)
        )
    }

    func testOverdueIsTrueAtAndAfterTheInterval() {
        XCTAssertTrue(
            feedingReminderOverdue(lastFeedTime: 0, now: 120 * 60_000, intervalMinutes: 120)
        )
        XCTAssertTrue(
            feedingReminderOverdue(lastFeedTime: 0, now: 500 * 60_000, intervalMinutes: 120)
        )
    }

    // MARK: - The per-baby override

    private func baby(enabled: Bool? = nil, interval: Int? = nil) -> Baby {
        Baby(
            name: "Léa",
            birthDate: 0,
            feedingReminderEnabled: enabled,
            feedingReminderIntervalMinutes: interval
        )
    }

    /// The global default the four cases below are measured against: on, 3h.
    private func setGlobalDefault() {
        FeedingReminderSettings.setEnabled(true, in: store)
        FeedingReminderSettings.setIntervalMinutes(180, in: store)
    }

    func testNoOverrideFollowsTheGlobalDefault() {
        setGlobalDefault()
        let effective = effectiveFeedingReminder(baby: baby(), settings: store)
        XCTAssertTrue(effective.enabled)
        XCTAssertEqual(effective.intervalMinutes, 180)
    }

    func testAFullOverrideWinsOverTheGlobalDefault() {
        setGlobalDefault()
        let effective = effectiveFeedingReminder(
            baby: baby(enabled: false, interval: 120),
            settings: store
        )
        XCTAssertFalse(effective.enabled)
        XCTAssertEqual(effective.intervalMinutes, 120)
    }

    /// The two fields resolve independently, which is the whole point of the
    /// override: a shorter gap for one twin is not also a decision about whether
    /// reminders are on.
    func testAnIntervalOnlyOverrideKeepsTheGlobalOnOff() {
        setGlobalDefault()
        let effective = effectiveFeedingReminder(baby: baby(interval: 90), settings: store)
        XCTAssertTrue(effective.enabled)
        XCTAssertEqual(effective.intervalMinutes, 90)
    }

    func testAnEnabledOnlyOverrideKeepsTheGlobalInterval() {
        setGlobalDefault()
        FeedingReminderSettings.setEnabled(false, in: store)
        let effective = effectiveFeedingReminder(baby: baby(enabled: true), settings: store)
        XCTAssertTrue(effective.enabled)
        XCTAssertEqual(effective.intervalMinutes, 180)
    }

    /// Off until asked for. A reminder nobody requested, about a baby who may be
    /// asleep, is the one notification this app must not send unprompted.
    func testRemindersAreOffOnAPhoneThatHasNeverBeenAsked() {
        XCTAssertFalse(FeedingReminderSettings.isEnabled(store))
        XCTAssertEqual(FeedingReminderSettings.intervalMinutes(store), 180)
        XCTAssertFalse(effectiveFeedingReminder(baby: baby(), settings: store).enabled)
    }

    // MARK: - Treatments

    private func day(_ year: Int, _ month: Int, _ dayOfMonth: Int, _ hour: Int = 0, _ minute: Int = 0) -> Int64 {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = dayOfMonth
        parts.hour = hour
        parts.minute = minute
        let date = SproutFormat.calendar.date(from: parts)!
        return SproutFormat.millis(from: date)
    }

    private func treatment(
        start: Int64,
        end: Int64? = nil,
        everyDays: Int = 1
    ) -> Treatment {
        Treatment(
            name: "Vitamin D",
            intervalDays: everyDays,
            startDate: start,
            endDate: end
        )
    }

    func testTheFirstDoseIsOnTheStartDayWhenTheTimeHasNotPassed() {
        let course = treatment(start: day(2026, 3, 1))
        let trigger = nextTreatmentTrigger(
            treatment: course,
            minuteOfDay: 9 * 60,
            after: day(2026, 3, 1, 7, 0)
        )
        XCTAssertEqual(trigger, day(2026, 3, 1, 9, 0))
    }

    func testATimeAlreadyPastRollsToTheNextDosingDay() {
        let course = treatment(start: day(2026, 3, 1))
        let trigger = nextTreatmentTrigger(
            treatment: course,
            minuteOfDay: 9 * 60,
            after: day(2026, 3, 1, 10, 0)
        )
        XCTAssertEqual(trigger, day(2026, 3, 2, 9, 0))
    }

    /// Every third day means every third day. Rolling to "tomorrow" when today's
    /// time has passed would quietly turn the course daily.
    func testAnEveryThirdDayCourseStaysOnItsGrid() {
        let course = treatment(start: day(2026, 3, 1), everyDays: 3)

        XCTAssertEqual(
            nextTreatmentTrigger(treatment: course, minuteOfDay: 9 * 60, after: day(2026, 3, 1, 10, 0)),
            day(2026, 3, 4, 9, 0)
        )
        // Asked from a day that is not on the grid, it lands on the next one that
        // is — not on the day after the question.
        XCTAssertEqual(
            nextTreatmentTrigger(treatment: course, minuteOfDay: 9 * 60, after: day(2026, 3, 2, 12, 0)),
            day(2026, 3, 4, 9, 0)
        )
    }

    func testAskingBeforeTheCourseStartsGivesItsFirstDose() {
        let course = treatment(start: day(2026, 3, 10))
        XCTAssertEqual(
            nextTreatmentTrigger(treatment: course, minuteOfDay: 8 * 60, after: day(2026, 3, 1)),
            day(2026, 3, 10, 8, 0)
        )
    }

    func testThereIsNothingAfterTheCourseEnds() {
        let course = treatment(start: day(2026, 3, 1), end: day(2026, 3, 5))
        XCTAssertNil(
            nextTreatmentTrigger(treatment: course, minuteOfDay: 9 * 60, after: day(2026, 3, 5, 10, 0))
        )
    }

    func testTheLastDayOfACourseStillGetsItsDose() {
        let course = treatment(start: day(2026, 3, 1), end: day(2026, 3, 5))
        XCTAssertEqual(
            nextTreatmentTrigger(treatment: course, minuteOfDay: 9 * 60, after: day(2026, 3, 5, 7, 0)),
            day(2026, 3, 5, 9, 0)
        )
    }

    // MARK: - Growth spurts

    /// Nine in the morning on the first day of the next window — never the small
    /// hours, because this is information and not an alarm.
    func testTheNextGrowthSpurtIsAtNineOnTheWindowsFirstDay() throws {
        let birth = day(2026, 3, 1)
        let first = try XCTUnwrap(nextGrowthSpurtTrigger(birthDate: birth, now: birth))

        // The first window starts on day 7.
        XCTAssertEqual(first, day(2026, 3, 8, 9, 0))
    }

    func testEachWindowIsOfferedOnceTheLastHasPassed() throws {
        let birth = day(2026, 3, 1)
        let first = try XCTUnwrap(nextGrowthSpurtTrigger(birthDate: birth, now: birth))
        let second = try XCTUnwrap(nextGrowthSpurtTrigger(birthDate: birth, now: first))

        XCTAssertGreaterThan(second, first)
        // The second window starts on day 14.
        XCTAssertEqual(second, day(2026, 3, 15, 9, 0))
    }

    /// A baby who has outgrown the known windows gets nothing, rather than the
    /// last one over and over.
    func testThereIsNothingLeftAfterTheLastWindow() {
        let birth = day(2020, 1, 1)
        XCTAssertNil(nextGrowthSpurtTrigger(birthDate: birth, now: day(2026, 1, 1)))
    }

    func testGrowthSpurtAlertsAreOffUntilAskedFor() {
        XCTAssertFalse(GrowthSpurtSettings.isEnabled(store))
        GrowthSpurtSettings.setEnabled(true, in: store)
        XCTAssertTrue(GrowthSpurtSettings.isEnabled(store))
    }

    /// The reason this is day arithmetic and not milliseconds. Europe/Zurich
    /// springs forward on 2026-03-29; adding 24 hours across it would move a
    /// 09:00 dose to 10:00 and keep it there.
    func testADoseKeepsItsTimeAcrossADaylightSavingChange() throws {
        let course = treatment(start: day(2026, 3, 28))
        let trigger = nextTreatmentTrigger(
            treatment: course,
            minuteOfDay: 9 * 60,
            after: day(2026, 3, 28, 10, 0)
        )
        XCTAssertEqual(trigger, day(2026, 3, 29, 9, 0))

        // And it really is nine in the morning on the day the clocks moved.
        let parts = SproutFormat.calendar.dateComponents(
            [.hour, .minute],
            from: SproutFormat.date(from: try XCTUnwrap(trigger))
        )
        XCTAssertEqual(parts.hour, 9)
        XCTAssertEqual(parts.minute, 0)
    }
}
