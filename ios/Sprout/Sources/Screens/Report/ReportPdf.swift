import SproutData
import SwiftUI
import UIKit

/// The report, drawn onto pages. From `ui/report/ReportPdf.kt`.
///
/// Everything is drawn onto the context `UIGraphicsPDFRenderer` hands out — no
/// library, nothing that parses a file. That is ADR-0013's decision, not a
/// departure from it: the ADR reads as "written by hand", but what it chose for
/// the PDF is *the platform's own writer and its canvas* — `PdfDocument` handing
/// out a `Canvas` there, `UIGraphicsPDFRenderer` handing out a `CGContext` here.
///
/// The layout is a list of ``Block``s of known height, laid onto pages before
/// anything is drawn. That indirection earns its keep twice: every page can say
/// "3 / 5" because the total is known before the first page is started, and a
/// table that outgrows a page can be given its header again at the top of the
/// next one rather than discovering the break afterwards.
///
/// **The document interprets nothing** (BDR-0012). No threshold, no colour that
/// means "low", no sentence that reads as an assessment: every figure is a count,
/// a total, or a reading of the WHO tables the app ships. A screen that
/// over-reads a centile is a bad moment; a printed page that does gets
/// photocopied.
struct ReportPdf {
    let report: ReportContent

    /// A page, in PostScript points.
    struct Paper {
        let width: CGFloat
        let height: CGFloat

        static let a4 = Paper(width: 595, height: 842)
        static let letter = Paper(width: 612, height: 792)

        /// Letter where letter is the paper, A4 everywhere else.
        ///
        /// **Not a setting**: nobody wants to think about paper sizes, and a
        /// printed page silently scaled and clipped is worse than either choice
        /// made on the reader's behalf.
        static func forLocale(_ locale: Locale) -> Paper {
            let region = locale.region?.identifier.uppercased() ?? ""
            return letterCountries.contains(region) ? letter : a4
        }

        private static let letterCountries: Set<String> =
            ["US", "CA", "MX", "PH", "CL", "CO", "VE", "PR"]
    }

    private let paper = Paper.forLocale(.current)
    private let margin: CGFloat = 42
    private let footerHeight: CGFloat = 18

    private var contentWidth: CGFloat { paper.width - margin * 2 }
    private var contentBottom: CGFloat { paper.height - margin - footerHeight }

    // MARK: - Ink
    //
    // Fixed values, not the app's theme: this is paper. A page that came out in
    // the reader's dark mode would be a black rectangle, and the two chart hues
    // are the same warm/cool pair BDR-0008 validated for colour-vision
    // deficiency.

    private let ink = UIColor(red: 0.08, green: 0.10, blue: 0.09, alpha: 1)
    private let muted = UIColor(red: 0.42, green: 0.46, blue: 0.43, alpha: 1)
    private let grid = UIColor(red: 0.89, green: 0.91, blue: 0.89, alpha: 1)
    private let rule = UIColor(red: 0.73, green: 0.76, blue: 0.73, alpha: 1)
    private let warm = UIColor(red: 0.79, green: 0.47, blue: 0.13, alpha: 1)
    private let cool = UIColor(red: 0.23, green: 0.50, blue: 0.77, alpha: 1)
    private let bar = UIColor(red: 0.29, green: 0.42, blue: 0.35, alpha: 1)

    // MARK: - The document

    /// The whole report as PDF bytes.
    func data() -> Data {
        let laid = paginate(blocks())
        let bounds = CGRect(x: 0, y: 0, width: paper.width, height: paper.height)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds, format: metadata())

        return renderer.pdfData { context in
            for (index, page) in laid.enumerated() {
                context.beginPage()
                for placed in page { placed.block.draw(placed.y) }
                drawFooter(page: index + 1, of: laid.count)
            }
        }
    }

    /// What the file says about itself. Deliberately only the baby's name and
    /// the app's — no author, no device, nothing the parent did not already know
    /// they were sharing.
    private func metadata() -> UIGraphicsPDFRendererFormat {
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [
            kCGPDFContextTitle as String: Str.t("report_doc_title") + " — " + report.babyName,
            kCGPDFContextCreator as String: Str.t("app_name"),
        ]
        return format
    }

    /// One drawable unit of the document, whose height is known before anything
    /// is drawn.
    ///
    /// `repeatHeader` is what a table row carries so that, if the page breaks
    /// above it, the column headings are drawn again at the top of the next page
    /// rather than leaving a slab of unlabelled figures.
    struct Block {
        let height: CGFloat
        var newPage = false
        var repeatHeader: (() -> Block)?
        let draw: (CGFloat) -> Void
    }

    private struct Placed {
        let block: Block
        let y: CGFloat
    }

    private func paginate(_ blocks: [Block]) -> [[Placed]] {
        var pages: [[Placed]] = []
        var current: [Placed] = []
        var y = margin

        for block in blocks {
            let breaks = block.newPage && !current.isEmpty
            if breaks || (!current.isEmpty && y + block.height > contentBottom) {
                pages.append(current)
                current = []
                y = margin
                if let header = block.repeatHeader?() {
                    current.append(Placed(block: header, y: y))
                    y += header.height
                }
            }
            current.append(Placed(block: block, y: y))
            y += block.height
        }
        if !current.isEmpty { pages.append(current) }
        return pages
    }

    private func drawFooter(page: Int, of total: Int) {
        let y = paper.height - margin + 4
        line(from: CGPoint(x: margin, y: y - 10), to: CGPoint(x: paper.width - margin, y: y - 10),
             colour: grid)

        let caption = Str.t(
            "report_footer",
            report.babyName,
            SproutDateStyle.date(report.range.from.startMillis()),
            SproutDateStyle.date(report.range.to.startMillis())
        )
        draw(caption, at: CGPoint(x: margin, y: y - 7), size: 6.6, colour: muted)
        draw("\(page) / \(total)", at: CGPoint(x: paper.width - margin, y: y - 7),
             size: 6.6, colour: muted, align: .right)
    }
}

// MARK: - Drawing primitives
//
// Thin wrappers over `NSAttributedString` and `CGContext`, so the sections below
// read as layout rather than as Core Graphics.

extension ReportPdf {

    enum Align { case left, right, centre }

    func font(_ size: CGFloat, bold: Bool = false, serif: Bool = false) -> UIFont {
        if serif {
            let base = UIFont.systemFont(ofSize: size, weight: bold ? .bold : .regular)
            let descriptor = base.fontDescriptor.withDesign(.serif) ?? base.fontDescriptor
            return UIFont(descriptor: descriptor, size: size)
        }
        return UIFont.systemFont(ofSize: size, weight: bold ? .semibold : .regular)
    }

    /// One line of text, positioned by its *baseline-ish* top-left, right edge or
    /// centre — the same three the Kotlin's `Paint.Align` gives.
    func draw(
        _ text: String,
        at point: CGPoint,
        size: CGFloat,
        colour: UIColor,
        bold: Bool = false,
        serif: Bool = false,
        align: Align = .left
    ) {
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font(size, bold: bold, serif: serif),
            .foregroundColor: colour,
        ]
        let string = NSAttributedString(string: text, attributes: attributes)
        let measured = string.size()

        var origin = point
        switch align {
        case .left: break
        case .right: origin.x -= measured.width
        case .centre: origin.x -= measured.width / 2
        }
        string.draw(at: origin)
    }

    /// Wrapped text in a fixed width, returning the height it took.
    @discardableResult
    func draw(
        paragraph text: String,
        at point: CGPoint,
        width: CGFloat,
        size: CGFloat,
        colour: UIColor,
        italic: Bool = false
    ) -> CGFloat {
        var descriptor = UIFont.systemFont(ofSize: size).fontDescriptor
        if italic, let slanted = descriptor.withSymbolicTraits(.traitItalic) { descriptor = slanted }

        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byWordWrapping

        let string = NSAttributedString(string: text, attributes: [
            .font: UIFont(descriptor: descriptor, size: size),
            .foregroundColor: colour,
            .paragraphStyle: style,
        ])

        let bounds = string.boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        string.draw(with: CGRect(origin: point, size: CGSize(width: width, height: bounds.height)),
                    options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        return ceil(bounds.height)
    }

    /// How tall wrapped text will be, before deciding which page it goes on.
    func height(paragraph text: String, width: CGFloat, size: CGFloat, italic: Bool = false) -> CGFloat {
        var descriptor = UIFont.systemFont(ofSize: size).fontDescriptor
        if italic, let slanted = descriptor.withSymbolicTraits(.traitItalic) { descriptor = slanted }

        let string = NSAttributedString(string: text, attributes: [
            .font: UIFont(descriptor: descriptor, size: size),
        ])
        let bounds = string.boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        return ceil(bounds.height)
    }

    func line(from: CGPoint, to: CGPoint, colour: UIColor, width: CGFloat = 0.6) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setStrokeColor(colour.cgColor)
        context.setLineWidth(width)
        context.move(to: from)
        context.addLine(to: to)
        context.strokePath()
    }

    func fill(_ rect: CGRect, colour: UIColor) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.setFillColor(colour.cgColor)
        context.fill(rect)
    }
}

// MARK: - What the document is made of

extension ReportPdf {

    func blocks() -> [Block] {
        var blocks: [Block] = [header()]

        if report.hasEntries {
            blocks.append(glanceTable())
            blocks.append(contentsOf: sleepSection())
            blocks.append(contentsOf: nappySection())
        } else {
            // An empty period says so once, plainly, rather than printing a page
            // of zeroes that reads like a finding.
            blocks.append(paragraph(Str.t("report_nothing_logged"), size: 8.6, colour: muted, topGap: 14))
        }

        blocks.append(contentsOf: growthSection())
        blocks.append(contentsOf: treatmentSection())
        blocks.append(contentsOf: dailySection())
        blocks.append(paragraph(Str.t("report_about"), size: 7.4, colour: muted, topGap: 14))
        return blocks
    }

    /// Whose record this is, what it covers, and where the figures came from.
    func header() -> Block {
        let fields: [(String, String)] = [
            (Str.t("report_field_dob"), SproutDateStyle.date(report.birthDate)),
            (Str.t("report_field_age"),
             SproutFormat.age(birthDate: report.birthDate, now: report.range.to.startMillis()).text),
            (Str.t("report_field_period"), Str.t(
                "stats_window_range",
                SproutDateStyle.date(report.range.from.startMillis()),
                SproutDateStyle.date(report.range.to.startMillis())
            )),
            (Str.t("report_field_days"), String(report.range.dayCount)),
            (Str.t("report_field_generated"), SproutDateStyle.date(report.generatedAt)),
            (Str.t("report_field_days_logged"),
             Str.t("report_days_of", report.daysWithEntries, report.range.dayCount)),
        ]

        let provenance = Str.t("report_provenance")
        let provenanceHeight = height(paragraph: provenance, width: contentWidth - 12, size: 7.4, italic: true)
        let rows = Int(ceil(Double(fields.count) / 2))
        let blockHeight = 14 + 26 + 14 + CGFloat(rows) * 14 + 10 + provenanceHeight + 10

        return Block(height: blockHeight) { top in
            draw(Str.t("report_doc_title").uppercased(), at: CGPoint(x: margin, y: top),
                 size: 7.2, colour: muted, bold: true)
            draw(Str.t("app_name"), at: CGPoint(x: paper.width - margin, y: top),
                 size: 6.6, colour: muted, align: .right)

            draw(report.babyName, at: CGPoint(x: margin, y: top + 14),
                 size: 21, colour: ink, bold: true, serif: true)

            let column = contentWidth / 2
            for (index, field) in fields.enumerated() {
                let x = margin + CGFloat(index % 2) * column
                let y = top + 54 + CGFloat(index / 2) * 14
                draw(field.0, at: CGPoint(x: x, y: y), size: 7.2, colour: muted, bold: true)
                draw(field.1, at: CGPoint(x: x + 96, y: y), size: 8.6, colour: ink)
            }

            let ruleY = top + 54 + CGFloat(rows) * 14 + 4
            line(from: CGPoint(x: margin, y: ruleY), to: CGPoint(x: paper.width - margin, y: ruleY),
                 colour: rule)
            draw(paragraph: provenance, at: CGPoint(x: margin, y: ruleY + 6),
                 width: contentWidth - 12, size: 7.4, colour: muted, italic: true)
        }
    }

    /// The figures the front page carries: per day beside the total, so neither
    /// can be mistaken for the other.
    func glanceTable() -> Block {
        struct Row {
            let label: String
            let perDay: String
            let total: String
            let strong: Bool
        }

        let averages = report.averages
        var rows: [Row] = [
            Row(label: Str.t("stats_feeds_per_day"), perDay: decimal(averages.feedsPerDay),
                total: String(report.feedTotal), strong: true),
            Row(label: "   " + Str.t("stats_breast_per_day"), perDay: decimal(averages.breastfeedsPerDay),
                total: String(report.breastTotal), strong: false),
            Row(label: "   " + Str.t("stats_bottle_per_day"), perDay: decimal(averages.bottlesPerDay),
                total: String(report.bottleCount), strong: false),
            Row(label: "   " + Str.t("stats_solid_per_day"), perDay: decimal(averages.solidsPerDay),
                total: String(report.solidCount), strong: false),
            Row(label: Str.t("report_time_at_breast"), perDay: hours(averages.breastMillisPerDay),
                total: hours(report.breastMillisTotal), strong: true),
            Row(label: Str.t("report_bottle_volume"),
                perDay: Str.t("feeding_amount_ml", averages.bottleMlPerDay),
                total: Str.t("feeding_amount_ml", report.bottleMlTotal), strong: true),
        ]
        if report.solidCount > 0 {
            rows.append(
                Row(label: Str.t("report_solid_weight"),
                    perDay: Str.t("feeding_amount_g", averages.solidGramsPerDay),
                    total: Str.t("feeding_amount_g", report.solidGramsTotal), strong: true)
            )
        }
        rows.append(contentsOf: [
            Row(label: Str.t("stats_sleep_per_day"), perDay: hours(averages.sleepMillisPerDay),
                total: hours(report.sleepMillisTotal), strong: true),
            Row(label: "   " + Str.t("stats_sleeps_per_day"), perDay: decimal(averages.sleepsPerDay),
                total: String(report.sleepCountTotal), strong: false),
            Row(label: Str.t("report_col_settled"), perDay: hours(report.longestSleepMillis),
                total: "", strong: false),
            Row(label: Str.t("stats_diapers_per_day"), perDay: decimal(averages.diapersPerDay),
                total: String(report.diaperTotal), strong: true),
            Row(label: "   " + Str.t("stats_wet_per_day"), perDay: decimal(averages.wetPerDay),
                total: String(report.wetTotal), strong: false),
            Row(label: "   " + Str.t("stats_dirty_per_day"), perDay: decimal(averages.dirtyPerDay),
                total: String(report.dirtyTotal), strong: false),
        ])

        let rowHeight: CGFloat = 13
        let blockHeight = 22 + CGFloat(rows.count) * rowHeight + 12

        return Block(height: blockHeight) { top in
            draw(Str.t("report_at_a_glance"), at: CGPoint(x: margin, y: top),
                 size: 13, colour: ink, bold: true, serif: true)

            let perDayX = paper.width - margin - 110
            let totalX = paper.width - margin
            draw(Str.t("report_per_day"), at: CGPoint(x: perDayX, y: top + 16),
                 size: 7.2, colour: muted, bold: true, align: .right)
            draw(Str.t("report_over_the_period"), at: CGPoint(x: totalX, y: top + 16),
                 size: 7.2, colour: muted, bold: true, align: .right)

            for (index, row) in rows.enumerated() {
                let y = top + 26 + CGFloat(index) * rowHeight
                draw(row.label, at: CGPoint(x: margin, y: y), size: 8.6, colour: ink, bold: row.strong)
                draw(row.perDay, at: CGPoint(x: perDayX, y: y), size: 8.6, colour: ink,
                     bold: row.strong, align: .right)
                if !row.total.isEmpty {
                    draw(row.total, at: CGPoint(x: totalX, y: y), size: 8.6, colour: ink,
                         bold: row.strong, align: .right)
                }
            }
        }
    }

    /// Where the baby slept and how they were lying, when anything was recorded.
    ///
    /// **The line for the sleeps that recorded nothing stays** (BDR-0014), so the
    /// shares add up to the sleep total printed above rather than to a subset of
    /// it — a page reporting "80% in their own bed" off two naps out of ten would
    /// be worse than one that said nothing.
    func sleepSection() -> [Block] {
        let breakdown = report.sleepBreakdown
        guard breakdown.hasPlaces || breakdown.hasPositions else { return [] }

        var lines: [(String, Int, Int64)] = []
        if breakdown.hasPlaces {
            lines.append((Str.t("stats_sleep_where"), -1, -1))
            lines += breakdown.byPlace.map { ($0.value.label, $0.count, $0.millis) }
        }
        if breakdown.hasPositions {
            lines.append((Str.t("stats_sleep_position"), -1, -1))
            lines += breakdown.byPosition.map {
                ($0.value?.label ?? Str.t("stats_sleep_not_recorded"), $0.count, $0.millis)
            }
        }

        let rowHeight: CGFloat = 12
        let blockHeight = 22 + CGFloat(lines.count) * rowHeight + 10

        return [Block(height: blockHeight) { top in
            draw(Str.t("stats_sleep_title"), at: CGPoint(x: margin, y: top),
                 size: 13, colour: ink, bold: true, serif: true)

            for (index, entry) in lines.enumerated() {
                let y = top + 22 + CGFloat(index) * rowHeight
                if entry.1 < 0 {
                    draw(entry.0, at: CGPoint(x: margin, y: y), size: 7.2, colour: muted, bold: true)
                    continue
                }

                draw(entry.0, at: CGPoint(x: margin + 10, y: y), size: 8.6, colour: ink)
                let share = breakdown.totalMillis > 0
                    ? CGFloat(entry.2) / CGFloat(breakdown.totalMillis) : 0
                let trackX = margin + 190
                let trackWidth = contentWidth - 300
                fill(CGRect(x: trackX, y: y + 3, width: trackWidth, height: 4), colour: grid)
                fill(CGRect(x: trackX, y: y + 3, width: trackWidth * share, height: 4), colour: bar)

                let figure = entry.1 == 0
                    ? SproutFormat.duration(millis: entry.2).text
                    : Str.t("stats_sleep_share", entry.1, SproutFormat.duration(millis: entry.2).text)
                draw(figure, at: CGPoint(x: paper.width - margin, y: y), size: 8.6, colour: muted,
                     align: .right)
            }
        }]
    }

    /// The stool colours actually seen, counted. No colour here means anything —
    /// the scale is the one the printed cards use, and the page does not grade it.
    func nappySection() -> [Block] {
        guard !report.stoolColours.isEmpty else { return [] }

        let rowHeight: CGFloat = 12
        let blockHeight = 22 + CGFloat(report.stoolColours.count) * rowHeight + 10

        return [Block(height: blockHeight) { top in
            draw(Str.t("stats_diaper_title"), at: CGPoint(x: margin, y: top),
                 size: 13, colour: ink, bold: true, serif: true)

            for (index, entry) in report.stoolColours.enumerated() {
                let y = top + 22 + CGFloat(index) * rowHeight
                fill(CGRect(x: margin, y: y + 2, width: 8, height: 8),
                     colour: UIColor(entry.colour.swatch))
                draw(entry.colour.label, at: CGPoint(x: margin + 14, y: y), size: 8.6, colour: ink)
                draw(String(entry.count), at: CGPoint(x: margin + 160, y: y),
                     size: 8.6, colour: muted, align: .right)
            }
        }]
    }
}

// MARK: - Growth, treatments, and the day-by-day table

extension ReportPdf {

    /// Every measurement, with where it sits against the references.
    ///
    /// The centile is printed as a **span** when both references are read, which
    /// is the honest form: Sprout does not ask a baby's sex, so it does not know
    /// which curve applies (BDR-0008). Nothing here says whether a number is good.
    func growthSection() -> [Block] {
        guard !report.growth.isEmpty else { return [] }

        let columns: [(String, CGFloat, Align)] = [
            (Str.t("report_col_date"), 0, .left),
            (Str.t("report_col_age"), 92, .left),
            (Str.t("stats_measure_weight"), 160, .right),
            (Str.t("report_col_centile"), 232, .right),
            (Str.t("stats_measure_length"), 300, .right),
            (Str.t("report_col_centile"), 366, .right),
            (Str.t("stats_measure_head"), 430, .right),
            (Str.t("report_col_centile"), 496, .right),
        ]

        let rows: [[String]] = report.growth.map { reading in
            [
                SproutDateStyle.date(reading.entry.time),
                Str.t("stats_age_days", reading.ageDays),
                reading.entry.weightGrams.map { String(format: "%.2f kg", Double($0) / 1000) } ?? "—",
                centile(reading.placement(.weight)),
                reading.entry.heightMm.map { String(format: "%.1f cm", Double($0) / 10) } ?? "—",
                centile(reading.placement(.length)),
                reading.entry.headMm.map { String(format: "%.1f cm", Double($0) / 10) } ?? "—",
                centile(reading.placement(.head)),
            ]
        }

        var blocks = table(
            title: Str.t("stats_growth_title"),
            columns: columns,
            rows: rows,
            newPage: true
        )
        blocks.append(
            paragraph(
                report.options.reference == nil
                    ? Str.t("report_centile_note_both")
                    : Str.t("report_centile_note_one", referenceName()),
                size: 7.4,
                colour: muted,
                topGap: 6
            )
        )
        return blocks
    }

    /// The courses running in the range, as a timeline across the report's own
    /// days.
    ///
    /// **A bar clamped to the range says it was clamped** — an arrow at the edge
    /// rather than a squared end, so the page cannot claim a start or an end the
    /// course never had. And this is the plan, not an administration record:
    /// Sprout does not know whether a dose was given, and the page does not imply
    /// it.
    func treatmentSection() -> [Block] {
        guard report.options.includeTreatments, !report.treatments.isEmpty else { return [] }

        let rowHeight: CGFloat = 16
        let labelWidth: CGFloat = 150
        let trackX = margin + labelWidth
        let trackWidth = contentWidth - labelWidth
        let days = CGFloat(max(report.range.dayCount, 1))
        let blockHeight = 26 + CGFloat(report.treatments.count) * rowHeight + 14

        return [Block(height: blockHeight, newPage: true) { top in
            draw(Str.t("screen_treatments"), at: CGPoint(x: margin, y: top),
                 size: 13, colour: ink, bold: true, serif: true)

            draw(SproutDateStyle.date(report.range.from.startMillis()),
                 at: CGPoint(x: trackX, y: top + 16), size: 6.6, colour: muted)
            draw(SproutDateStyle.date(report.range.to.startMillis()),
                 at: CGPoint(x: paper.width - margin, y: top + 16), size: 6.6, colour: muted,
                 align: .right)

            for (index, course) in report.treatments.enumerated() {
                let y = top + 30 + CGFloat(index) * rowHeight
                let name = course.treatment.dose.map { "\(course.treatment.name) · \($0)" }
                    ?? course.treatment.name
                draw(name, at: CGPoint(x: margin, y: y), size: 8, colour: ink)

                let startX = trackX + trackWidth * CGFloat(course.startIndex) / days
                let endX = trackX + trackWidth * CGFloat(course.endIndex) / days
                fill(CGRect(x: startX, y: y + 3, width: max(endX - startX, 1.5), height: 5),
                     colour: bar)

                // The two marks that stop a clamped bar from lying about itself.
                if course.startsBefore {
                    draw("‹", at: CGPoint(x: startX - 6, y: y - 1), size: 8, colour: muted)
                }
                if course.runsPast {
                    draw("›", at: CGPoint(x: endX + 1, y: y - 1), size: 8, colour: muted)
                }

                // Only a course given less often than daily marks its days:
                // marking every day of a daily one would say nothing.
                for doseDay in course.doseDays {
                    let x = trackX + trackWidth * (CGFloat(doseDay) + 0.5) / days
                    fill(CGRect(x: x - 0.75, y: y + 1, width: 1.5, height: 9), colour: ink)
                }
            }
        }]
    }

    /// One row per calendar day, **including the days nothing was logged on**.
    func dailySection() -> [Block] {
        guard report.options.includeDailyTable, report.hasEntries else { return [] }

        let columns: [(String, CGFloat, Align)] = [
            (Str.t("report_col_date"), 0, .left),
            (Str.t("nav_feed"), 150, .right),
            (Str.t("report_col_at_breast"), 210, .right),
            (Str.t("report_col_ml"), 280, .right),
            (Str.t("nav_sleep"), 350, .right),
            (Str.t("report_col_settled"), 410, .right),
            (Str.t("nav_diaper"), 465, .right),
            (Str.t("stats_wet_per_day"), 505, .right),
            (Str.t("stats_dirty_per_day"), 511, .right),
        ]

        let rows: [[String]] = report.days.map { day in
            [
                SproutDateStyle.date(day.date.startMillis()),
                String(day.feedCount),
                hours(day.breastMillis),
                Str.t("feeding_amount_ml", day.bottleMl),
                hours(day.sleepMillis),
                String(day.sleepCount),
                String(day.diaperCount),
                String(day.wetCount),
                String(day.dirtyCount),
            ]
        }

        return table(
            title: Str.t("report_day_by_day"),
            columns: columns,
            rows: rows,
            newPage: true
        )
    }

    // MARK: - Building blocks

    /// A title, a header row, and one block per row — so a table that outgrows a
    /// page takes its headings with it.
    func table(
        title: String,
        columns: [(String, CGFloat, Align)],
        rows: [[String]],
        newPage: Bool = false
    ) -> [Block] {
        let rowHeight: CGFloat = 12

        func headerBlock(withTitle: Bool) -> Block {
            let blockHeight: CGFloat = withTitle ? 34 : 16
            return Block(height: blockHeight, newPage: withTitle && newPage) { top in
                var y = top
                if withTitle {
                    draw(title, at: CGPoint(x: margin, y: y), size: 13, colour: ink,
                         bold: true, serif: true)
                    y += 20
                }
                for column in columns {
                    let x = column.2 == .right ? margin + column.1 + 40 : margin + column.1
                    draw(column.0, at: CGPoint(x: x, y: y), size: 7.2, colour: muted,
                         bold: true, align: column.2)
                }
                line(from: CGPoint(x: margin, y: y + 11),
                     to: CGPoint(x: paper.width - margin, y: y + 11), colour: rule)
            }
        }

        var blocks: [Block] = [headerBlock(withTitle: true)]
        for (index, row) in rows.enumerated() {
            blocks.append(
                Block(
                    height: rowHeight,
                    repeatHeader: { headerBlock(withTitle: false) }
                ) { top in
                    // A faint band every other row, so a wide table can be read
                    // across without a ruler.
                    if index.isMultiple(of: 2) {
                        fill(CGRect(x: margin - 2, y: top - 1, width: contentWidth + 4, height: rowHeight),
                             colour: grid.withAlphaComponent(0.45))
                    }
                    for (columnIndex, column) in columns.enumerated() where columnIndex < row.count {
                        let x = column.2 == .right ? margin + column.1 + 40 : margin + column.1
                        draw(row[columnIndex], at: CGPoint(x: x, y: top), size: 7.6, colour: ink,
                             align: column.2)
                    }
                }
            )
        }
        return blocks
    }

    func paragraph(_ text: String, size: CGFloat, colour: UIColor, topGap: CGFloat = 0) -> Block {
        let textHeight = height(paragraph: text, width: contentWidth, size: size)
        return Block(height: topGap + textHeight + 4) { top in
            draw(paragraph: text, at: CGPoint(x: margin, y: top + topGap),
                 width: contentWidth, size: size, colour: colour)
        }
    }

    // MARK: - Wording

    func decimal(_ value: Double) -> String {
        String(format: "%.1f", locale: .current, value)
    }

    func hours(_ millis: Int64) -> String {
        SproutFormat.duration(millis: millis).text
    }

    /// "P30–P48", or a single figure when one reference was chosen; an em dash
    /// when the age is past the tables, because the honest answer there is that
    /// there is no answer.
    func centile(_ placement: WhoPlacement?) -> String {
        guard let placement else { return "—" }
        let low = min(max(Int(placement.lowPercentile.rounded()), 1), 99)
        let high = min(max(Int(placement.highPercentile.rounded()), 1), 99)
        return low == high
            ? Str.t("report_centile_one", low)
            : Str.t("report_centile_span", low, high)
    }

    func referenceName() -> String {
        switch report.options.reference {
        case .girls: return Str.t("stats_reference_girls_in_sentence")
        case .boys: return Str.t("stats_reference_boys_in_sentence")
        case nil: return Str.t("stats_reference_both")
        }
    }
}
