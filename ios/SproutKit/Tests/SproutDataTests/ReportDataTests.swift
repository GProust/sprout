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
        medicines: [Medicine] = [],
        medicineDoses: [MedicineDose] = [],
        options: ReportOptions = ReportOptions(period: .week),
        foodName: (String) -> String? = { _ in nil },
        allergies: [Allergy] = []
    ) -> ReportContent {
        buildReport(
            baby: baby,
            feedings: feedings,
            sleeps: sleeps,
            diapers: diapers,
            growth: growth,
            treatments: treatments,
            medicines: medicines,
            medicineDoses: medicineDoses,
            options: options,
            now: now,
            foodName: foodName,
            allergies: allergies
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

    // MARK: - As-needed medicine in the doctor's record (BDR-18)

    private func gel(uid: String = "gel") -> Medicine {
        Medicine(
            babyId: 1,
            name: "Teething gel",
            minIntervalMinutes: 0,
            maxPerDay: 6,
            doseAmount: 0.25,
            doseUnit: "cm",
            maxAmountPerDay: 1.5,
            uid: uid
        )
    }

    private func gelDose(_ at: Int64, amount: Double? = 0.25, uid: String = "gel") -> MedicineDose {
        MedicineDose(babyId: 1, medicineUid: uid, time: at, amount: amount)
    }

    func testAMedicineReachesTheRecordWithTheDosesInsideTheRange() throws {
        let content = report(
            medicines: [gel()],
            medicineDoses: [gelDose(at(today, 9)), gelDose(at(today, 14))]
        )

        let record = try XCTUnwrap(content.medicines.first)
        XCTAssertEqual(content.medicines.count, 1)
        XCTAssertEqual(record.medicine.name, "Teething gel")
        XCTAssertEqual(record.doses.count, 2)
        XCTAssertEqual(record.amountTotal, 0.5, accuracy: 1e-9)
        XCTAssertTrue(record.hasAmounts)
    }

    /// A shelf is not an event: a medicine set up but not given says nothing.
    func testAMedicineNeverGivenInsideTheRangeIsLeftOut() {
        XCTAssertTrue(report(medicines: [gel()], medicineDoses: []).medicines.isEmpty)
    }

    func testADoseFromBeforeTheRangeDoesNotTravelWithIt() throws {
        let content = report(
            medicines: [gel()],
            medicineDoses: [gelDose(at(born, 9)), gelDose(at(today, 9))]
        )

        let record = try XCTUnwrap(content.medicines.first)
        XCTAssertEqual(record.doses.count, 1)
    }

    /// A dose that recorded no quantity is still a dose. It is listed, and adds
    /// nothing to the total — which is why the total is only worth printing
    /// when something was measured at all.
    func testADoseWithNoAmountIsReportedWithoutInventingOne() throws {
        let content = report(
            medicines: [gel()],
            medicineDoses: [gelDose(at(today, 9), amount: nil)]
        )

        let record = try XCTUnwrap(content.medicines.first)
        XCTAssertEqual(record.doses.count, 1)
        XCTAssertEqual(record.amountTotal, 0, accuracy: 1e-9)
        XCTAssertFalse(record.hasAmounts)
    }

    func testTurningTheMedicinesOffLeavesThemOutOfTheDocument() {
        let content = report(
            medicines: [gel()],
            medicineDoses: [gelDose(at(today, 9))],
            options: ReportOptions(period: .week, includeMedicines: false)
        )

        XCTAssertTrue(content.medicines.isEmpty)
    }

    // MARK: - First foods in the doctor's record (BDR-20)

    private func meal(_ date: CalendarDay, _ hour: Int, _ foods: MealFood...) -> Feeding {
        Feeding(babyId: 1, type: .SOLID, startTime: at(date, hour), foods: foods, uid: "meal-\(at(date, hour))")
    }

    func testAFoodReachesTheRecordWithTheMealsInsideTheRangeAndItsFirstEver() throws {
        let content = report(
            feedings: [
                meal(today.adding(days: -30), 12, MealFood(name: "Carotte", ref: "carrot")),
                meal(today.adding(days: -2), 12, MealFood(name: "Carrot", portion: .SERVING, reception: .LIKED, ref: "carrot")),
                meal(today, 12, MealFood(name: "Carotte", ref: "carrot"), MealFood(name: "Ratatouille")),
            ],
            foodName: { $0 == "carrot" ? "Carrot" : nil }
        )

        XCTAssertEqual(content.foods.count, 2)
        let carrot = content.foods[0]
        XCTAssertEqual(carrot.name, "Carrot")
        XCTAssertEqual(carrot.ref, "carrot")
        XCTAssertEqual(carrot.offers.count, 2)
        XCTAssertEqual(carrot.tastes, 1)
        XCTAssertEqual(carrot.servings, 1)
        XCTAssertEqual(carrot.timesReceived(.LIKED), 1)
        XCTAssertEqual(carrot.firstOffered, at(today.adding(days: -30), 12), "the first ever, not the first in the range")
        XCTAssertLessThan(try XCTUnwrap(carrot.offers.first).time, try XCTUnwrap(carrot.offers.last).time, "oldest first")

        XCTAssertEqual(content.foods[1].name, "Ratatouille")
        XCTAssertNil(content.foods[1].ref)
    }

    /// In the order they were first offered — never by how well each is going.
    func testFoodsAreListedInTheOrderTheyWereIntroduced() {
        let content = report(feedings: [
            meal(today, 12, MealFood(name: "Pea"), MealFood(name: "Apple")),
            meal(today.adding(days: -3), 12, MealFood(name: "Leek", reception: .REFUSED)),
        ])

        XCTAssertEqual(content.foods.map(\.name), ["Leek", "Apple", "Pea"])
    }

    func testAFoodOfferedOnlyBeforeTheRangeIsLeftOut() {
        XCTAssertTrue(report(feedings: [meal(born, 12, MealFood(name: "Leek"))]).foods.isEmpty)
    }

    func testTurningTheFoodsOffLeavesThemOutOfTheDocument() {
        let content = report(
            feedings: [meal(today, 12, MealFood(name: "Leek"))],
            options: ReportOptions(period: .week, includeFoods: false)
        )

        XCTAssertTrue(content.foods.isEmpty)
    }

    /// One row per food per meal inside the range: the list's id to group by,
    /// keys rather than translated words, and a reception nobody noted left blank
    /// rather than made into an answer. From `ReportWorkbookTest.kt`.
    func testTheFoodsSheetHasARowPerFoodPerMeal() throws {
        let content = report(
            feedings: [
                meal(today.adding(days: -20), 12, MealFood(name: "Carotte", ref: "carrot")),
                meal(
                    today, 12,
                    MealFood(name: "Carotte", portion: .SERVING, reception: .LIKED, ref: "carrot"),
                    MealFood(name: "Ratatouille")
                ),
            ],
            foodName: { $0 == "carrot" ? "Carrot" : nil }
        )
        let sheet = try XCTUnwrap(ReportWorkbook.build(content).first { $0.name == "Foods" })

        XCTAssertEqual(sheet.headers, ["date", "time", "food", "list_id", "portion", "reception", "first_offered"])
        XCTAssertEqual(sheet.rows.count, 2)
        let carrot = sheet.rows[0]
        XCTAssertEqual(carrot[2], .text("Carrot"), "named in the export's language, whatever it was saved under")
        XCTAssertEqual(carrot[3], .text("carrot"))
        XCTAssertEqual(carrot[4], .text("serving"))
        XCTAssertEqual(carrot[5], .text("liked"))
        XCTAssertEqual(carrot[6], .day(today.adding(days: -20)))

        let ratatouille = sheet.rows[1]
        XCTAssertEqual(ratatouille[2], .text("Ratatouille"))
        XCTAssertEqual(ratatouille[3], .blank)
        XCTAssertEqual(ratatouille[4], .text("taste"))
        XCTAssertEqual(ratatouille[5], .blank)
        XCTAssertEqual(ratatouille[6], .day(today))
    }

    func testTheFoodsSheetGoesWhenFoodsDo() {
        let content = report(
            feedings: [meal(today, 12, MealFood(name: "Leek"))],
            options: ReportOptions(period: .week, includeFoods: false)
        )

        XCTAssertFalse(ReportWorkbook.build(content).map(\.name).contains("Foods"))
    }

    // MARK: - Allergies in the doctor's record (BDR-21)

    /// About who the baby is, not about the period: one noted in March is in
    /// June's report. From `ReportDataTest.kt`.
    func testAllergiesReachTheRecordWhateverThePeriodNamedAndInAlphabeticalOrder() throws {
        let content = report(
            foodName: { $0 == "kiwi" ? "Kiwi fruit" : nil },
            allergies: [
                Allergy(babyId: 1, name: "Œuf", noticedAt: at(born, 9)),
                Allergy(babyId: 1, name: "Kiwi", ref: "kiwi", confirmed: true, notes: " lips "),
                Allergy(babyId: 1, name: "Gone", deletedAt: 1),
            ]
        )

        XCTAssertEqual(content.allergies.map(\.name), ["Kiwi fruit", "Œuf"])
        let kiwi = try XCTUnwrap(content.allergies.first)
        XCTAssertTrue(kiwi.confirmed)
        XCTAssertEqual(kiwi.notes, "lips")
        XCTAssertEqual(content.allergies.last?.noticedAt, at(born, 9))
        XCTAssertEqual(content.allergies.last?.confirmed, false)
    }

    func testTurningTheAllergiesOffLeavesThemOutOfTheDocument() {
        let content = report(
            options: ReportOptions(period: .week, includeAllergies: false),
            allergies: [Allergy(babyId: 1, name: "Kiwi")]
        )

        XCTAssertTrue(content.allergies.isEmpty)
    }

    /// The list shared on its own is assembled the same way, so the two documents
    /// agree.
    func testTheAllergyListSaysWhoseItIsAndCarriesTheSameRecords() {
        let allergies = [Allergy(babyId: 1, name: "Kiwi", ref: "kiwi")]
        let list = allergyList(baby: baby, allergies: allergies, now: now) { _ in "Kiwi fruit" }

        XCTAssertEqual(list.babyName, "Louise")
        XCTAssertEqual(list.birthDate, baby.birthDate)
        XCTAssertEqual(list.generatedAt, now)
        XCTAssertEqual(list.allergies, report(foodName: { _ in "Kiwi fruit" }, allergies: allergies).allergies)
    }

    /// One row per allergy; notes only when notes are switched on, as on every
    /// sheet. From `ReportWorkbookTest.kt`.
    func testTheAllergiesSheetCarriesWhatTheFamilyNoted() throws {
        let allergies = [
            Allergy(babyId: 1, name: "Kiwi", ref: "kiwi", noticedAt: at(born, 9), confirmed: true, notes: "lips"),
        ]
        let sheet = try XCTUnwrap(ReportWorkbook.build(report(allergies: allergies)).first { $0.name == "Allergies" })
        XCTAssertEqual(sheet.headers, ["allergy", "list_id", "confirmed", "noticed_on"])
        XCTAssertEqual(sheet.rows.first, [.text("Kiwi"), .text("kiwi"), .flag(true), .day(born)])

        let withNotes = try XCTUnwrap(
            ReportWorkbook.build(
                report(options: ReportOptions(period: .week, includeNotes: true), allergies: allergies)
            ).first { $0.name == "Allergies" }
        )
        XCTAssertEqual(withNotes.rows.first?.last, .text("lips"))
    }
}
