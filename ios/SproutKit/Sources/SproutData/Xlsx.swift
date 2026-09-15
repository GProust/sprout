import Foundation
import SproutKit

/// A very small `.xlsx` writer: a zip of a few XML parts, and nothing else.
/// From `data/export/Xlsx.kt`.
///
/// Sprout takes no dependency for this (ADR-0013). Every library that writes the
/// format also reads it, and a reader is a parser — which is not something to
/// carry in an app whose whole claim is that it does not open files it did not
/// write. A workbook of plain cells is a documented format and about two hundred
/// lines, so it is written here, where it is also testable without a simulator.
///
/// What is deliberately **not** here: formulas, styling beyond a bold header row
/// and three number formats, merged cells, charts. A spreadsheet a doctor or a
/// dietitian will pivot wants typed columns, not decoration.
public enum Xlsx {

    public static let mimeType =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /// Excel counts days from 1899-12-30 — the leap-year bug is part of the
    /// format, and matching it is what makes a date land on the right day.
    private static let epoch = CalendarDay(year: 1899, month: 12, day: 30)

    private static let styleDefault = 0
    private static let styleHeader = 1
    private static let styleDate = 2
    private static let styleTime = 3
    private static let styleDecimal = 4

    /// One cell. Numbers stay numbers and dates stay dates; nothing is a string
    /// with a unit glued on.
    public enum Cell {
        case text(String)
        case whole(Int64)
        case decimal(Double)
        case day(CalendarDay)
        /// A time of day, as seconds since midnight.
        case clock(Int)
        case flag(Bool)
        /// Nothing was recorded. Left genuinely empty, so it cannot be read as a
        /// zero — which is the difference between "no bottle was measured" and
        /// "the bottle was 0 ml".
        case blank
    }

    /// One sheet: a header row, then the rows.
    ///
    /// `widths` are in Excel's character units, one per column. The header row is
    /// frozen and filtered, so a long table is still usable after a hundred rows
    /// have scrolled past.
    public struct Sheet {
        public let name: String
        public let headers: [String]
        public let rows: [[Cell]]
        public let widths: [Int]
        public let autoFilter: Bool

        public init(
            name: String,
            headers: [String],
            rows: [[Cell]],
            widths: [Int] = [],
            autoFilter: Bool = true
        ) {
            self.name = name
            self.headers = headers
            self.rows = rows
            self.widths = widths
            self.autoFilter = autoFilter
        }
    }

    /// The whole workbook, as the bytes of an `.xlsx` file.
    public static func write(_ sheets: [Sheet]) throws -> Data {
        precondition(!sheets.isEmpty, "a workbook needs at least one sheet")

        var entries: [Zip.Entry] = [
            Zip.Entry(name: "[Content_Types].xml", data: Data(contentTypes(sheets.count).utf8)),
            Zip.Entry(name: "_rels/.rels", data: Data(rootRels.utf8)),
            Zip.Entry(name: "xl/workbook.xml", data: Data(workbook(sheets).utf8)),
            Zip.Entry(name: "xl/_rels/workbook.xml.rels", data: Data(workbookRels(sheets.count).utf8)),
            Zip.Entry(name: "xl/styles.xml", data: Data(styles.utf8)),
        ]
        for (index, sheet) in sheets.enumerated() {
            entries.append(
                Zip.Entry(
                    name: "xl/worksheets/sheet\(index + 1).xml",
                    data: Data(worksheet(sheet).utf8)
                )
            )
        }
        return try Zip.archive(entries)
    }

    // MARK: - The parts

    private static func contentTypes(_ sheetCount: Int) -> String {
        var xml = xmlHeader
        xml += "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        xml += "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        xml += "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        xml += "<Override PartName=\"/xl/workbook.xml\" ContentType=\"\(mimeType).main+xml\"/>"
        xml += "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-"
        xml += "officedocument.spreadsheetml.styles+xml\"/>"
        for i in 1...sheetCount {
            xml += "<Override PartName=\"/xl/worksheets/sheet\(i).xml\" ContentType=\"application/vnd."
            xml += "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        return xml + "</Types>"
    }

    private static func workbook(_ sheets: [Sheet]) -> String {
        var xml = xmlHeader
        xml += "<workbook xmlns=\"\(nsMain)\" xmlns:r=\"\(nsRel)\"><sheets>"
        for (index, sheet) in sheets.enumerated() {
            let id = index + 1
            xml += "<sheet name=\"\(escape(sheetName(sheet.name)))\" sheetId=\"\(id)\" r:id=\"rId\(id)\"/>"
        }
        return xml + "</sheets></workbook>"
    }

    private static func workbookRels(_ sheetCount: Int) -> String {
        var xml = xmlHeader
        xml += "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        for i in 1...sheetCount {
            xml += "<Relationship Id=\"rId\(i)\" Type=\"\(nsRel)/worksheet\" Target=\"worksheets/sheet\(i).xml\"/>"
        }
        xml += "<Relationship Id=\"rId\(sheetCount + 1)\" Type=\"\(nsRel)/styles\" Target=\"styles.xml\"/>"
        return xml + "</Relationships>"
    }

    private static func worksheet(_ sheet: Sheet) -> String {
        let columns = max(sheet.headers.count, sheet.rows.map(\.count).max() ?? 0)
        let lastRow = sheet.rows.count + 1

        var xml = xmlHeader
        xml += "<worksheet xmlns=\"\(nsMain)\">"
        xml += "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" "
        xml += "activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"

        if !sheet.widths.isEmpty {
            xml += "<cols>"
            for (index, width) in sheet.widths.enumerated() {
                xml += "<col min=\"\(index + 1)\" max=\"\(index + 1)\" width=\"\(width)\" customWidth=\"1\"/>"
            }
            xml += "</cols>"
        }

        xml += "<sheetData><row r=\"1\">"
        for (index, header) in sheet.headers.enumerated() {
            xml += cell(ref: column(index) + "1", value: .text(header), style: styleHeader)
        }
        xml += "</row>"

        for (rowIndex, row) in sheet.rows.enumerated() {
            let r = rowIndex + 2
            xml += "<row r=\"\(r)\">"
            for (colIndex, value) in row.enumerated() {
                xml += cell(ref: "\(column(colIndex))\(r)", value: value, style: style(of: value))
            }
            xml += "</row>"
        }
        xml += "</sheetData>"

        if sheet.autoFilter && columns > 0 && !sheet.rows.isEmpty {
            xml += "<autoFilter ref=\"A1:\(column(columns - 1))\(lastRow)\"/>"
        }
        return xml + "</worksheet>"
    }

    private static func style(of cell: Cell) -> Int {
        switch cell {
        case .day: return styleDate
        case .clock: return styleTime
        case .decimal: return styleDecimal
        default: return styleDefault
        }
    }

    private static func cell(ref: String, value: Cell, style: Int) -> String {
        let s = style == styleDefault ? "" : " s=\"\(style)\""
        switch value {
        case .blank:
            return "<c r=\"\(ref)\"\(s)/>"
        case .text(let text):
            guard !text.isEmpty else { return "<c r=\"\(ref)\"\(s)/>" }
            return "<c r=\"\(ref)\"\(s) t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escape(text) + "</t></is></c>"
        case .whole(let number):
            return "<c r=\"\(ref)\"\(s)><v>\(number)</v></c>"
        case .decimal(let number):
            return "<c r=\"\(ref)\"\(s)><v>\(trim(number))</v></c>"
        case .day(let date):
            return "<c r=\"\(ref)\"\(s)><v>\(serial(date))</v></c>"
        case .clock(let seconds):
            return "<c r=\"\(ref)\"\(s)><v>\(trim(fraction(secondsOfDay: seconds)))</v></c>"
        case .flag(let on):
            return "<c r=\"\(ref)\"\(s) t=\"b\"><v>\(on ? 1 : 0)</v></c>"
        }
    }

    /// Days since Excel's epoch.
    public static func serial(_ date: CalendarDay) -> Int { epoch.days(until: date) }

    /// A time of day as Excel sees it: the fraction of a day that has passed.
    public static func fraction(secondsOfDay seconds: Int) -> Double {
        Double(seconds) / 86_400
    }

    /// A number as the format wants it: plain decimal, six places at most, no
    /// trailing zeros and never an exponent.
    ///
    /// Not `%g`, which switches to `1.23457e+06` past six significant digits —
    /// valid in C and not a number any spreadsheet will read back. And not the
    /// reader's locale either: this is a number *inside a file format*, so a
    /// decimal comma would make it unreadable everywhere it was not written.
    private static func trim(_ value: Double) -> String {
        guard value.isFinite else { return "0" }
        let rounded = (value * 1_000_000).rounded() / 1_000_000
        if rounded == rounded.rounded(.towardZero), abs(rounded) < 9e15 {
            return String(Int64(rounded))
        }
        var text = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), rounded)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    /// A1-style column name: A, B, … Z, AA, AB, …
    public static func column(_ index: Int) -> String {
        var n = index
        var name = ""
        while n >= 0 {
            name = String(UnicodeScalar(UInt8(65 + n % 26))) + name
            n = n / 26 - 1
        }
        return name
    }

    /// Excel refuses a sheet name over 31 characters or carrying `: \ / ? * [ ]`,
    /// and refuses the whole *file* rather than the name — so they are taken out
    /// here instead of trusting every caller.
    private static func sheetName(_ name: String) -> String {
        let cleaned = String(name.map { ":\\/?*[]".contains($0) ? " " : $0 }.prefix(31))
        return cleaned.trimmingCharacters(in: .whitespaces).isEmpty ? "Sheet" : cleaned
    }

    /// XML text, with the five entities escaped and the control characters the
    /// format has no encoding for dropped.
    ///
    /// A note typed on an entry can contain anything at all, and a stray 0x01 in
    /// it would produce a file that no spreadsheet will open.
    private static func escape(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        for ch in text.unicodeScalars {
            switch ch {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&apos;"
            case "\t", "\n", "\r": out.unicodeScalars.append(ch)
            case let c where c.value < 0x20: break
            default: out.unicodeScalars.append(ch)
            }
        }
        return out
    }

    private static let xmlHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private static let nsMain = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private static let nsRel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private static let rootRels = xmlHeader
        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        + "<Relationship Id=\"rId1\" Type=\"\(nsRel)/officeDocument\" Target=\"xl/workbook.xml\"/>"
        + "</Relationships>"

    private static let styles = xmlHeader
        + "<styleSheet xmlns=\"\(nsMain)\">"
        + "<numFmts count=\"3\">"
        + "<numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/>"
        + "<numFmt numFmtId=\"165\" formatCode=\"hh:mm\"/>"
        + "<numFmt numFmtId=\"166\" formatCode=\"0.0\"/>"
        + "</numFmts>"
        + "<fonts count=\"2\">"
        + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
        + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
        + "</fonts>"
        + "<fills count=\"2\">"
        + "<fill><patternFill patternType=\"none\"/></fill>"
        + "<fill><patternFill patternType=\"gray125\"/></fill>"
        + "</fills>"
        + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
        + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
        + "<cellXfs count=\"5\">"
        + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
        + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
        + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
        + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
        + "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
        + "</cellXfs>"
        + "</styleSheet>"
}
