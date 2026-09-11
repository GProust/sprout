import XCTest
@testable import SproutKit

final class HouseholdBeaconTests: XCTestCase {

    private func secret() throws -> SyncSecret {
        let hex = try XCTUnwrap(Vectors.load("secret.json")["secretHex"] as? String)
        return try SyncSecret(bytes: Data(hex: hex))
    }

    /// The whole vector table at once. If the window arithmetic, the ASCII
    /// decimal message, or the truncation is off by anything, this says which
    /// timestamp exposed it.
    func testMatchesTheVectors() throws {
        let secret = try secret()

        for testCase in try Vectors.cases(in: "beacon.json") {
            let at = try XCTUnwrap((testCase["at"] as? NSNumber)?.int64Value)
            let window = try XCTUnwrap((testCase["window"] as? NSNumber)?.int64Value)
            let beaconHex = try XCTUnwrap(testCase["beaconHex"] as? String)
            let advertUuid = try XCTUnwrap(testCase["advertUuid"] as? String)

            XCTAssertEqual(HouseholdBeacon.window(at: at), window, "window for at=\(at)")
            XCTAssertEqual(
                HouseholdBeacon.value(secret: secret, at: at).hex, beaconHex,
                "beacon for at=\(at)"
            )
            XCTAssertEqual(
                HouseholdBeacon.advertUuid(secret: secret, at: at).uuidString.lowercased(),
                advertUuid.lowercased(),
                "advertised uuid for at=\(at)"
            )
        }
    }

    /// The two derivations share a secret and a window and must never collide;
    /// the label is what keeps them apart.
    func testTheBeaconAndTheUuidAreNotTheSameDerivation() throws {
        let secret = try secret()
        let at: Int64 = 1_757_400_000_000

        let beacon = HouseholdBeacon.value(secret: secret, at: at)
        let uuid = withUnsafeBytes(of: HouseholdBeacon.advertUuid(secret: secret, at: at).uuid) {
            Data($0.prefix(HouseholdBeacon.valueBytes))
        }

        XCTAssertNotEqual(beacon, uuid)
    }

    func testAcceptsThePreviousWindow() throws {
        let secret = try secret()
        let window: Int64 = 1_000
        let inThePrevious = window * HouseholdBeacon.windowMs - 1
        let inThisOne = window * HouseholdBeacon.windowMs

        let old = HouseholdBeacon.value(secret: secret, at: inThePrevious)

        // A phone whose clock has just crossed the boundary still recognises a
        // partner that has not.
        XCTAssertTrue(HouseholdBeacon.matches(observed: old, secret: secret, at: inThisOne))
    }

    func testRefusesTheWindowBeforeThat() throws {
        let secret = try secret()
        let now: Int64 = 1_000 * HouseholdBeacon.windowMs
        let tooOld = HouseholdBeacon.value(secret: secret, at: now - 2 * HouseholdBeacon.windowMs)

        XCTAssertFalse(HouseholdBeacon.matches(observed: tooOld, secret: secret, at: now))
    }

    func testRefusesAnotherHousehold() throws {
        let mine = try secret()
        let theirs = try SyncSecret(bytes: Data(repeating: 0x5A, count: SyncSecret.sizeBytes))
        let at: Int64 = 1_757_400_000_000

        let hers = HouseholdBeacon.value(secret: theirs, at: at)

        XCTAssertFalse(HouseholdBeacon.matches(observed: hers, secret: mine, at: at))
    }

    func testRefusesAValueOfTheWrongLength() throws {
        let secret = try secret()
        let at: Int64 = 1_757_400_000_000
        let correct = HouseholdBeacon.value(secret: secret, at: at)

        XCTAssertFalse(HouseholdBeacon.matches(observed: correct.dropLast(), secret: secret, at: at))
        XCTAssertFalse(HouseholdBeacon.matches(observed: correct + [0], secret: secret, at: at))
        XCTAssertFalse(HouseholdBeacon.matches(observed: Data(), secret: secret, at: at))
    }

    /// A listener scans for this window and the last, in that order.
    func testScansForBothWindows() throws {
        let secret = try secret()
        let at: Int64 = 1_757_400_000_000

        let scanning = HouseholdBeacon.advertUuidsToScanFor(secret: secret, at: at)

        XCTAssertEqual(scanning.count, 2)
        XCTAssertEqual(scanning[0], HouseholdBeacon.advertUuid(secret: secret, at: at))
        XCTAssertEqual(scanning[1], HouseholdBeacon.advertUuid(secret: secret, at: at - HouseholdBeacon.windowMs))
    }

    /// The value has to actually rotate, or none of the above buys anything.
    func testTheValueChangesEveryWindow() throws {
        let secret = try secret()
        let base: Int64 = 1_757_400_000_000

        let values = Set((0..<8).map {
            HouseholdBeacon.value(secret: secret, at: base + Int64($0) * HouseholdBeacon.windowMs).hex
        })

        XCTAssertEqual(values.count, 8)
    }
}
