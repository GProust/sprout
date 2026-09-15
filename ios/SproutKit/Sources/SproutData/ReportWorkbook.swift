import Foundation

/// The report as a workbook: one sheet per kind of entry, plus the figures the
/// PDF prints, so the two files can never disagree.
/// From `ui/report/ReportWorkbook.kt`.
///
/// **The column names are English and stay English**, unlike the PDF, which is
/// translated like the rest of the app. They are the schema of a data file rather
/// than prose: a pivot table or a formula written against `bottle_ml` keeps
/// working when the phone's language changes, and a doctor handed the file next
/// year gets the same header row as the one handed it today. The sheet the
/// numbers are *read* from is the PDF.
public enum ReportWorkbook {

    /// The file's sheets, in the order they open.
    public static func build(_ report: ReportContent) -> [Xlsx.Sheet] {
        var sheets = [
            summary(report),
            daily(report),
            feeding(report),
            sleep(report),
            nappies(report),
            growth(report),
        ]
        if report.options.includeTreatments { sheets.append(treatments(report)) }
        return sheets
    }

    public static func bytes(_ report: ReportContent) throws -> Data {
        try Xlsx.write(build(report))
    }

    // MARK: - The sheets

    /// A cover sheet, so the file explains itself six months later: whose record
    /// it is, what it covers, and the same per-day figures the report's front page
    /// prints.
    private static func summary(_ report: ReportContent) -> Xlsx.Sheet {
        var rows: [[Xlsx.Cell]] = []
        func row(_ field: String, _ value: Xlsx.Cell, _ note: String = "") {
            rows.append([.text(field), value, .text(note)])
        }

        let averages = report.averages
        row("baby", .text(report.babyName))
        row("date_of_birth", .day(day(report.birthDate)))
        row("period_from", .day(report.range.from))
        row("period_to", .day(report.range.to))
        row("days_in_period", .whole(Int64(report.range.dayCount)), "days the baby lived")
        row("days_with_entries", .whole(Int64(report.daysWithEntries)))
        row("days_averaged", .whole(Int64(averages.dayCount)), "completed days only")
        row("exported_at", .day(day(report.generatedAt)))
        row("feeds_per_day", .decimal(averages.feedsPerDay))
        row("breastfeeds_per_day", .decimal(averages.breastfeedsPerDay))
        row("breast_minutes_per_day", .whole(minutes(averages.breastMillisPerDay)))
        row("bottles_per_day", .decimal(averages.bottlesPerDay))
        row("bottle_ml_per_day", .whole(Int64(averages.bottleMlPerDay)))
        row(
            "bottles_with_volume",
            .whole(Int64(report.bottlesWithVolume)),
            "of \(report.bottleCount) bottles logged"
        )
        row("solids_per_day", .decimal(averages.solidsPerDay))
        row("solid_grams_per_day", .whole(Int64(averages.solidGramsPerDay)))
        row(
            "solids_with_weight",
            .whole(Int64(report.solidsWithGrams)),
            "of \(report.solidCount) solid feeds logged"
        )
        row("sleep_minutes_per_day", .whole(minutes(averages.sleepMillisPerDay)))
        row("sleeps_per_day", .decimal(averages.sleepsPerDay))
        row("longest_sleep_minutes", .whole(minutes(report.longestSleepMillis)))
        row("nappies_per_day", .decimal(averages.diapersPerDay))
        row("wet_per_day", .decimal(averages.wetPerDay))
        row("dirty_per_day", .decimal(averages.dirtyPerDay))
        row("who_reference", .text(report.options.reference?.rawValue ?? "both"))
        row(
            "about",
            .text("Counts and totals of entries logged in Sprout. Nothing here is an assessment.")
        )

        return Xlsx.Sheet(
            name: "Summary",
            headers: ["field", "value", "note"],
            rows: rows,
            widths: [24, 22, 34],
            autoFilter: false
        )
    }

    /// One row per calendar day, **including the days nothing was logged on** —
    /// they are what the averages divide by, and a gap in a spreadsheet is a day
    /// somebody has to guess about.
    private static func daily(_ report: ReportContent) -> Xlsx.Sheet {
        Xlsx.Sheet(
            name: "Day by day",
            headers: [
                "date", "feeds", "breastfeeds", "breast_minutes", "bottles", "bottle_ml",
                "solids", "solid_grams", "sleep_minutes", "sleeps", "nappies", "wet", "dirty", "both",
            ],
            rows: report.days.map { day -> [Xlsx.Cell] in
                [
                    .day(day.date),
                    .whole(Int64(day.feedCount)),
                    .whole(Int64(day.breastCount)),
                    .whole(minutes(day.breastMillis)),
                    .whole(Int64(day.bottleCount)),
                    .whole(Int64(day.bottleMl)),
                    .whole(Int64(day.solidCount)),
                    .whole(Int64(day.solidGrams)),
                    .whole(minutes(day.sleepMillis)),
                    .whole(Int64(day.sleepCount)),
                    .whole(Int64(day.diaperCount)),
                    .whole(Int64(day.wetCount)),
                    .whole(Int64(day.dirtyCount)),
                    .whole(Int64(day.bothCount)),
                ]
            },
            widths: widthsForDaily
        )
    }

    private static func feeding(_ report: ReportContent) -> Xlsx.Sheet {
        var headers = [
            "date", "start_time", "end_time", "type", "side",
            "duration_minutes", "left_minutes", "right_minutes", "amount_ml", "amount_g",
        ]
        if report.options.includeNotes { headers.append("notes") }

        return Xlsx.Sheet(
            name: "Feeding",
            headers: headers,
            rows: report.feedings.map { feed in
                var row: [Xlsx.Cell] = [
                    .day(day(feed.startTime)),
                    .clock(clock(feed.startTime)),
                    feed.endTime.map { .clock(clock($0)) } ?? .blank,
                    .text(feed.type.rawValue.lowercased()),
                    feed.side.map { .text($0.rawValue.lowercased()) } ?? .blank,
                    .whole(minutes(feedDurationMillis(feed))),
                    feed.leftDurationMs.map { .whole(minutes($0)) } ?? .blank,
                    feed.rightDurationMs.map { .whole(minutes($0)) } ?? .blank,
                    feed.amountMl.map { .whole(Int64($0)) } ?? .blank,
                    feed.amountGrams.map { .whole(Int64($0)) } ?? .blank,
                ]
                if report.options.includeNotes { row.append(.text(feed.notes ?? "")) }
                return row
            },
            widths: [12, 11, 11, 10, 8, 16, 13, 14, 11, 10, 40]
        )
    }

    private static func sleep(_ report: ReportContent) -> Xlsx.Sheet {
        var headers = [
            "date_started", "start_time", "end_time", "duration_minutes",
            "minutes_before_midnight", "minutes_after_midnight",
            "position", "place", "place_name",
        ]
        if report.options.includeNotes { headers.append("notes") }

        return Xlsx.Sheet(
            name: "Sleep",
            headers: headers,
            rows: report.sleeps.map { sleep in
                let end = max(sleep.endTime ?? report.generatedAt, sleep.startTime)
                let total = end - sleep.startTime
                let midnight = day(sleep.startTime).adding(days: 1).startMillis()
                let before = max(0, min(end, midnight) - sleep.startTime)

                var row: [Xlsx.Cell] = [
                    .day(day(sleep.startTime)),
                    .clock(clock(sleep.startTime)),
                    sleep.endTime.map { .clock(clock($0)) } ?? .blank,
                    .whole(minutes(total)),
                    .whole(minutes(before)),
                    .whole(minutes(total - before)),
                    // Keys, like `stool_colour`: the enum's own name, in English,
                    // whatever language the phone is in. `place_name` is the
                    // parent's own words and is theirs, so it travels as typed.
                    sleep.position.map { .text($0.rawValue.lowercased()) } ?? .blank,
                    sleep.place.map { .text($0.rawValue.lowercased()) } ?? .blank,
                    nonEmpty(sleep.placeNote).map { .text($0) } ?? .blank,
                ]
                if report.options.includeNotes { row.append(.text(sleep.notes ?? "")) }
                return row
            },
            widths: [13, 11, 11, 16, 22, 21, 10, 14, 18, 40]
        )
    }

    private static func nappies(_ report: ReportContent) -> Xlsx.Sheet {
        var headers = ["date", "time", "wet", "dirty", "stool_colour"]
        if report.options.includeNotes { headers.append("notes") }

        return Xlsx.Sheet(
            name: "Nappies",
            headers: headers,
            rows: report.diapers.map { change in
                var row: [Xlsx.Cell] = [
                    .day(day(change.time)),
                    .clock(clock(change.time)),
                    .flag(change.wet),
                    .flag(change.dirty),
                    change.stoolColor.map { .text($0.rawValue.lowercased()) } ?? .blank,
                ]
                if report.options.includeNotes { row.append(.text(change.notes ?? "")) }
                return row
            },
            widths: [12, 10, 8, 8, 14, 40]
        )
    }

    /// Every measurement ever taken, not only those inside the range: a curve is
    /// read over months, and a fortnight of it is two dots and no shape.
    private static func growth(_ report: ReportContent) -> Xlsx.Sheet {
        var headers = [
            "date", "age_days", "age_months", "weight_g", "length_mm", "head_mm",
            "weight_centile_low", "weight_centile_high",
            "length_centile_low", "length_centile_high",
            "head_centile_low", "head_centile_high",
        ]
        if report.options.includeNotes { headers.append("notes") }

        func low(_ placement: WhoPlacement?) -> Xlsx.Cell {
            placement.map { .decimal($0.lowPercentile) } ?? .blank
        }
        func high(_ placement: WhoPlacement?) -> Xlsx.Cell {
            placement.map { .decimal($0.highPercentile) } ?? .blank
        }

        return Xlsx.Sheet(
            name: "Growth",
            headers: headers,
            rows: report.growth.map { reading in
                var row: [Xlsx.Cell] = [
                    .day(day(reading.entry.time)),
                    .whole(Int64(reading.ageDays)),
                    .decimal(reading.ageMonths),
                    reading.entry.weightGrams.map { .whole(Int64($0)) } ?? .blank,
                    reading.entry.heightMm.map { .whole(Int64($0)) } ?? .blank,
                    reading.entry.headMm.map { .whole(Int64($0)) } ?? .blank,
                    low(reading.placement(.weight)),
                    high(reading.placement(.weight)),
                    low(reading.placement(.length)),
                    high(reading.placement(.length)),
                    low(reading.placement(.head)),
                    high(reading.placement(.head)),
                ]
                if report.options.includeNotes { row.append(.text(reading.entry.notes ?? "")) }
                return row
            },
            widths: widthsForGrowth
        )
    }

    /// Courses running at any point in the range — **the plan, not an
    /// administration record.** Sprout does not know whether a dose was given.
    private static func treatments(_ report: ReportContent) -> Xlsx.Sheet {
        var headers = [
            "name", "dose", "interval_days", "times_of_day", "start_date", "end_date", "status",
        ]
        if report.options.includeNotes { headers.append("notes") }

        return Xlsx.Sheet(
            name: "Treatments",
            headers: headers,
            rows: report.treatments.map { course in
                let treatment = course.treatment
                let times = treatment.reminderTimes
                    .map { String(format: "%02d:%02d", $0 / 60, $0 % 60) }
                    .joined(separator: ", ")

                var row: [Xlsx.Cell] = [
                    .text(treatment.name),
                    .text(treatment.dose ?? ""),
                    .whole(Int64(treatment.intervalDays)),
                    .text(times),
                    .day(day(treatment.startDate)),
                    treatment.endDate.map { .day(day($0)) } ?? .blank,
                    .text(course.runsPast ? "running" : "finished"),
                ]
                if report.options.includeNotes { row.append(.text(treatment.notes ?? "")) }
                return row
            },
            widths: [20, 16, 14, 18, 12, 12, 11, 40]
        )
    }

    private static let widthsForDaily: [Int] = {
        var widths: [Int] = [12]
        widths.append(contentsOf: Array(repeating: 13, count: 13))
        return widths
    }()

    /// Spelled out rather than concatenated inline, for the reason
    /// `EncryptedZip.dosDateTime` carries: three array literals joined in an
    /// argument position is more than the type checker will work through.
    private static let widthsForGrowth: [Int] = {
        var widths: [Int] = [12, 10, 11]
        widths.append(contentsOf: Array(repeating: 14, count: 9))
        widths.append(40)
        return widths
    }()

    // MARK: - Small conversions

    private static func day(_ millis: Int64) -> CalendarDay { CalendarDay(millis: millis) }

    /// Seconds since midnight, which is what ``Xlsx/Cell/clock(_:)`` wants.
    private static func clock(_ millis: Int64) -> Int {
        let parts = SproutFormat.hourAndMinute(millis)
        return parts.hour * 3600 + parts.minute * 60
    }

    private static func minutes(_ millis: Int64) -> Int64 { millis / 60_000 }

    private static func nonEmpty(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return text
    }
}

/// How long a feed lasted: the breastfeed rules for a breastfeed, and plain
/// start-to-end for anything else that recorded an end. Never negative.
public func feedDurationMillis(_ feed: Feeding) -> Int64 {
    switch feed.type {
    case .BREAST: return breastfeedMillis(feed)
    default: return feed.endTime.map { max(0, $0 - feed.startTime) } ?? 0
    }
}
