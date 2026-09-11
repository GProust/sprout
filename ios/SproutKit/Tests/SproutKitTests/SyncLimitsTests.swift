import XCTest
@testable import SproutKit

final class SyncLimitsTests: XCTestCase {

    func testMatchesTheVectors() throws {
        for testCase in try Vectors.cases(in: "json-depth.json") {
            let name = try XCTUnwrap(testCase["name"] as? String)
            let text = try XCTUnwrap(testCase["text"] as? String)
            let exceeds = try XCTUnwrap(testCase["exceeds"] as? Bool)

            XCTAssertEqual(SyncLimits.exceedsMaxJsonDepth(text), exceeds, name)
        }
    }

    func testTheLimitsAgreeWithTheSpec() throws {
        let vector = try Vectors.load("json-depth.json")
        XCTAssertEqual(SyncLimits.maxJsonDepth, (vector["maxJsonDepth"] as? NSNumber)?.intValue)

        let frame = try Vectors.load("session-frame.json")
        XCTAssertEqual(SyncSession.maxPayloadBytes, (frame["maxPayloadBytes"] as? NSNumber)?.intValue)
    }

    /// The scan must be linear. A quadratic one turns a 40 kB file into a hang,
    /// which is the same denial this check exists to prevent.
    func testScansALargeDocumentQuickly() {
        let text = String(repeating: #"{"a":[1,2,3],"b":"[[[["},"#, count: 50_000)

        let started = Date()
        _ = SyncLimits.exceedsMaxJsonDepth(text)

        XCTAssertLessThan(Date().timeIntervalSince(started), 2.0)
    }

    /// Depth is about nesting, not length: a very long flat document is fine.
    func testALongFlatDocumentIsNotDeep() {
        let flat = "[" + Array(repeating: "1", count: 100_000).joined(separator: ",") + "]"

        XCTAssertFalse(SyncLimits.exceedsMaxJsonDepth(flat))
    }

    func testUnbalancedClosersDoNotBuyExtraDepth() {
        // If the counter were allowed to go negative, a run of closers up front
        // would let the rest nest that much deeper unnoticed.
        let text = String(repeating: "]", count: 100)
            + String(repeating: "[", count: 33) + String(repeating: "]", count: 33)

        XCTAssertTrue(SyncLimits.exceedsMaxJsonDepth(text))
    }
}
