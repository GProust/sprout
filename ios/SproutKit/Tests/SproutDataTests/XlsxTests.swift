import Foundation
import XCTest
@testable import SproutData

/// The workbook, checked by taking the bytes apart again.
///
/// This is the check ADR-0013 asks for and the only one that matters: a
/// malformed `.xlsx` does not throw, it downloads and then Excel refuses it. So
/// the archive is unzipped here — through `Process` and the system `unzip`, not
/// through a reader of our own, because a reader is exactly what this project
/// does not want to own — and the parts are read back.
final class XlsxTests: XCTestCase {

    /// The archive's parts, by the name they have *inside* it.
    ///
    /// Two things here are less obvious than they look, and both cost a red run
    /// to find:
    ///
    /// - **The base path is resolved first.** `temporaryDirectory` is `/var/…`,
    ///   which is a symlink to `/private/var/…`, and the enumerator hands back
    ///   the resolved form — so stripping the unresolved prefix silently matches
    ///   nothing and every part comes back under its absolute path instead.
    /// - **`.rels` is not a path extension.** Foundation reads a leading dot as
    ///   the start of the name, so `_rels/.rels` has no extension at all;
    ///   filtering on one drops the part that tells a reader where the workbook
    ///   is.
    private func parts(of workbook: Data) throws -> [String: String] {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let file = directory.appendingPathComponent("book.xlsx")
        try workbook.write(to: file)

        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        unzip.arguments = ["-q", file.path, "-d", directory.path]
        try unzip.run()
        unzip.waitUntilExit()
        XCTAssertEqual(unzip.terminationStatus, 0, "unzip refused the archive")

        let base = directory.resolvingSymlinksInPath().path + "/"
        var found: [String: String] = [:]
        let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
        while let url = enumerator?.nextObject() as? URL {
            let path = url.resolvingSymlinksInPath().path
            guard path.hasPrefix(base) else { continue }
            let name = String(path.dropFirst(base.count))
            guard name != "book.xlsx" else { continue }
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            found[name] = text
        }

        // A harness that quietly finds nothing turns every assertion below into
        // "missing", which says nothing about the workbook. It was doing exactly
        // that until the two notes above were true.
        XCTAssertFalse(found.isEmpty, "the archive extracted to nothing — this is the harness, not the file")
        return found
    }

    private let sheet = Xlsx.Sheet(
        name: "Feeds",
        headers: ["date", "time", "bottle_ml", "note", "measured"],
        rows: [
            [
                .day(CalendarDay(year: 2026, month: 9, day: 8)),
                .clock(9 * 3600 + 30 * 60),
                .whole(120),
                .text("after the walk"),
                .flag(true),
            ],
            [
                .day(CalendarDay(year: 2026, month: 9, day: 8)),
                .clock(13 * 3600),
                // Nobody measured this one.
                .blank,
                .text(""),
                .flag(false),
            ],
        ],
        widths: [12, 8, 10, 30, 10]
    )

    /// The parts a reader looks for. A workbook missing any of them opens as a
    /// damaged file, which is the failure this test exists to catch.
    func testTheArchiveHasEveryPartAReaderLooksFor() throws {
        let found = try parts(of: try Xlsx.write([sheet]))

        for part in [
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
        ] {
            XCTAssertNotNil(found[part], "missing \(part)")
        }
    }

    func testCellsKeepTheirTypes() throws {
        let found = try parts(of: try Xlsx.write([sheet]))
        let worksheet = try XCTUnwrap(found["xl/worksheets/sheet1.xml"])

        // A date is a serial number with a date format, not a string.
        XCTAssertTrue(worksheet.contains("<c r=\"A2\" s=\"2\"><v>46273</v></c>"), worksheet)
        // A time is a fraction of a day: 09:30 is 0.395833.
        XCTAssertTrue(worksheet.contains("<c r=\"B2\" s=\"3\"><v>0.395833</v></c>"), worksheet)
        // A whole number carries no unit and no style.
        XCTAssertTrue(worksheet.contains("<c r=\"C2\"><v>120</v></c>"), worksheet)
        // Text is inline, so there is no shared-string table to keep in step.
        XCTAssertTrue(worksheet.contains("after the walk"))
        XCTAssertTrue(worksheet.contains("t=\"b\"><v>1</v>"))
    }

    /// The difference between "no bottle was measured" and "the bottle was 0 ml".
    func testNothingRecordedIsAnEmptyCellAndNotAZero() throws {
        let found = try parts(of: try Xlsx.write([sheet]))
        let worksheet = try XCTUnwrap(found["xl/worksheets/sheet1.xml"])

        XCTAssertTrue(worksheet.contains("<c r=\"C3\"/>"), "the unmeasured bottle must be empty")
        XCTAssertFalse(worksheet.contains("<c r=\"C3\"><v>0</v></c>"))
    }

    func testExcelsEpochIsTheOneWithTheLeapYearBug() {
        // 1900-01-01 is serial 2 in Excel, because the format believes 1900 was
        // a leap year. Matching that is what puts a date on the right day.
        XCTAssertEqual(Xlsx.serial(CalendarDay(year: 1900, month: 1, day: 1)), 2)
        XCTAssertEqual(Xlsx.serial(CalendarDay(year: 2026, month: 9, day: 8)), 46273)
    }

    func testColumnNamesRunPastZ() {
        XCTAssertEqual(Xlsx.column(0), "A")
        XCTAssertEqual(Xlsx.column(25), "Z")
        XCTAssertEqual(Xlsx.column(26), "AA")
        XCTAssertEqual(Xlsx.column(27), "AB")
        XCTAssertEqual(Xlsx.column(51), "AZ")
        XCTAssertEqual(Xlsx.column(52), "BA")
    }

    /// A note typed on an entry can contain anything at all, and one stray
    /// control character produces a file no spreadsheet will open.
    func testAnAwkwardNoteStillProducesAReadableFile() throws {
        let awkward = Xlsx.Sheet(
            name: "Notes",
            headers: ["note"],
            rows: [[.text("5 & 6 <ml> \"one\" 'two'\u{01}\u{07} end")]]
        )

        let found = try parts(of: try Xlsx.write([awkward]))
        let worksheet = try XCTUnwrap(found["xl/worksheets/sheet1.xml"])

        XCTAssertTrue(worksheet.contains("&amp;"))
        XCTAssertTrue(worksheet.contains("&lt;ml&gt;"))
        XCTAssertTrue(worksheet.contains("&quot;one&quot;"))
        XCTAssertFalse(worksheet.contains("\u{01}"), "a control character would break every reader")
        XCTAssertFalse(worksheet.contains("\u{07}"))
    }

    /// Excel refuses the whole file — not just the name — for an over-long or
    /// illegal sheet name, so the writer takes them out rather than trusting the
    /// caller.
    func testAnImpossibleSheetNameIsMadePossible() throws {
        let awkward = Xlsx.Sheet(
            name: "Feeds / sleeps: everything [2026] * a very long name indeed",
            headers: ["a"],
            rows: [[.whole(1)]]
        )

        let found = try parts(of: try Xlsx.write([awkward]))
        let workbook = try XCTUnwrap(found["xl/workbook.xml"])

        for illegal in [":", "\\", "/", "?", "*", "[", "]"] {
            XCTAssertFalse(workbook.contains("name=\"\(illegal)"), "sheet name kept \(illegal)")
        }
        XCTAssertTrue(workbook.contains("Feeds   sleeps  everything  2026"), workbook)
    }

    func testTwoSheetsAreBothDeclaredAndBothPresent() throws {
        let found = try parts(of: try Xlsx.write([
            sheet,
            Xlsx.Sheet(name: "Sleeps", headers: ["date"], rows: [[.day(CalendarDay(year: 2026, month: 9, day: 8))]]),
        ]))

        XCTAssertNotNil(found["xl/worksheets/sheet1.xml"])
        XCTAssertNotNil(found["xl/worksheets/sheet2.xml"])
        let workbook = try XCTUnwrap(found["xl/workbook.xml"])
        XCTAssertTrue(workbook.contains("name=\"Feeds\""))
        XCTAssertTrue(workbook.contains("name=\"Sleeps\""))
    }

    /// Two exports of the same range must be the same bytes, or the archive is
    /// stamping the clock into a file about when a baby was awake.
    func testTheSameWorkbookTwiceIsTheSameBytes() throws {
        XCTAssertEqual(try Xlsx.write([sheet]), try Xlsx.write([sheet]))
    }
}
