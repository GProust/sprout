import XCTest
@testable import SproutKit

/// Where the L2CAP channel is announced, checked against the shared vectors
/// (ADR-0016).
///
/// The radio itself is still to be written here, and this is deliberately ahead
/// of it: the day it lands it either agrees with Android or fails this test,
/// rather than shipping a scanner that connects and then finds nothing.
final class L2capPsmTests: XCTestCase {

    func testTheCharacteristicMatchesTheSpec() throws {
        let vector = try Vectors.load("l2cap.json")

        XCTAssertEqual(
            (vector["characteristicUuid"] as? String)?.lowercased(),
            L2capPsm.characteristicUuid.uuidString.lowercased()
        )
        XCTAssertEqual((vector["psmBytes"] as? NSNumber)?.intValue, L2capPsm.bytes)
    }

    func testTheEncodingMatchesTheSpec() throws {
        for testCase in try Vectors.cases(in: "l2cap.json") {
            let psm = try XCTUnwrap((testCase["psm"] as? NSNumber)?.uint16Value)
            let hex = try XCTUnwrap(testCase["hex"] as? String)

            XCTAssertEqual(L2capPsm.encode(psm).hex, hex, "psm \(psm) encoded")
            // And read back the way Android would read ours.
            XCTAssertEqual(L2capPsm.decode(Data(hex: hex)), psm, "psm \(psm) decoded")
        }
    }

    func testSomethingThatIsNotAPsmIsRefusedRatherThanDialled() {
        XCTAssertNil(L2capPsm.decode(nil))
        XCTAssertNil(L2capPsm.decode(Data([0, 0])), "zero is not a channel")
        XCTAssertNil(L2capPsm.decode(Data()))
        XCTAssertNil(L2capPsm.decode(Data([1])))
        XCTAssertNil(L2capPsm.decode(Data([0, 0, 1])))
    }
}
