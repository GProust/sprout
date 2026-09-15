import XCTest
@testable import SproutData

final class MilkStashTests: XCTestCase {

    private let now: Int64 = 1_757_400_000_000
    private let hour: Int64 = 3_600_000
    private var day: Int64 { 24 * hour }

    private func batch(_ ml: Int, _ storage: MilkStorage, ago: Int64 = 0) -> Pumping {
        Pumping(time: now - ago, amountMl: ml, storage: storage)
    }

    // MARK: - The guidance

    /// These are published figures, not tuning. A "tidier" number here is
    /// advice about a baby's milk that nobody checked.
    func testTheStorageGuidanceIsTheOneWePublished() {
        XCTAssertEqual(MilkStorage.ROOM.keepsForMillis, 4 * hour)
        XCTAssertEqual(MilkStorage.FRIDGE.keepsForMillis, 4 * day)
        XCTAssertEqual(MilkStorage.FREEZER.keepsForMillis, 180 * day)
        XCTAssertNil(MilkStorage.USED.keepsForMillis, "used milk is gone, not dated")
    }

    func testBestBefore() {
        XCTAssertEqual(bestBefore(batch(100, .FRIDGE)), now + 4 * day)
        XCTAssertNil(bestBefore(batch(100, .USED)))
    }

    /// Inclusive at the boundary: milk is still good *at* its limit.
    func testPastBestBeforeOnlyAfterTheLimit() {
        XCTAssertFalse(isPastBestBefore(batch(100, .ROOM, ago: 4 * hour), now: now))
        XCTAssertTrue(isPastBestBefore(batch(100, .ROOM, ago: 4 * hour + 1), now: now))
    }

    func testUsedMilkIsNeverPastItsBestBefore() {
        // It has no date, so the question does not apply — and answering "yes"
        // would grey out a history entry for no reason.
        XCTAssertFalse(isPastBestBefore(batch(100, .USED, ago: 400 * day), now: now))
    }

    // MARK: - The stash

    func testTotalsPerPlace() {
        let stash = milkStash(
            entries: [
                batch(120, .FRIDGE),
                batch(60, .FRIDGE),
                batch(200, .FREEZER),
                batch(30, .ROOM),
            ],
            now: now
        )

        XCTAssertEqual(stash.fridgeMl, 180)
        XCTAssertEqual(stash.freezerMl, 200)
        XCTAssertEqual(stash.roomMl, 30)
        XCTAssertEqual(stash.totalMl, 410)
        XCTAssertFalse(stash.isEmpty)
    }

    /// The stash answers "what can I give the baby today", so milk that is gone
    /// is not in it.
    func testUsedMilkIsOutOfTheStash() {
        let stash = milkStash(entries: [batch(120, .FRIDGE), batch(90, .USED)], now: now)

        XCTAssertEqual(stash.totalMl, 120)
    }

    /// And neither is milk past its guidance. A total that counted spoiled milk
    /// would be worse than showing no total at all.
    func testMilkPastItsGuidanceIsOutOfTheStash() {
        let stash = milkStash(
            entries: [
                batch(120, .FRIDGE, ago: 5 * day),      // past four days
                batch(30, .ROOM, ago: 5 * hour),        // past four hours
                batch(200, .FREEZER, ago: 30 * day),    // well inside six months
            ],
            now: now
        )

        XCTAssertEqual(stash.fridgeMl, 0)
        XCTAssertEqual(stash.roomMl, 0)
        XCTAssertEqual(stash.freezerMl, 200)
    }

    func testAnEmptyStash() {
        XCTAssertTrue(milkStash(entries: [], now: now).isEmpty)
        XCTAssertTrue(milkStash(entries: [batch(90, .USED)], now: now).isEmpty)
    }

    // MARK: - Pumped since

    /// Everything expressed, *wherever it ended up* — including milk already
    /// given, which is the difference between "what did I make today" and
    /// "what is left".
    func testPumpedSinceCountsUsedMilkToo() {
        let entries = [
            batch(120, .FRIDGE, ago: 2 * hour),
            batch(90, .USED, ago: 5 * hour),
            batch(60, .FRIDGE, ago: 30 * hour),
        ]

        XCTAssertEqual(pumpedSince(entries, from: now - 12 * hour), 210)
        XCTAssertEqual(pumpedSince(entries, from: now - 48 * hour), 270)
    }

    func testPumpedSinceIsInclusiveAtTheBoundary() {
        XCTAssertEqual(pumpedSince([batch(100, .FRIDGE, ago: hour)], from: now - hour), 100)
    }
}
