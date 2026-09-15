import XCTest
@testable import SproutData

/// The courtesy list of phones this one has heard from (ADR-0009).
final class HouseholdDevicesTests: XCTestCase {

    private var settings: InMemoryStore!
    private var devices: HouseholdDevices!

    override func setUp() {
        super.setUp()
        settings = InMemoryStore()
        devices = HouseholdDevices(settings: settings)
    }

    func testAFreshPhoneHasHeardFromNobody() {
        XCTAssertTrue(devices.all().isEmpty)
    }

    func testTheMostRecentlyHeardFromComesFirst() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)
        devices.seen(deviceId: "b", name: "Sam's phone", at: 3_000)
        devices.seen(deviceId: "c", name: "the tablet", at: 2_000)

        XCTAssertEqual(devices.all().map(\.deviceId), ["b", "c", "a"])
    }

    func testHearingFromAPhoneAgainUpdatesItRatherThanAddingASecond() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)
        devices.seen(deviceId: "a", name: "Alex's phone", at: 5_000)

        XCTAssertEqual(devices.all().count, 1)
        XCTAssertEqual(devices.all().first?.lastSeen, 5_000)
    }

    func testAPhoneThatRenamesItselfIsTheSamePhone() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)
        devices.seen(deviceId: "a", name: "Alex's new phone", at: 5_000)

        XCTAssertEqual(devices.all().map(\.name), ["Alex's new phone"])
    }

    /// A replica written before ADR-0009 carries no name. It must not blank out
    /// the one we already have.
    func testANamelessReplicaKeepsTheNameWeKnow() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)
        devices.seen(deviceId: "a", name: "", at: 5_000)

        XCTAssertEqual(devices.all().map(\.name), ["Alex's phone"])
        XCTAssertEqual(devices.all().first?.lastSeen, 5_000, "still heard from, just unnamed")
    }

    func testAReplicaWithNoDeviceIdIsNotADevice() {
        devices.seen(deviceId: "", name: "nobody", at: 1_000)
        devices.seen(deviceId: "   ", name: "nobody", at: 1_000)

        XCTAssertTrue(devices.all().isEmpty)
    }

    func testForgettingRemovesOneAndLeavesTheRest() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)
        devices.seen(deviceId: "b", name: "Sam's phone", at: 2_000)

        devices.forget(deviceId: "a")

        XCTAssertEqual(devices.all().map(\.deviceId), ["b"])
    }

    func testClearingEmptiesTheList() {
        devices.seen(deviceId: "a", name: "Alex's phone", at: 1_000)

        devices.clear()

        XCTAssertTrue(devices.all().isEmpty)
    }

    /// The list is written as JSON into a store that anything could have put
    /// anything in. It reads as "nobody yet" rather than crashing.
    func testRubbishInTheStoreReadsAsAnEmptyList() {
        settings.set(Data("not json at all".utf8), forKey: "sync_household_devices")

        XCTAssertTrue(devices.all().isEmpty)
    }

    /// An entry missing the one field that names it is dropped; the rest survive.
    func testAnEntryWithNoDeviceIdIsSkippedAndTheRestAreKept() throws {
        let json = """
        [{"name":"a ghost","lastSeen":1000},
         {"deviceId":"b","name":"Sam's phone","lastSeen":2000}]
        """
        settings.set(Data(json.utf8), forKey: "sync_household_devices")

        XCTAssertEqual(devices.all().map(\.deviceId), ["b"])
    }
}
