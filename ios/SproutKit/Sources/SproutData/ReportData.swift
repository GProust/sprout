import Foundation

/// Everything a report says, worked out once from the entries the family made.
/// From `ui/report/ReportData.kt`.
///
/// **Assembled once and rendered twice.** The PDF and the workbook are two
/// renderings of this one object, so a doctor reading the document and a doctor
/// pivoting the spreadsheet cannot be looking at different numbers. Add a figure
/// here, not in a renderer (BDR-0012).
///
/// **Nothing here interprets.** Every field is a count, a total or a reading of
/// the published WHO tables; there is no threshold, no flag and no assessment. A
/// screen that over-reads a centile is a bad moment; a printed page that does
/// gets photocopied.

/// What the parent asked for on the export screen.
public struct ReportOptions: Equatable, Sendable {
    public var period: ReportPeriod = .month
    public var customFrom: CalendarDay?
    public var customTo: CalendarDay?
    /// Which WHO reference the growth pages are read against; `nil` means both,
    /// and both is the default.
    ///
    /// **A view choice, never stored and never asked for** (BDR-0008). It lives
    /// here for the length of one export and no longer — remembering it would be
    /// keeping a baby's sex on the device by the back door.
    public var reference: WhoSex?
    public var includeDailyTable = true
    public var includeTreatments = true
    /// Off by default: free text is where a parent writes what they meant for
    /// themselves.
    public var includeNotes = false

    public init(
        period: ReportPeriod = .month,
        customFrom: CalendarDay? = nil,
        customTo: CalendarDay? = nil,
        reference: WhoSex? = nil,
        includeDailyTable: Bool = true,
        includeTreatments: Bool = true,
        includeNotes: Bool = false
    ) {
        self.period = period
        self.customFrom = customFrom
        self.customTo = customTo
        self.reference = reference
        self.includeDailyTable = includeDailyTable
        self.includeTreatments = includeTreatments
        self.includeNotes = includeNotes
    }
}

/// One measurement, and where it sits against the references.
public struct GrowthReading: Equatable, Sendable {
    public let entry: Growth
    public let ageMonths: Double
    public let ageDays: Int
    /// `nil` when that measure was not taken, or the age is past the WHO tables.
    public let weight: WhoPlacement?
    public let length: WhoPlacement?
    public let head: WhoPlacement?

    public func placement(_ measure: GrowthMeasure) -> WhoPlacement? {
        switch measure {
        case .weight: return weight
        case .length: return length
        case .head: return head
        }
    }
}

/// A treatment course as it sits across the report's own days.
///
/// `startIndex` and `endIndex` are day indices into the range, clamped to it, so
/// the timeline can be drawn without knowing any dates; `startsBefore` and
/// `runsPast` are what stop a clamped bar from claiming a start or an end the
/// course never had.
public struct TreatmentCourse: Equatable, Sendable {
    public let treatment: Treatment
    public let startIndex: Int
    /// Exclusive: a course covering only the first day is 0 to 1.
    public let endIndex: Int
    public let startsBefore: Bool
    public let runsPast: Bool
    /// Day indices on which a dose was scheduled, for courses given less often
    /// than daily. Empty for a daily course, which is drawn as a solid bar —
    /// marking every day of one would say nothing and draw the eye to nothing.
    public let doseDays: [Int]
}

/// The whole of a report, ready to be drawn or written out.
public struct ReportContent: Sendable {
    public let babyName: String
    public let birthDate: Int64
    public let generatedAt: Int64
    public let range: ReportRange
    public let options: ReportOptions
    public let days: [DayStats]
    public let averages: StatsAverages
    /// Days in the range that had at least one entry of any kind.
    public let daysWithEntries: Int
    public let bottleCount: Int
    /// How many of those bottles had a volume written down.
    public let bottlesWithVolume: Int
    public let solidCount: Int
    public let solidsWithGrams: Int
    public let longestSleepMillis: Int64
    /// How the range's sleep divides by where it happened and how the baby was
    /// lying, longest first, with the sleeps that recorded neither kept as a line
    /// of their own (BDR-0014).
    public let sleepBreakdown: SleepBreakdown
    /// Stool colours seen in the range, commonest first; only where one was
    /// recorded.
    public let stoolColours: [(colour: StoolColor, count: Int)]
    /// Measurements over the whole history — a curve is only worth reading over
    /// months.
    public let growth: [GrowthReading]
    public let treatments: [TreatmentCourse]
    /// The raw entries inside the range, oldest first, for the workbook.
    public let feedings: [Feeding]
    public let sleeps: [Sleep]
    public let diapers: [Diaper]

    public var feedTotal: Int { days.reduce(0) { $0 + $1.feedCount } }
    public var breastTotal: Int { days.reduce(0) { $0 + $1.breastCount } }
    public var breastMillisTotal: Int64 { days.reduce(0) { $0 + $1.breastMillis } }
    public var bottleMlTotal: Int { days.reduce(0) { $0 + $1.bottleMl } }
    public var solidGramsTotal: Int { days.reduce(0) { $0 + $1.solidGrams } }
    public var sleepMillisTotal: Int64 { days.reduce(0) { $0 + $1.sleepMillis } }
    public var sleepCountTotal: Int { days.reduce(0) { $0 + $1.sleepCount } }
    public var diaperTotal: Int { days.reduce(0) { $0 + $1.diaperCount } }
    public var wetTotal: Int { days.reduce(0) { $0 + $1.wetCount } }
    public var dirtyTotal: Int { days.reduce(0) { $0 + $1.dirtyCount } }

    /// Whether anything at all was logged — an empty period says so once, plainly.
    public var hasEntries: Bool { daysWithEntries > 0 }
}

/// Assembles ``ReportContent`` from one baby's logs.
///
/// `growth` and `treatments` are the baby's whole history; the feeds, sleeps and
/// changes may be too, and are filtered to the range here. **A sleep counts as
/// being in the range when any part of it is**: a night that began the evening
/// before belongs to the morning it woke into, which is the same rule the daily
/// figures are split by.
public func buildReport(
    baby: Baby,
    feedings: [Feeding],
    sleeps: [Sleep],
    diapers: [Diaper],
    growth: [Growth],
    treatments: [Treatment],
    options: ReportOptions,
    now: Int64
) -> ReportContent {
    let today = CalendarDay(millis: now)
    let birthDay = CalendarDay(millis: baby.birthDate)
    let range = reportRange(
        period: options.period,
        today: today,
        birthDay: birthDay,
        customFrom: options.customFrom,
        customTo: options.customTo
    )

    let startMillis = range.from.startMillis()
    let endMillis = range.to.adding(days: 1).startMillis()

    let feedsInRange = feedings
        .filter { $0.startTime >= startMillis && $0.startTime < endMillis }
        .sorted { $0.startTime < $1.startTime }
    let diapersInRange = diapers
        .filter { $0.time >= startMillis && $0.time < endMillis }
        .sorted { $0.time < $1.time }
    let sleepsInRange = sleeps
        .filter { $0.startTime < endMillis && ($0.endTime ?? now) >= startMillis }
        .sorted { $0.startTime < $1.startTime }

    let days = dailyStats(
        feedings: feedsInRange,
        sleeps: sleepsInRange,
        diapers: diapersInRange,
        from: range.from,
        to: range.to,
        now: now
    )

    let bottles = feedsInRange.filter { $0.type == .BOTTLE }
    let solids = feedsInRange.filter { $0.type == .SOLID }

    var counts: [StoolColor: Int] = [:]
    for colour in diapersInRange.compactMap(\.stoolColor) { counts[colour, default: 0] += 1 }
    let order = StoolColor.allCases
    let colours = counts
        .map { (colour: $0.key, count: $0.value) }
        .sorted { a, b in
            if a.count != b.count { return a.count > b.count }
            // Ties keep the scale's own order, which is the order the colour card
            // prints them in.
            let ai = order.firstIndex(of: a.colour) ?? 0
            let bi = order.firstIndex(of: b.colour) ?? 0
            return ai < bi
        }

    return ReportContent(
        babyName: baby.name,
        birthDate: baby.birthDate,
        generatedAt: now,
        range: range,
        options: options,
        days: days,
        averages: averagesOf(days, today: today),
        // `filter { }.count` and not `count(where:)`: the latter needs the Swift
        // 6 standard library, which means iOS 18, and this app targets 17.
        daysWithEntries: days.filter {
            $0.feedCount > 0 || $0.sleepCount > 0 || $0.diaperCount > 0
        }.count,
        bottleCount: bottles.count,
        bottlesWithVolume: bottles.filter { $0.amountMl != nil }.count,
        solidCount: solids.count,
        solidsWithGrams: solids.filter { $0.amountGrams != nil }.count,
        longestSleepMillis: sleepsInRange
            .map { max(0, ($0.endTime ?? now) - $0.startTime) }
            .max() ?? 0,
        sleepBreakdown: sleepBreakdown(sleepsInRange, from: range.from, to: range.to, now: now),
        stoolColours: colours,
        growth: growthReadings(birthDate: baby.birthDate, growth: growth, reference: options.reference),
        treatments: options.includeTreatments ? treatmentCourses(treatments, range: range) : [],
        feedings: feedsInRange,
        sleeps: sleepsInRange,
        diapers: diapersInRange
    )
}

/// Every measurement read against the references, oldest first.
///
/// **A measure that was not taken stays absent rather than becoming a zero**: a
/// head circumference nobody measured is a gap in the line, not a head of no
/// size.
public func growthReadings(
    birthDate: Int64,
    growth: [Growth],
    reference: WhoSex?
) -> [GrowthReading] {
    let birthDay = CalendarDay(millis: birthDate)

    return growth.sorted { $0.time < $1.time }.map { entry in
        let ageMonths = ageInMonths(birthDateMillis: birthDate, at: entry.time)
        func read(_ measure: GrowthMeasure) -> WhoPlacement? {
            guard let value = measure.reportValue(of: entry) else { return nil }
            return whoPlacement(
                measure: measure, ageMonths: ageMonths, value: value, only: reference
            )
        }
        return GrowthReading(
            entry: entry,
            ageMonths: ageMonths,
            ageDays: birthDay.days(until: CalendarDay(millis: entry.time)),
            weight: read(.weight),
            length: read(.length),
            head: read(.head)
        )
    }
}

/// The measure's value on a record, in the unit the WHO tables use.
///
/// The app has the same conversion for the chart; this one lives here so the
/// report does not depend on a screen.
extension GrowthMeasure {
    public func reportValue(of entry: Growth) -> Double? {
        switch self {
        case .weight: return entry.weightGrams.map { Double($0) / 1000 }
        case .length: return entry.heightMm.map { Double($0) / 10 }
        case .head: return entry.headMm.map { Double($0) / 10 }
        }
    }
}

/// The courses that were running at any point in `range`, with their bars clamped
/// to it.
///
/// A course is "running" between its start day and its end day **inclusive** —
/// the same inclusive end the treatments list uses to decide what is over — and
/// one with no end date runs on.
public func treatmentCourses(_ treatments: [Treatment], range: ReportRange) -> [TreatmentCourse] {
    treatments.compactMap { treatment -> TreatmentCourse? in
        let start = CalendarDay(millis: treatment.startDate)
        let end = treatment.endDate.map { CalendarDay(millis: $0) }

        if start > range.to { return nil }
        if let end, end < range.from { return nil }

        let firstShown = start < range.from ? range.from : start
        let lastShown = (end == nil || end! > range.to) ? range.to : end!
        let startIndex = range.index(of: firstShown) ?? 0
        let endIndex = (range.index(of: lastShown) ?? (range.dayCount - 1)) + 1

        let interval = max(treatment.intervalDays, 1)
        let doseDays: [Int] = interval == 1 ? [] : (startIndex..<endIndex).filter { index in
            let day = range.from.adding(days: index)
            return start.days(until: day) % interval == 0
        }

        return TreatmentCourse(
            treatment: treatment,
            startIndex: startIndex,
            endIndex: endIndex,
            startsBefore: start < range.from,
            runsPast: end == nil || end! > range.to,
            doseDays: doseDays
        )
    }
    .sorted { a, b in
        if a.startIndex != b.startIndex { return a.startIndex < b.startIndex }
        return a.treatment.name < b.treatment.name
    }
}
