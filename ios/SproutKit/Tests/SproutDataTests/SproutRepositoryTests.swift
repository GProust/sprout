import GRDB
import XCTest
@testable import SproutData

/// The rules `SproutRepository` exists to enforce, checked here rather than
/// trusted: every one of them is invisible until a merge goes wrong.
final class SproutRepositoryTests: XCTestCase {

    private var queue: DatabaseQueue!
    private var clock: Int64 = 1_757_400_000_000
    private var repository: SproutRepository!

    override func setUpWithError() throws {
        queue = try SproutDatabase.inMemory()
        let start = clock
        repository = SproutRepository(database: queue, now: { start })
        try repository.saveParentProfile(
            ParentProfile(name: "Alex", gaveBirth: true, breastfeeding: true)
        )
    }

    private func makeBaby(_ name: String = "Robin") throws -> Int64 {
        try repository.addBaby(name: name, birthDate: clock - 20 * 24 * 60 * 60 * 1000)
    }

    // MARK: - Stamping

    func testAddingABabyStampsAUidAndTimestamp() throws {
        _ = try makeBaby()

        let baby = try XCTUnwrap(try repository.activeBabies().first)

        XCTAssertFalse(baby.uid.isEmpty)
        XCTAssertEqual(baby.updatedAt, clock)
    }

    /// Android generates uids with `UUID.randomUUID().toString()`, and the merge
    /// matches rows by exact string. An uppercase uid is a row no Android phone
    /// recognises as the same entry.
    func testUidsAreLowercase() throws {
        _ = try makeBaby()
        let baby = try XCTUnwrap(try repository.activeBabies().first)

        XCTAssertEqual(baby.uid, baby.uid.lowercased())
        XCTAssertEqual(baby.uid.count, 36, "a UUID string, hyphens included")
    }

    func testEveryLogIsStampedWithTheActiveBaby() throws {
        let babyId = try makeBaby()

        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        try repository.addSleep(Sleep(startTime: clock))
        try repository.addDiaper(Diaper(time: clock, wet: true))
        try repository.addGrowth(Growth(time: clock, weightGrams: 4200))

        try queue.read { db in
            XCTAssertEqual(try Feeding.fetchOne(db)?.babyId, babyId)
            XCTAssertEqual(try Sleep.fetchOne(db)?.babyId, babyId)
            XCTAssertEqual(try Diaper.fetchOne(db)?.babyId, babyId)
            XCTAssertEqual(try Growth.fetchOne(db)?.babyId, babyId)
            for uid in [
                try Feeding.fetchOne(db)?.uid, try Sleep.fetchOne(db)?.uid,
                try Diaper.fetchOne(db)?.uid, try Growth.fetchOne(db)?.uid,
            ] {
                XCTAssertFalse(uid?.isEmpty ?? true)
            }
        }
    }

    /// With no baby there is nothing to stamp, and a row stamped with 0 would be
    /// a row that belongs to nobody and syncs to nobody.
    func testALogWithoutAnActiveBabyIsNotWritten() throws {
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))

        try queue.read { db in XCTAssertEqual(try Feeding.fetchCount(db), 0) }
    }

    // MARK: - Soft deletes

    func testDeletingFlagsTheRowRatherThanRemovingIt() throws {
        _ = try makeBaby()
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        let stored = try queue.read { db in try Feeding.fetchOne(db) }
        let feeding = try XCTUnwrap(stored)

        try repository.deleteFeeding(feeding)

        try queue.read { db in
            let row = try XCTUnwrap(try Feeding.fetchOne(db))
            XCTAssertNotNil(row.deletedAt, "the row must stay, flagged")
            XCTAssertEqual(row.updatedAt, clock, "or the deletion loses to an older edit")
        }
    }

    func testDeletedRowsAreNotRead() async throws {
        _ = try makeBaby()
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        let stored = try queue.read { db in try Feeding.fetchOne(db) }
        let feeding = try XCTUnwrap(stored)
        try repository.deleteFeeding(feeding)

        for try await feedings in repository.feedings {
            XCTAssertTrue(feedings.isEmpty, "a forgotten deletedAt filter shows deleted entries again")
            break
        }
    }

    // MARK: - The active baby

    func testTheFirstBabyBecomesActiveAndLaterOnesDoNot() throws {
        let first = try makeBaby("Robin")
        let second = try makeBaby("Sam")

        XCTAssertEqual(try repository.activeBabyIdNow(), first)
        XCTAssertNotEqual(try repository.activeBabyIdNow(), second)
    }

    func testLogsFollowTheActiveBaby() async throws {
        let robin = try makeBaby("Robin")
        let sam = try makeBaby("Sam")

        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        try repository.setActiveBaby(id: sam)
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 120, startTime: clock))

        for try await feedings in repository.feedings {
            XCTAssertEqual(feedings.map(\.amountMl), [120], "Sam's feed only")
            break
        }

        try repository.setActiveBaby(id: robin)
        for try await feedings in repository.feedings {
            XCTAssertEqual(feedings.map(\.amountMl), [90], "Robin's feed only")
            break
        }
    }

    /// Archiving whoever is selected has to hand the selection on, or the app
    /// opens on a baby nobody is tracking.
    func testArchivingTheActiveBabyPassesTheSelectionOn() throws {
        let robin = try makeBaby("Robin")
        let sam = try makeBaby("Sam")

        try repository.archiveBaby(id: robin)

        XCTAssertEqual(try repository.activeBabyIdNow(), sam)
        XCTAssertEqual(try repository.activeBabies().map(\.name), ["Sam"])
    }

    func testArchivingKeepsTheHistory() throws {
        let robin = try makeBaby("Robin")
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))

        try repository.archiveBaby(id: robin)

        try queue.read { db in
            XCTAssertEqual(try Feeding.fetchCount(db), 1, "stop tracking is not delete")
        }
        try repository.restoreBaby(id: robin)
        XCTAssertEqual(try repository.activeBabies().map(\.name), ["Robin"])
    }

    // MARK: - Pumping belongs to the parent

    func testPumpingIsNotScopedToABaby() async throws {
        let robin = try makeBaby("Robin")
        let sam = try makeBaby("Sam")
        try repository.addPumping(Pumping(time: clock, amountMl: 60, storage: .FRIDGE))

        for babyId in [robin, sam] {
            try repository.setActiveBaby(id: babyId)
            for try await pumpings in repository.pumpings {
                XCTAssertEqual(pumpings.count, 1, "the same session, whoever is selected (BDR-0007)")
                break
            }
        }
    }

    // MARK: - Tombstones

    func testCompactionErasesRowsPastTheRetentionWindow() throws {
        _ = try makeBaby()
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        let stored = try queue.read { db in try Feeding.fetchOne(db) }
        let feeding = try XCTUnwrap(stored)
        try repository.deleteFeeding(feeding)

        // A repository whose clock is well past the retention window.
        let later = clock + SproutRepository.tombstoneRetentionMs + 1
        let future = SproutRepository(database: queue, now: { later })
        try future.compactTombstones()

        try queue.read { db in
            XCTAssertEqual(try Feeding.fetchCount(db), 0, "past retention the row goes for good")
        }
    }

    func testCompactionLeavesRecentDeletionsAlone() throws {
        _ = try makeBaby()
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        let stored = try queue.read { db in try Feeding.fetchOne(db) }
        let feeding = try XCTUnwrap(stored)
        try repository.deleteFeeding(feeding)

        try repository.compactTombstones()

        try queue.read { db in
            XCTAssertEqual(
                try Feeding.fetchCount(db), 1,
                "a partner who has not synced this week still needs to hear about it"
            )
        }
    }

    // MARK: - Reminder queries

    func testLastFeedTimeIgnoresDeletedFeeds() throws {
        let babyId = try makeBaby()
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock - 10_000))
        try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: 90, startTime: clock))
        let latest = try queue.read { db in try Feeding.order(Column("startTime").desc).fetchOne(db) }
        let newest = try XCTUnwrap(latest)

        XCTAssertEqual(try repository.lastFeedTime(babyId: babyId), clock)

        try repository.deleteFeeding(newest)

        XCTAssertEqual(
            try repository.lastFeedTime(babyId: babyId), clock - 10_000,
            "a deleted feed must not hold off the reminder"
        )
    }

    func testTreatmentsWithRemindersExcludesStoppedAndSilentOnes() throws {
        _ = try makeBaby()
        try repository.addTreatment(Treatment(name: "Vitamin D", startDate: clock))
        try repository.addTreatment(
            Treatment(name: "Finished course", startDate: clock, active: false)
        )
        try repository.addTreatment(
            Treatment(name: "No reminder", startDate: clock, remindersEnabled: false)
        )

        XCTAssertEqual(try repository.treatmentsWithReminders().map(\.name), ["Vitamin D"])
    }
}
