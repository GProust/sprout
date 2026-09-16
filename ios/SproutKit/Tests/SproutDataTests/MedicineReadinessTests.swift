import XCTest
@testable import SproutData

/// The traffic light, as arithmetic (BDR-15).
///
/// The same cases as `MedicineReadinessTest.kt`, because the two platforms
/// answer this question separately and a household with one of each has to get
/// the same answer on both phones — in a feature where the answer is "may I give
/// my child another dose".
final class MedicineReadinessTests: XCTestCase {

    private let now: Int64 = 1_757_400_000_000
    private let hour: Int64 = 60 * 60 * 1000

    /// Paracetamol as the editor offers it: six hours at least, eight usually.
    private func paracetamol(
        maxPerDay: Int? = nil,
        comfort: Int? = 8 * 60,
        remind: Bool = false,
        remindAtComfort: Bool = false,
        active: Bool = true
    ) -> Medicine {
        Medicine(
            name: "Paracetamol",
            minIntervalMinutes: 6 * 60,
            comfortIntervalMinutes: comfort,
            maxPerDay: maxPerDay,
            remindWhenDue: remind,
            remindAtComfort: remindAtComfort,
            active: active,
            uid: "medicine-uid"
        )
    }

    private func dose(_ at: Int64, of: String = "medicine-uid", deletedAt: Int64? = nil) -> MedicineDose {
        MedicineDose(medicineUid: of, time: at, uid: "dose-\(at)", deletedAt: deletedAt)
    }

    func testAMedicineNeverGivenIsReadyNotRed() {
        let readiness = medicineReadiness(medicine: paracetamol(), doses: [], now: now)

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertNil(readiness.lastDoseAt, "no wait is running, so nothing to count down")
        XCTAssertEqual(readiness.dosesInLastDay, 0)
    }

    func testBeforeTheMinimumItIsTooSoon() {
        let readiness = medicineReadiness(
            medicine: paracetamol(), doses: [dose(now - 2 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .tooSoon)
        XCTAssertEqual(readiness.reason, .interval)
        XCTAssertEqual(readiness.nextAllowedAt, now + 4 * hour)
    }

    /// The boundary itself is past the wait, not still inside it.
    func testExactlyAtTheMinimumItIsAllowed() {
        let readiness = medicineReadiness(
            medicine: paracetamol(), doses: [dose(now - 6 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .soonerThanIdeal)
        XCTAssertNil(readiness.nextAllowedAt)
        XCTAssertEqual(readiness.comfortableAt, now + 2 * hour)
    }

    func testBetweenTheTwoItIsSoonerThanIdeal() {
        let readiness = medicineReadiness(
            medicine: paracetamol(), doses: [dose(now - 7 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .soonerThanIdeal)
        XCTAssertEqual(readiness.comfortableAt, now + hour)
    }

    func testPastTheComfortableIntervalItIsReady() {
        let readiness = medicineReadiness(
            medicine: paracetamol(), doses: [dose(now - 9 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertNil(readiness.comfortableAt)
    }

    /// With no comfortable interval there is no middle band at all: the medicine
    /// goes from red to ready at the one boundary the parent gave.
    func testAMedicineWithOnlyAMinimumHasNoMiddleBand() {
        let readiness = medicineReadiness(
            medicine: paracetamol(comfort: nil), doses: [dose(now - 6 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertNil(readiness.comfortableAt)
    }

    /// A comfortable interval below the minimum is a typo, not a second
    /// boundary. Taking the larger keeps the states in the order they assume
    /// rather than producing a band that ends before it begins.
    func testAComfortableIntervalShorterThanTheMinimumIsClampedUp() {
        let readiness = medicineReadiness(
            medicine: paracetamol(comfort: 2 * 60), doses: [dose(now - 3 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .tooSoon)
        XCTAssertEqual(readiness.nextAllowedAt, now + 3 * hour)
        // Clamped up to the minimum, so both boundaries land on the same moment
        // and the medicine goes straight from red to ready with no band between.
        XCTAssertEqual(readiness.comfortableAt, readiness.nextAllowedAt)
    }

    func testOnlyThisMedicinesDosesCount() {
        let readiness = medicineReadiness(
            medicine: paracetamol(),
            doses: [dose(now - hour, of: "another-medicine"), dose(now - 9 * hour)],
            now: now
        )

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertEqual(readiness.lastDoseAt, now - 9 * hour)
    }

    /// A deleted dose is not a dose; the light has to follow the correction.
    func testASoftDeletedDoseIsIgnored() {
        let readiness = medicineReadiness(
            medicine: paracetamol(),
            doses: [dose(now - hour, deletedAt: now), dose(now - 9 * hour)],
            now: now
        )

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertEqual(readiness.lastDoseAt, now - 9 * hour)
    }

    func testTheOrderOfTheDosesDoesNotMatter() {
        let ascending = [dose(now - 9 * hour), dose(now - 2 * hour)]

        XCTAssertEqual(
            medicineReadiness(medicine: paracetamol(), doses: ascending, now: now),
            medicineReadiness(medicine: paracetamol(), doses: ascending.reversed(), now: now)
        )
    }

    // MARK: - The daily maximum

    /// The interval has elapsed but the day's allowance is spent, so it is still
    /// red — for a reason the screen has to be able to say differently.
    func testASpentDailyMaximumHoldsItRedPastTheInterval() {
        let doses = [
            dose(now - 23 * hour),
            dose(now - 17 * hour),
            dose(now - 13 * hour),
            dose(now - 7 * hour),
        ]

        let readiness = medicineReadiness(
            medicine: paracetamol(maxPerDay: 4), doses: doses, now: now
        )

        XCTAssertEqual(readiness.level, .tooSoon)
        XCTAssertEqual(readiness.reason, .dailyMaximum)
        // Twenty-four hours after the fourth-most-recent dose, which is the one
        // that has to age out of the window.
        XCTAssertEqual(readiness.nextAllowedAt, now - 23 * hour + medicineDayMs)
        XCTAssertEqual(readiness.dosesInLastDay, 4)
    }

    /// Rolling, not calendar: a maximum counted per calendar day would allow
    /// four at 23:00 and four more at 00:30.
    func testADoseOlderThanTheWindowDoesNotCount() {
        let doses = [
            dose(now - 25 * hour),
            dose(now - 17 * hour),
            dose(now - 13 * hour),
            dose(now - 9 * hour),
        ]

        let readiness = medicineReadiness(
            medicine: paracetamol(maxPerDay: 4), doses: doses, now: now
        )

        XCTAssertEqual(readiness.level, .ready)
        XCTAssertEqual(readiness.dosesInLastDay, 3)
    }

    /// While the interval is still running that is the reason, even if the day's
    /// allowance is also spent: it is the one the parent is waiting on first.
    func testTheIntervalIsNamedWhileItIsStillRunning() {
        let doses = [
            dose(now - 23 * hour),
            dose(now - 17 * hour),
            dose(now - 11 * hour),
            dose(now - 2 * hour),
        ]

        let readiness = medicineReadiness(
            medicine: paracetamol(maxPerDay: 4), doses: doses, now: now
        )

        XCTAssertEqual(readiness.reason, .interval)
        // And the later of the two moments, which here is the interval's own:
        // the allowance frees up an hour from now, the six-hour wait in four.
        XCTAssertEqual(readiness.nextAllowedAt, now + 4 * hour)
    }

    func testADailyMaximumOfZeroIsNoMaximum() {
        let readiness = medicineReadiness(
            medicine: paracetamol(maxPerDay: 0), doses: [dose(now - 9 * hour)], now: now
        )

        XCTAssertEqual(readiness.level, .ready)
    }

    // MARK: - The reminder

    func testNoReminderWhenTheSwitchIsOff() {
        XCTAssertNil(
            nextMedicineReminder(
                medicine: paracetamol(), doses: [dose(now - 2 * hour)], now: now
            )
        )
    }

    func testNoReminderForAMedicineNeverGiven() {
        XCTAssertNil(
            nextMedicineReminder(medicine: paracetamol(remind: true), doses: [], now: now)
        )
    }

    func testByDefaultTheReminderIsAtTheMinimumWait() {
        XCTAssertEqual(
            nextMedicineReminder(
                medicine: paracetamol(remind: true), doses: [dose(now - 2 * hour)], now: now
            ),
            now + 4 * hour
        )
    }

    func testAParentCanAskForTheComfortableIntervalInstead() {
        XCTAssertEqual(
            nextMedicineReminder(
                medicine: paracetamol(remind: true, remindAtComfort: true),
                doses: [dose(now - 2 * hour)],
                now: now
            ),
            now + 6 * hour
        )
    }

    /// With no comfortable interval the switch is only about *whether* to
    /// remind: there is one boundary, so it falls back to it.
    func testTheComfortableChoiceFallsBackToTheMinimum() {
        XCTAssertEqual(
            nextMedicineReminder(
                medicine: paracetamol(comfort: nil, remind: true, remindAtComfort: true),
                doses: [dose(now - 2 * hour)],
                now: now
            ),
            now + 4 * hour
        )
    }

    /// A moment that has already passed is not a reminder.
    func testNoReminderOnceItIsAlreadyAvailable() {
        XCTAssertNil(
            nextMedicineReminder(
                medicine: paracetamol(remind: true), doses: [dose(now - 9 * hour)], now: now
            )
        )
    }

    func testTheDailyMaximumCanPushTheReminderPastBothIntervals() {
        // Both waits elapsed an hour or more ago; only the day's allowance is
        // still holding it, and it frees up an hour from now.
        let doses = [
            dose(now - 23 * hour),
            dose(now - 20 * hour),
            dose(now - 16 * hour),
            dose(now - 10 * hour),
        ]

        XCTAssertEqual(
            nextMedicineReminder(
                medicine: paracetamol(maxPerDay: 4, remind: true), doses: doses, now: now
            ),
            now - 23 * hour + medicineDayMs
        )
    }

    func testAnInactiveMedicineIsNotRemindedAbout() {
        XCTAssertNil(
            nextMedicineReminder(
                medicine: paracetamol(remind: true, active: false),
                doses: [dose(now - 2 * hour)],
                now: now
            )
        )
    }
}
