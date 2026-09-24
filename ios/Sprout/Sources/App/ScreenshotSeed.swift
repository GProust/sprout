#if DEBUG
import Foundation
import SproutData

/// A fixed database for the screenshot run, and nothing else.
///
/// **`#if DEBUG` is load-bearing, not tidiness.** This reads a launch argument
/// and, if it sees one, swaps the parent's database for a fake one full of
/// invented babies. In a Release build that would be a door into the app's data
/// opened by anyone who can pass an argument to it — precisely the kind of way
/// in ADR-0014 exists to keep shut. It must never ship, and the compiler is what
/// guarantees that rather than a code review.
///
/// Android has the same need and no such risk: its screenshots are driven from
/// `androidTest`, which is a separate APK that never reaches Play.
enum ScreenshotSeed {

    static let launchArgument = "-sprout-screenshots"

    /// The same fixed app, with **nothing seeded**, so the first-run flow can be
    /// captured too. Onboarding is the one screen every parent sees and no
    /// seeded run ever reaches, because seeding is exactly what makes it not
    /// appear.
    static let emptyLaunchArgument = "-sprout-screenshots-empty"

    /// Fixed, so the captures are reproducible.
    ///
    /// Every screenshot in every language has to show the same numbers, or the
    /// set does not read as one app. That means the clock too: "5 min ago" has
    /// to be five minutes in the Polish capture as well.
    ///
    /// 2026-06-15 14:30 UTC — an afternoon, so the dashboard's greeting is the
    /// one most screenshots should show.
    static let now: Int64 = 1_781_534_400_000

    static var isRequested: Bool {
        let arguments = ProcessInfo.processInfo.arguments
        return arguments.contains(launchArgument) || arguments.contains(emptyLaunchArgument)
    }

    private static var isEmptyRequested: Bool {
        ProcessInfo.processInfo.arguments.contains(emptyLaunchArgument)
    }

    /// An in-memory app with a plausible couple of days already logged — or with
    /// nothing at all, for the first-run capture.
    static func environment() throws -> AppEnvironment {
        Clock.now = { now }
        let environment = try AppEnvironment.inMemory()
        if !isEmptyRequested { try seed(environment.repository) }
        return environment
    }

    private static func seed(_ repository: SproutRepository) throws {
        try repository.saveParentProfile(
            ParentProfile(
                name: "Alex",
                gaveBirth: true,
                breastfeeding: true,
                deliveryType: .VAGINAL
            )
        )
        // Three weeks old: old enough for the age to read in weeks and days,
        // young enough that the logs below are a plausible day.
        _ = try repository.addBaby(name: "Robin", birthDate: now - 22 * day)

        try seedFeeds(repository)
        try seedSleeps(repository)
        try seedDiapers(repository)
        try seedMedicines(repository)
    }

    /// As-needed medicine (BDR-15), seeded into one of each state the traffic
    /// light has: too soon, allowed-but-sooner-than-ideal, and ready. One card
    /// would show the screen; three show what it is *for*.
    ///
    /// The uids are written out rather than generated, because each dose has to
    /// name the medicine it was of, and the repository keeps a uid that is
    /// already set. The same three, with the same gaps, as Android's
    /// `ScreenshotTest` — the two sets are one product.
    private static func seedMedicines(_ repository: SproutRepository) throws {
        let ibuprofen = "screenshot-medicine-ibuprofen"
        _ = try repository.addMedicine(
            Medicine(
                name: "Ibuprofen",
                dose: "1.25 ml",
                minIntervalMinutes: 6 * 60,
                comfortIntervalMinutes: 8 * 60,
                maxPerDay: 3,
                remindWhenDue: true,
                uid: ibuprofen
            )
        )
        // Two hours ago: four of the six still to wait.
        try repository.addMedicineDose(
            MedicineDose(medicineUid: ibuprofen, time: now - 2 * hour)
        )

        let paracetamol = "screenshot-medicine-paracetamol"
        _ = try repository.addMedicine(
            Medicine(
                name: "Paracetamol",
                dose: "2.5 ml",
                minIntervalMinutes: 6 * 60,
                comfortIntervalMinutes: 8 * 60,
                maxPerDay: 4,
                remindWhenDue: true,
                uid: paracetamol
            )
        )
        // Seven hours ago: past the minimum, an hour short of the usual gap —
        // and a second dose inside the day, so the count reads "2 of 4".
        try repository.addMedicineDose(
            MedicineDose(medicineUid: paracetamol, time: now - 7 * hour)
        )
        try repository.addMedicineDose(
            MedicineDose(medicineUid: paracetamol, time: now - 16 * hour)
        )

        let teething = "screenshot-medicine-teething"
        _ = try repository.addMedicine(
            Medicine(
                name: "Teething gel",
                dose: "0.25 cm",
                // The other shape a leaflet comes in (BDR-18): no gap at all,
                // held by how many and how much in a day. It reads "No set gap"
                // where the others read "Every 6 h to 8 h", and carries the
                // running quantity beside the count.
                minIntervalMinutes: 0,
                maxPerDay: 6,
                doseAmount: 0.25,
                doseUnit: "cm",
                maxAmountPerDay: 1.5,
                uid: teething
            )
        )
        // Three applications inside the day: "3 of 6 in the last 24 h" and
        // three quarters of a centimetre of the allowed centimetre and a half.
        for ago in [10 * hour, 6 * hour, 2 * hour] {
            try repository.addMedicineDose(
                MedicineDose(medicineUid: teething, time: now - ago, amount: 0.25)
            )
        }
    }

    private static func seedFeeds(_ repository: SproutRepository) throws {
        // A session that switched sides, so the per-side breakdown has
        // something to break down; then a bottle and a plain one, which is what
        // most rows look like.
        try repository.addFeeding(
            Feeding(
                type: .BREAST,
                side: .BOTH,
                startTime: now - 2 * hour,
                endTime: now - 2 * hour + 22 * minute,
                leftDurationMs: 14 * minute,
                rightDurationMs: 8 * minute,
                segments: [
                    NursingSegment(side: .LEFT, startTime: now - 2 * hour, endTime: now - 2 * hour + 14 * minute),
                    NursingSegment(
                        side: .RIGHT,
                        startTime: now - 2 * hour + 14 * minute,
                        endTime: now - 2 * hour + 22 * minute
                    ),
                ]
            )
        )
        // One feed saved as two: the left side stopped and saved, five minutes
        // of winding, the right side started afresh. The history offers to join
        // them back into the one feed they were (BDR-19) — the same two halves,
        // five minutes apart, as Android's `ScreenshotTest`.
        let half = now - 3 * hour - 40 * minute
        try repository.addFeeding(
            Feeding(
                type: .BREAST,
                side: .LEFT,
                startTime: half,
                endTime: half + 9 * minute,
                leftDurationMs: 9 * minute,
                segments: [NursingSegment(side: .LEFT, startTime: half, endTime: half + 9 * minute)]
            )
        )
        try repository.addFeeding(
            Feeding(
                type: .BREAST,
                side: .RIGHT,
                startTime: half + 14 * minute,
                endTime: half + 20 * minute,
                rightDurationMs: 6 * minute,
                segments: [
                    NursingSegment(side: .RIGHT, startTime: half + 14 * minute, endTime: half + 20 * minute),
                ]
            )
        )
        try repository.addFeeding(
            Feeding(type: .BOTTLE, amountMl: 90, startTime: now - 5 * hour, notes: "Expressed")
        )
        try repository.addFeeding(
            Feeding(
                type: .BREAST,
                side: .LEFT,
                startTime: now - 8 * hour,
                endTime: now - 8 * hour + 18 * minute
            )
        )
    }

    private static func seedSleeps(_ repository: SproutRepository) throws {
        // A night that crossed midnight, a morning nap with a place and a
        // position, an afternoon nap that says neither, and one still running —
        // between them they show every shape a sleep card takes.
        try repository.addSleep(
            Sleep(
                startTime: now - 20 * hour,
                endTime: now - 14 * hour,
                position: .BACK,
                place: .BEDSIDE_COT
            )
        )
        try repository.addSleep(
            Sleep(
                startTime: now - 6 * hour,
                endTime: now - 5 * hour + 20 * minute,
                position: .SIDE,
                place: .OTHER,
                placeNote: "In the pram"
            )
        )
        try repository.addSleep(
            Sleep(startTime: now - 3 * hour, endTime: now - 2 * hour, notes: "Went down easily")
        )
        try repository.addSleep(Sleep(startTime: now - 25 * minute))
    }

    private static func seedDiapers(_ repository: SproutRepository) throws {
        try repository.addDiaper(Diaper(time: now - 40 * minute, wet: true))
        try repository.addDiaper(
            Diaper(time: now - 4 * hour, wet: true, dirty: true, stoolColor: .YELLOW)
        )
        try repository.addDiaper(
            Diaper(time: now - 9 * hour, dirty: true, stoolColor: .GREEN, notes: "A bit runny")
        )
        try repository.addDiaper(Diaper(time: now - 26 * hour, wet: true))
    }

    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour
}
#endif
