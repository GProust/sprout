import SproutKit
import XCTest
@testable import SproutData

/// What a phone remembers about its household, and — more to the point — what it
/// deliberately forgets.
final class PairingStoreTests: XCTestCase {

    private var settings: InMemoryStore!
    private var deviceOnly: InMemoryStore!
    private var store: PairingStore!

    override func setUp() {
        super.setUp()
        settings = InMemoryStore()
        deviceOnly = InMemoryStore()
        store = PairingStore(settings: settings, deviceOnly: deviceOnly)
    }

    private func pair(shareStash: Bool = true, at: Int64 = 1_700_000_000_000) -> Pairing {
        let pairing = Pairing(
            householdId: "household-1",
            secret: SyncSecret.random(),
            pairedAt: at,
            shareStash: shareStash
        )
        store.save(pairing)
        return pairing
    }

    func testAFreshPhoneIsNotPaired() {
        XCTAssertNil(store.current())
        XCTAssertFalse(store.isPaired)
    }

    func testAPairingComesBackAsItWasSaved() throws {
        let saved = pair(shareStash: false, at: 1_699_999_999_000)

        let read = try XCTUnwrap(store.current())

        XCTAssertEqual(read.householdId, saved.householdId)
        XCTAssertEqual(read.secret, saved.secret)
        XCTAssertEqual(read.pairedAt, 1_699_999_999_000)
        XCTAssertFalse(read.shareStash)
        XCTAssertEqual(read.verificationCode(), saved.verificationCode())
    }

    /// The stash switch defaults on, so a pairing written before the switch
    /// existed keeps sharing rather than silently stopping.
    func testTheStashSwitchDefaultsOn() throws {
        _ = pair()
        settings.remove(PairingStore.Keys.shareStash)

        XCTAssertTrue(try XCTUnwrap(store.current()).shareStash)
    }

    func testTheStashSwitchIsRemembered() throws {
        _ = pair(shareStash: true)
        store.setShareStash(false)

        XCTAssertFalse(try XCTUnwrap(store.current()).shareStash)
    }

    // MARK: - What a restore onto a new phone looks like

    /// The whole point of the two-store split. The settings travel in a backup;
    /// the secret does not — so this is exactly the state a restored phone is in,
    /// and it must read as "not paired", not as "paired but broken" (ADR-0011).
    func testARecordRestoredWithoutItsSecretIsNotAPairing() {
        _ = pair()
        store.markFirstMergeDone()

        // What the backup carried, and what it could not.
        deviceOnly.remove(PairingStore.Keys.secret)

        XCTAssertNil(store.current())
        XCTAssertFalse(store.isPaired)
    }

    /// And the leftovers go with it. A phone that kept `first_merge_done` while
    /// claiming to be unpaired would skip ADR-0008's question the next time it
    /// was invited into a household, and adopt silently.
    func testTheLeftoversOfAnUnopenablePairingAreCleared() {
        _ = pair()
        store.markFirstMergeDone()
        deviceOnly.remove(PairingStore.Keys.secret)

        XCTAssertNil(store.current())

        XCTAssertFalse(store.firstMergeDone(), "the next invitation asks its question again")
        XCTAssertNil(settings.string(forKey: PairingStore.Keys.household))
        XCTAssertNil(settings.string(forKey: PairingStore.Keys.pairedAt))
    }

    /// A secret of the wrong length is damage, not a shorter key. It clears the
    /// pairing the same way an absent one does.
    func testASecretOfTheWrongSizeIsNotAPairing() {
        _ = pair()
        deviceOnly.set(Data([1, 2, 3]), forKey: PairingStore.Keys.secret)

        XCTAssertNil(store.current())
    }

    // MARK: - Removal

    func testRotatingTheSecretKeepsTheHouseholdAndChangesTheCode() throws {
        let before = pair()

        let after = try XCTUnwrap(store.rotateSecret(now: 1_700_000_500_000))

        XCTAssertEqual(after.householdId, before.householdId, "the household survives its members")
        XCTAssertNotEqual(after.secret, before.secret)
        XCTAssertNotEqual(
            after.verificationCode(),
            before.verificationCode(),
            "anyone still showing the old code has not been re-invited"
        )
        XCTAssertEqual(after.pairedAt, 1_700_000_500_000)
        XCTAssertEqual(try XCTUnwrap(store.current()).secret, after.secret)
    }

    func testRotatingWhenUnpairedDoesNothing() {
        XCTAssertNil(store.rotateSecret(now: 1_700_000_500_000))
        XCTAssertNil(store.current())
    }

    func testUnpairingLeavesNothingBehind() {
        _ = pair()
        store.markFirstMergeDone()

        store.unpair()

        XCTAssertNil(store.current())
        XCTAssertFalse(store.firstMergeDone())
        XCTAssertNil(deviceOnly.data(forKey: PairingStore.Keys.secret))
    }

    // MARK: - The first merge

    func testTheFirstMergeIsOnlyTheFirst() {
        _ = pair()
        XCTAssertFalse(store.firstMergeDone())

        store.markFirstMergeDone()

        XCTAssertTrue(store.firstMergeDone())
    }

    // MARK: - This device's own id

    func testTheDeviceIdIsMintedOnceAndKept() {
        let first = DeviceIdentity.id(in: deviceOnly)
        let second = DeviceIdentity.id(in: deviceOnly)

        XCTAssertFalse(first.isEmpty)
        XCTAssertEqual(first, second)
    }

    /// Lowercase, like every other uid: the other phones store it as a string and
    /// compare it as one.
    func testTheDeviceIdLooksLikeEveryOtherUid() {
        let id = DeviceIdentity.id(in: deviceOnly)

        XCTAssertEqual(id, id.lowercased())
        XCTAssertEqual(id.count, 36)
    }

    /// It lives in the store a backup leaves alone, and unpairing does not touch
    /// it: this phone is still this phone after it leaves a household.
    func testTheDeviceIdSurvivesUnpairing() {
        let id = DeviceIdentity.id(in: deviceOnly)
        _ = pair()

        store.unpair()

        XCTAssertEqual(DeviceIdentity.id(in: deviceOnly), id)
    }
}
