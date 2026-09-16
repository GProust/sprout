import XCTest
@testable import SproutData

/// The dashboard's arithmetic, which is where a wrong answer is most visible:
/// this is the screen a parent looks at first, half asleep, to decide what to do
/// next.
final class HouseholdSummaryTests: XCTestCase {

    private let now: Int64 = 1_757_400_000_000
    private var dayStart: Int64 { now - 14 * hour }

    private let hour: Int64 = 60 * 60 * 1000
    private let minute: Int64 = 60 * 1000

    private func baby(_ id: Int64, _ name: String) -> Baby {
        Baby(id: id, name: name, birthDate: now - 30 * 24 * hour)
    }

    private func summarise(
        babies: [Baby],
        feedings: [Feeding] = [],
        sleeps: [Sleep] = [],
        diapers: [Diaper] = [],
        ongoing: [Sleep] = [],
        medicines: [Medicine] = [],
        doses: [MedicineDose] = [],
        dismissed: [String: Int64] = [:]
    ) -> [BabySummary] {
        summariseHousehold(
            babies: babies,
            feedings: feedings,
            sleeps: sleeps,
            diapers: diapers,
            ongoingSleeps: ongoing,
            medicines: medicines,
            medicineDoses: doses,
            dismissedMedicines: dismissed,
            dayStart: dayStart,
            now: now
        )
    }

    private func medicine(
        _ babyId: Int64,
        _ uid: String,
        _ name: String,
        minHours: Int = 6,
        comfortHours: Int? = 8
    ) -> Medicine {
        Medicine(
            babyId: babyId,
            name: name,
            minIntervalMinutes: minHours * 60,
            comfortIntervalMinutes: comfortHours.map { $0 * 60 },
            uid: uid
        )
    }

    private func dose(_ babyId: Int64, _ medicineUid: String, _ at: Int64) -> MedicineDose {
        MedicineDose(babyId: babyId, medicineUid: medicineUid, time: at)
    }

    // MARK: - Per baby

    /// Twins are the case this exists for: two babies, one dashboard, and no
    /// leakage between their lines.
    func testEachBabyGetsOnlyItsOwnLogs() {
        let robin = baby(1, "Robin")
        let sam = baby(2, "Sam")

        let summaries = summarise(
            babies: [robin, sam],
            feedings: [
                Feeding(babyId: 1, type: .BOTTLE, startTime: now - hour),
                Feeding(babyId: 1, type: .BOTTLE, startTime: now - 3 * hour),
                Feeding(babyId: 2, type: .BOTTLE, startTime: now - 2 * hour),
            ],
            diapers: [Diaper(babyId: 2, time: now - 30 * minute, wet: true)]
        )

        XCTAssertEqual(summaries[0].feedsToday, 2)
        XCTAssertEqual(summaries[0].diapersToday, 0)
        XCTAssertEqual(summaries[1].feedsToday, 1)
        XCTAssertEqual(summaries[1].diapersToday, 1)
    }

    func testABabyWithNothingLoggedSummarisesToNothing() {
        let summaries = summarise(babies: [baby(1, "Robin")])

        XCTAssertEqual(summaries.count, 1)
        XCTAssertNil(summaries[0].lastFeed)
        XCTAssertNil(summaries[0].lastSleep)
        XCTAssertNil(summaries[0].lastDiaper)
        XCTAssertEqual(summaries[0].feedsToday, 0)
        XCTAssertEqual(summaries[0].sleepTodayMs, 0)
        XCTAssertNil(summaries[0].nextSide)
        XCTAssertNil(summaries[0].ongoingSleep)
    }

    /// Yesterday's entries still show as "last fed", but must not count towards
    /// today's totals.
    func testYesterdayCountsAsLastButNotAsToday() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [Feeding(babyId: 1, type: .BOTTLE, startTime: dayStart - hour)]
        )

        XCTAssertEqual(summaries[0].lastFeed, dayStart - hour)
        XCTAssertEqual(summaries[0].feedsToday, 0)
    }

    // MARK: - Sleep

    /// "Last slept" is about a sleep that finished. One still running is its own
    /// field, because those two say different things to a parent.
    func testLastSleepIgnoresOneStillRunning() {
        let running = Sleep(babyId: 1, startTime: now - 20 * minute)

        let summaries = summarise(
            babies: [baby(1, "Robin")],
            sleeps: [
                Sleep(babyId: 1, startTime: now - 5 * hour, endTime: now - 4 * hour),
                running,
            ],
            ongoing: [running]
        )

        XCTAssertEqual(summaries[0].lastSleep, now - 4 * hour)
        XCTAssertEqual(summaries[0].ongoingSleep?.startTime, running.startTime)
    }

    /// A nap still running counts up to now, or the day's total stops moving
    /// while the baby is actually asleep.
    func testAnOngoingSleepCountsUpToNow() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            sleeps: [Sleep(babyId: 1, startTime: now - 40 * minute)]
        )

        XCTAssertEqual(summaries[0].sleepTodayMs, 40 * minute)
    }

    func testSleepTodaySumsEveryNapSinceMidnight() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            sleeps: [
                Sleep(babyId: 1, startTime: now - 6 * hour, endTime: now - 5 * hour),
                Sleep(babyId: 1, startTime: now - 3 * hour, endTime: now - 2 * hour),
                // Yesterday's night: outside the window, so not today's.
                Sleep(babyId: 1, startTime: dayStart - 3 * hour, endTime: dayStart - hour),
            ]
        )

        XCTAssertEqual(summaries[0].sleepTodayMs, 2 * hour)
    }

    /// An end logged slightly before its start is a typo, and must not subtract
    /// from the day.
    func testABackwardsSleepDoesNotSubtract() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            sleeps: [
                Sleep(babyId: 1, startTime: now - 2 * hour, endTime: now - 3 * hour),
                Sleep(babyId: 1, startTime: now - 5 * hour, endTime: now - 4 * hour),
            ]
        )

        XCTAssertEqual(summaries[0].sleepTodayMs, hour)
    }

    // MARK: - Which breast next

    func testNextBreastIsTheOppositeOfWhereTheLastOneBegan() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [Feeding(babyId: 1, type: .BREAST, side: .LEFT, startTime: now - 2 * hour)]
        )

        XCTAssertEqual(summaries[0].nextSide, .RIGHT)
    }

    /// A session that went left then right still counts as left: the side to
    /// offer next is the opposite of where it *began*.
    func testASwitchedSessionCountsAsTheSideItStartedOn() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [
                Feeding(
                    babyId: 1,
                    type: .BREAST,
                    side: .BOTH,
                    startTime: now - 2 * hour,
                    segments: [
                        NursingSegment(side: .LEFT, startTime: now - 2 * hour, endTime: now - 100 * minute),
                        NursingSegment(side: .RIGHT, startTime: now - 100 * minute, endTime: now - 90 * minute),
                    ]
                )
            ]
        )

        XCTAssertEqual(summaries[0].nextSide, .RIGHT)
    }

    /// A bottle in between must not take the side with it — that was the whole
    /// reason this looks past the last feed.
    func testABottleInBetweenDoesNotTakeTheSideWithIt() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [
                Feeding(babyId: 1, type: .BOTTLE, amountMl: 90, startTime: now - 30 * minute),
                Feeding(babyId: 1, type: .BREAST, side: .RIGHT, startTime: now - 3 * hour),
            ]
        )

        XCTAssertEqual(summaries[0].nextSide, .LEFT)
    }

    /// Past a day there is no alternation left to continue, so the app stops
    /// guessing rather than offering a stale answer.
    func testNothingNursedWithinADayAnswersNothing() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [
                Feeding(babyId: 1, type: .BREAST, side: .LEFT, startTime: now - 25 * hour)
            ]
        )

        XCTAssertNil(summaries[0].nextSide)
    }

    /// BOTH on an older entry says which breasts were used but not which came
    /// first, so it settles nothing.
    func testBothWithNoSegmentsAnswersNothing() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [Feeding(babyId: 1, type: .BREAST, side: .BOTH, startTime: now - hour)]
        )

        XCTAssertNil(summaries[0].nextSide)
    }

    func testABreastfeedWithNoSideAnswersNothing() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            feedings: [Feeding(babyId: 1, type: .BREAST, startTime: now - hour)]
        )

        XCTAssertNil(summaries[0].nextSide)
    }

    // MARK: - The dashboard's medicine card (BDR-16)
    //
    // The arithmetic itself is MedicineReadinessTests'; these are about which
    // medicines reach the dashboard at all, and under whose name. The same
    // cases run on Android in `HouseholdSummaryTest`.

    func testAMedicineNeverGivenStaysOffTheDashboard() {
        // Green because it has never been given is not news, and a household
        // with a shelf of medicines set up would otherwise have a dashboard
        // that is mostly shelf.
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [medicine(1, "m-para", "Paracetamol")]
        )
        XCTAssertTrue(summaries[0].medicines.isEmpty)
    }

    func testARunningWaitReachesTheDashboard() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [medicine(1, "m-para", "Paracetamol")],
            doses: [dose(1, "m-para", now - 2 * hour)]
        )
        XCTAssertEqual(summaries[0].medicines.count, 1)
        XCTAssertEqual(summaries[0].medicines[0].medicine.name, "Paracetamol")
    }

    func testADoseNeverCountsAgainstAnotherBabysMedicine() {
        // Two babies, the same medicine name, one dose. The sibling's card must
        // read as untouched.
        let summaries = summarise(
            babies: [baby(1, "Robin"), baby(2, "Sam")],
            medicines: [
                medicine(1, "m-robin", "Paracetamol"),
                medicine(2, "m-sam", "Paracetamol"),
            ],
            doses: [dose(1, "m-robin", now - 2 * hour)]
        )
        XCTAssertEqual(summaries[0].medicines.count, 1)
        XCTAssertTrue(summaries[1].medicines.isEmpty)
    }

    func testWhatNeedsNoSecondThoughtComesFirst() {
        // Green, then amber, then red. Both of the first two may be given, but
        // one is the dose the parent was told to give and the other is the dose
        // they are allowed to give early.
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [
                // Alphabetically the reverse of the order expected, so a list
                // that only sorted by name would pass this by accident.
                medicine(1, "m-red", "Aspirin"),
                medicine(1, "m-amber", "Ibuprofen"),
                medicine(1, "m-green", "Paracetamol"),
            ],
            doses: [
                dose(1, "m-red", now - 2 * hour),
                dose(1, "m-amber", now - 7 * hour),
                dose(1, "m-green", now - 9 * hour),
            ]
        )
        XCTAssertEqual(
            summaries[0].medicines.map(\.medicine.name),
            ["Paracetamol", "Ibuprofen", "Aspirin"]
        )
    }

    func testTheSoonestToBeGivenComesFirst() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [
                medicine(1, "m-a", "Ibuprofen", minHours: 8, comfortHours: nil),
                medicine(1, "m-b", "Paracetamol", minHours: 4, comfortHours: nil),
            ],
            doses: [
                dose(1, "m-a", now - hour),
                dose(1, "m-b", now - hour),
            ]
        )
        XCTAssertEqual(
            summaries[0].medicines.map(\.medicine.name),
            ["Paracetamol", "Ibuprofen"]
        )
    }

    func testADismissedMedicineIsPutAway() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [medicine(1, "m-para", "Paracetamol")],
            doses: [dose(1, "m-para", now - 2 * hour)],
            dismissed: ["m-para": now - 2 * hour]
        )
        XCTAssertTrue(summaries[0].medicines.isEmpty)
    }

    func testTheNextDoseBringsADismissedMedicineBack() {
        // The dismissal names the dose it was made against, so it expires on its
        // own rather than needing to be cleared: a newer dose is a different
        // wait, and the card is about the wait that is running.
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [medicine(1, "m-para", "Paracetamol")],
            doses: [
                dose(1, "m-para", now - 8 * hour),
                dose(1, "m-para", now - hour),
            ],
            dismissed: ["m-para": now - 8 * hour]
        )
        XCTAssertEqual(summaries[0].medicines.count, 1)
    }

    func testADismissalNamesOneMedicineOnly() {
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [
                medicine(1, "m-para", "Paracetamol"),
                medicine(1, "m-ibu", "Ibuprofen"),
            ],
            doses: [
                dose(1, "m-para", now - 2 * hour),
                dose(1, "m-ibu", now - 2 * hour),
            ],
            dismissed: ["m-para": now - 2 * hour]
        )
        XCTAssertEqual(summaries[0].medicines.map(\.medicine.name), ["Ibuprofen"])
    }

    func testAnInactiveMedicineIsNotWatched() {
        var stopped = medicine(1, "m-para", "Paracetamol")
        stopped.active = false
        let summaries = summarise(
            babies: [baby(1, "Robin")],
            medicines: [stopped],
            doses: [dose(1, "m-para", now - 2 * hour)]
        )
        XCTAssertTrue(summaries[0].medicines.isEmpty)
    }
}
