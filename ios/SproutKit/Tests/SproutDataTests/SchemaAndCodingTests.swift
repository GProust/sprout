import GRDB
import XCTest
@testable import SproutData

/// The iOS schema against Room's exported one.
///
/// This is the check that stops the two databases drifting apart in a way only a
/// sync would reveal. It reads `android/app/schemas/…/<version>.json` directly —
/// the files Android's own CI already refuses to let go stale.
///
/// **The version it reads is this build's, not a literal.** Reading a fixed
/// `16.json` was right while 16 was the only schema there was, and would have
/// gone quiet the moment a table was added after it: the new tables appear in no
/// file the test opens, so nothing compares them to anything. Reading
/// ``SproutDatabase/schemaVersion`` instead means adding a migration here
/// without committing Room's export for it fails, which is the same rule the
/// spec has about vectors — the schema lands with the change.
final class SchemaTests: XCTestCase {

    private static let schemaDirectory: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        url.appendPathComponent("android/app/schemas/com.gproust.sprout.data.local.SproutDatabase")
        return url
    }()

    private static func schema(version: Int) -> [String: Any]? {
        let url = schemaDirectory.appendingPathComponent("\(version).json")
        guard let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return root["database"] as? [String: Any]
    }

    /// Room's export for the schema this build actually speaks.
    private static let androidSchema: [String: Any]? = schema(version: SproutDatabase.schemaVersion)

    func testTheAndroidSchemaIsReadable() throws {
        XCTAssertNotNil(
            Self.androidSchema,
            """
            android/app/schemas/…/\(SproutDatabase.schemaVersion).json not found. \
            Room writes it during the Android build; commit it with the migration \
            that produced it.
            """
        )
    }

    func testVersionMatchesTheOneWeSpeak() throws {
        let database = try XCTUnwrap(Self.androidSchema)
        XCTAssertEqual(
            (database["version"] as? NSNumber)?.intValue,
            SproutDatabase.schemaVersion,
            "the exported schema is numbered differently from the one this build speaks"
        )
    }

    /// The schema iOS starts from is still the one `schemaV16` reproduces.
    ///
    /// Separate from the check above, because the two say different things: that
    /// one is about where this build has got to, this one is about where it
    /// began. Editing `schemaV16` to follow a change on Android — rather than
    /// adding a migration — is the mistake it exists to catch.
    func testTheStartingSchemaIsStillThere() throws {
        XCTAssertNotNil(
            Self.schema(version: SproutDatabase.initialAndroidSchemaVersion),
            "android/app/schemas/…/16.json not found"
        )
    }

    /// Table for table, column for column, including types and nullability.
    ///
    /// A column this side declares `TEXT` where Room declares `INTEGER` reads
    /// back as something the other app never wrote.
    func testEveryTableAndColumnMatchesRoom() throws {
        let database = try XCTUnwrap(Self.androidSchema)
        let entities = try XCTUnwrap(database["entities"] as? [[String: Any]])
        let queue = try SproutDatabase.inMemory()

        try queue.read { db in
            for entity in entities {
                let table = try XCTUnwrap(entity["tableName"] as? String)
                XCTAssertTrue(try db.tableExists(table), "missing table \(table)")

                let columns = try db.columns(in: table)
                let ours = Dictionary(uniqueKeysWithValues: columns.map { ($0.name, $0) })

                let fields = try XCTUnwrap((entity["fields"] as? [[String: Any]]))
                for field in fields {
                    let name = try XCTUnwrap(field["columnName"] as? String)
                    let affinity = try XCTUnwrap(field["affinity"] as? String)
                    let notNull = (field["notNull"] as? Bool) ?? false

                    let column = try XCTUnwrap(ours[name], "\(table).\(name) is missing")
                    XCTAssertEqual(
                        column.type.uppercased(), affinity.uppercased(),
                        "\(table).\(name) type"
                    )
                    XCTAssertEqual(column.isNotNull, notNull, "\(table).\(name) nullability")
                }

                XCTAssertEqual(
                    Set(ours.keys),
                    Set(fields.compactMap { $0["columnName"] as? String }),
                    "\(table) has columns Room does not, or the other way round"
                )
            }
        }
    }

    func testTheIndicesRoomDeclaresAreThere() throws {
        let database = try XCTUnwrap(Self.androidSchema)
        let entities = try XCTUnwrap(database["entities"] as? [[String: Any]])
        let queue = try SproutDatabase.inMemory()

        try queue.read { db in
            for entity in entities {
                let table = try XCTUnwrap(entity["tableName"] as? String)
                let ours = Set(try db.indexes(on: table).map(\.name))
                for index in (entity["indices"] as? [[String: Any]]) ?? [] {
                    let name = try XCTUnwrap(index["name"] as? String)
                    XCTAssertTrue(ours.contains(name), "missing index \(name) on \(table)")
                }
            }
        }
    }

    func testMigratingTwiceIsANoOp() throws {
        let queue = try DatabaseQueue()
        try SproutDatabase.migrator.migrate(queue)
        XCTAssertNoThrow(try SproutDatabase.migrator.migrate(queue))
    }
}

/// The two columns that hold a list inside a TEXT value, in Android's encoding.
final class CodingTests: XCTestCase {

    func testReminderTimesRoundTrip() {
        XCTAssertEqual(IntListCoding.encode([540, 1080]), "540,1080")
        XCTAssertEqual(IntListCoding.decode("540,1080"), [540, 1080])
        XCTAssertEqual(IntListCoding.encode([]), "")
        XCTAssertEqual(IntListCoding.decode(""), [])
        XCTAssertEqual(IntListCoding.decode(nil), [])
    }

    /// Kotlin drops blank entries rather than reading them as zero. A trailing
    /// comma is a formatting artefact, not a reminder at midnight.
    func testBlankReminderEntriesAreDropped() {
        XCTAssertEqual(IntListCoding.decode("540,,1080,"), [540, 1080])
    }

    func testNursingSegmentsRoundTrip() {
        let segments = [
            NursingSegment(side: .LEFT, startTime: 1000, endTime: 2000),
            NursingSegment(side: .RIGHT, startTime: 2000, endTime: 3500),
        ]

        let encoded = NursingSegmentCoding.encode(segments)

        XCTAssertEqual(encoded, "LEFT,1000,2000;RIGHT,2000,3500")
        XCTAssertEqual(NursingSegmentCoding.decode(encoded), segments)
    }

    func testAnEmptySegmentColumnIsAnEmptyList() {
        XCTAssertEqual(NursingSegmentCoding.decode(""), [])
        XCTAssertEqual(NursingSegmentCoding.decode(nil), [])
        XCTAssertEqual(NursingSegmentCoding.encode([]), "")
    }

    /// This column can arrive from a merge with whatever the other phone wrote.
    /// One bad triple loses one segment, not the whole session.
    func testAMalformedTripleIsSkippedNotFatal() {
        let decoded = NursingSegmentCoding.decode("LEFT,1000,2000;nonsense;RIGHT,2000,3000")

        XCTAssertEqual(decoded.count, 2)
        XCTAssertEqual(decoded.map(\.side), [.LEFT, .RIGHT])
    }

    func testAnUnknownSideIsSkipped() {
        XCTAssertEqual(NursingSegmentCoding.decode("MIDDLE,1,2"), [])
    }

    func testSegmentsSurviveTheDatabase() throws {
        let queue = try SproutDatabase.inMemory()
        let repository = SproutRepository(database: queue, now: { 1_000 })
        try repository.saveParentProfile(ParentProfile(name: "Alex", gaveBirth: true, breastfeeding: true))
        _ = try repository.addBaby(name: "Robin", birthDate: 0)

        try repository.addFeeding(
            Feeding(
                type: .BREAST,
                side: .BOTH,
                startTime: 1_000,
                segments: [
                    NursingSegment(side: .LEFT, startTime: 1_000, endTime: 2_000),
                    NursingSegment(side: .RIGHT, startTime: 2_000, endTime: 2_500),
                ]
            )
        )

        let row = try queue.read { db in try Feeding.fetchOne(db) }
        let stored = try XCTUnwrap(row)
        XCTAssertEqual(stored.nursingSegments.count, 2)
        XCTAssertEqual(stored.nursingSegments.first?.durationMs, 1_000)
    }
}
