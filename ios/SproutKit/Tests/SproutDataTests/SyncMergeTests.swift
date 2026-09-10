import GRDB
import XCTest
@testable import SproutData

/// Two phones, one baby.
///
/// Each test runs two real databases side by side and moves replicas between
/// them, because the properties worth having are properties of the *pair*:
/// applying the same replica twice must change nothing, exchanging in either
/// order must converge, and a deletion must stay deleted — the failure this whole
/// design exists to prevent is the partner's copy quietly resurrecting what
/// someone deleted (ADR-0007).
///
/// The Kotlin opposite number is `SyncMergeTest`, and the cases are deliberately
/// the same ones in the same order. Where the two apps disagree about a merge,
/// one of these two files is the place it shows up.
final class SyncMergeTests: XCTestCase {

    /// Static so the nested `Phone` can see it: a second literal here would be a
    /// second household, and every merge would refuse quietly.
    private static let household = "household-1"

    /// A clock the test can wind forward. Both the repository and the engine read
    /// it, so an edit made "later" really does carry a later `updatedAt`.
    private final class Clock: @unchecked Sendable {
        var now: Int64 = 1_000_000
    }

    /// One phone: its database, the repository that writes to it, and its engine.
    private final class Phone {
        let name: String
        let queue: DatabaseQueue
        let clock: Clock
        let repository: SproutRepository
        let engine: SyncEngine

        init(_ name: String) throws {
            let clock = Clock()
            let queue = try SproutDatabase.inMemory()
            self.name = name
            self.clock = clock
            self.queue = queue
            self.repository = SproutRepository(database: queue, now: { clock.now })
            self.engine = SyncEngine(database: queue, now: { clock.now })
        }

        func replica(includePumping: Bool = true) throws -> SyncPayload {
            try engine.buildPayload(
                householdId: SyncMergeTests.household,
                deviceId: name,
                deviceName: name,
                includePumping: includePumping
            )
        }

        func seedParent() throws {
            try repository.saveParentProfile(
                ParentProfile(name: name, gaveBirth: true, breastfeeding: true)
            )
        }

        @discardableResult
        func addBaby(_ name: String = "Léa") throws -> Int64 {
            try repository.addBaby(name: name, birthDate: 1_699_000_000_000)
        }

        func logFeed(_ amountMl: Int, at time: Int64) throws {
            try repository.addFeeding(Feeding(type: .BOTTLE, amountMl: amountMl, startTime: time))
        }

        /// What a screen would show: the live rows, in order.
        func feedings() throws -> [Feeding] {
            try queue.read { db in
                try Feeding
                    .filter(Column("deletedAt") == nil)
                    .order(Column("startTime"))
                    .fetchAll(db)
            }
        }

        func feedingUids() throws -> Set<String> { Set(try feedings().map(\.uid)) }

        /// The sync view: every row on the phone, soft-deleted ones included.
        func allRows<T: FetchableRecord & TableRecord>(_: T.Type) throws -> [T] {
            try queue.read { db in try T.fetchAll(db) }
        }

        func babyId() throws -> Int64 {
            let baby = try queue.read { db in
                try Baby.filter(Column("deletedAt") == nil).fetchOne(db)
            }
            return try XCTUnwrap(baby?.id)
        }
    }

    // MARK: - Adoption

    func testAPhoneWithNoHistoryOfItsOwnAdoptsTheOthers() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        try alex.logFeed(80, at: 1_700_000_200_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        XCTAssertFalse(try sam.engine.hasOwnHistory(), "the fresh phone has nothing yet")

        let summary = try sam.engine.merge(try alex.replica())

        XCTAssertEqual(summary.added, 3, "one baby and two feeds")
        XCTAssertEqual(summary.skipped, 0)
        XCTAssertEqual(try sam.allRows(Baby.self).map(\.name), ["Léa"])
        XCTAssertEqual(try sam.feedings().compactMap(\.amountMl).sorted(), [80, 90])
    }

    // MARK: - Idempotence and convergence

    func testMergingTheSameReplicaTwiceChangesNothingTheSecondTime() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        let replica = try alex.replica()

        let sam = try Phone("sam")
        try sam.seedParent()
        let first = try sam.engine.merge(replica)
        let second = try sam.engine.merge(replica)

        XCTAssertEqual(first.added, 2)
        XCTAssertEqual(second.added, 0, "idempotent: nothing new the second time")
        XCTAssertEqual(second.updated, 0)
        XCTAssertEqual(second.alreadyKnown, 2)
        XCTAssertEqual(try sam.feedings().count, 1)
    }

    func testEachPhonesOwnEntriesSurviveAnExchangeInEitherDirection() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())
        // Adopting a baby doesn't select it — the import flow does that; here it
        // is done by hand so this phone can log something of its own.
        try sam.repository.setActiveBaby(id: try sam.babyId())
        sam.clock.now += 1_000
        try sam.logFeed(70, at: 1_700_000_300_000)

        // Both directions, and then both again: convergence, not ping-pong.
        _ = try alex.engine.merge(try sam.replica())
        _ = try sam.engine.merge(try alex.replica())
        _ = try alex.engine.merge(try sam.replica())

        XCTAssertEqual(try alex.feedingUids(), try sam.feedingUids(), "both phones hold the same entries")
        XCTAssertEqual(try alex.feedings().count, 2)
        XCTAssertEqual(try alex.feedings().compactMap(\.amountMl).sorted(), [70, 90])
    }

    // MARK: - Which write wins

    func testTheLaterEditIsTheOneThatWins() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())

        alex.clock.now += 5_000
        var corrected = try XCTUnwrap(try alex.feedings().first)
        corrected.amountMl = 120
        try alex.repository.addFeeding(corrected)

        let summary = try sam.engine.merge(try alex.replica())

        XCTAssertEqual(summary.updated, 1)
        XCTAssertEqual(summary.added, 0)
        XCTAssertEqual(try sam.feedings().count, 1, "the correction, not a second feed")
        XCTAssertEqual(try sam.feedings().first?.amountMl, 120)
    }

    /// The other half of the same rule, which the summary alone would not catch:
    /// an *older* copy of a row we have already corrected must lose.
    func testAnOlderCopyOfAnEditedRowIsRefused() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        let staleReplica = try alex.replica()

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(staleReplica)
        sam.clock.now += 5_000
        try sam.repository.setActiveBaby(id: try sam.babyId())
        var corrected = try XCTUnwrap(try sam.feedings().first)
        corrected.amountMl = 120
        try sam.repository.addFeeding(corrected)

        let summary = try sam.engine.merge(staleReplica)

        XCTAssertEqual(summary.updated, 0)
        XCTAssertEqual(summary.alreadyKnown, 2)
        XCTAssertEqual(try sam.feedings().first?.amountMl, 120, "the correction stands")
    }

    // MARK: - Deletions

    func testADeletionReachesTheOtherPhone() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        try alex.logFeed(80, at: 1_700_000_200_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())

        alex.clock.now += 5_000
        try alex.repository.deleteFeeding(try XCTUnwrap(try alex.feedings().first))
        let summary = try sam.engine.merge(try alex.replica())

        XCTAssertEqual(summary.deleted, 1)
        XCTAssertEqual(try sam.feedings().count, 1)
    }

    func testADeletionIsNotUndoneByACopyThatPredatesIt() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        let staleReplica = try alex.replica()

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(staleReplica)
        sam.clock.now += 5_000
        try sam.repository.deleteFeeding(try XCTUnwrap(try sam.feedings().first))

        // Alex's phone still holds the entry and offers it back.
        let summary = try sam.engine.merge(staleReplica)

        XCTAssertEqual(summary.added, 0, "resurrecting it would undo what the parent asked for")
        XCTAssertTrue(try sam.feedings().isEmpty)
    }

    func testAnErasedBabyIsNotRestoredByTheOtherPhonesCopy() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)
        let replica = try alex.replica()

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(replica)
        sam.clock.now += 5_000
        try sam.repository.deleteBaby(id: try sam.babyId())

        let summary = try sam.engine.merge(replica)

        XCTAssertEqual(summary.added, 0)
        XCTAssertEqual(summary.skipped, 2, "both the baby and its feed stay gone")
        XCTAssertTrue(try sam.allRows(Baby.self).isEmpty)
        XCTAssertTrue(try sam.allRows(Feeding.self).isEmpty)
    }

    func testTheErasureTravelsSoTheOtherPhoneLetsItGoToo() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())

        alex.clock.now += 5_000
        try alex.repository.deleteBaby(id: try alex.babyId())
        let summary = try sam.engine.merge(try alex.replica())

        XCTAssertEqual(summary.deleted, 2, "the baby and its feed")
        XCTAssertTrue(try sam.allRows(Baby.self).isEmpty)
        XCTAssertTrue(try sam.allRows(Feeding.self).isEmpty)
    }

    /// A tombstone names a table, and that name is interpolated into SQL. It is
    /// read off a replica, which is parsed with nothing authenticated at all
    /// (ADR-0014), so the names are an allow-list rather than whatever arrived.
    func testATombstoneForATableWeDoNotKnowIsKeptAndErasesNothing() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())

        var replica = try alex.replica()
        replica.tombstones.append(
            Tombstone(uid: "from-a-newer-sprout", entity: "milestone", deletedAt: 1_700_000_400_000)
        )
        let summary = try sam.engine.merge(replica)

        XCTAssertEqual(summary.deleted, 0)
        XCTAssertEqual(try sam.feedings().count, 1, "the rows we do have are untouched")
        // Kept, not dropped: a later release that knows the table can act on it.
        XCTAssertTrue(
            try sam.allRows(Tombstone.self).contains { $0.uid == "from-a-newer-sprout" }
        )
    }

    // MARK: - What a replica is allowed to carry

    func testTheStashSwitchKeepsExpressedMilkAtHome() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.repository.addPumping(
            Pumping(time: 1_700_000_100_000, amountMl: 120, storage: .FRIDGE)
        )

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica(includePumping: false))

        XCTAssertTrue(
            try sam.allRows(Pumping.self).isEmpty,
            "a replica without the stash merges like any other"
        )
        XCTAssertEqual(try sam.allRows(Baby.self).count, 1, "the rest still arrives")

        // And turning it back on shares it, without any special handling.
        _ = try sam.engine.merge(try alex.replica(includePumping: true))
        XCTAssertEqual(try sam.allRows(Pumping.self).map(\.amountMl), [120])
    }

    /// The number the other phone judges us by. It has to be the schema this
    /// build actually runs, in Android's numbering — a replica that understates
    /// it would be accepted and then applied against columns that are not there.
    func testAReplicaSaysWhichSchemaItWasWrittenOn() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()

        XCTAssertEqual(try alex.replica().schemaVersion, SproutDatabase.schemaVersion)
        XCTAssertEqual(
            SproutDatabase.schemaVersion,
            SproutDatabase.initialAndroidSchemaVersion
                + SproutDatabase.migrationsAfterAndroidSchema.count
        )
    }

    /// The parent's own data never travels (BDR-0007) — not filtered at the far
    /// end, simply never put in.
    func testTheParentsOwnDataIsNotInTheReplicaAtAll() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.repository.addWellbeing(Wellbeing(time: 1_700_000_100_000, mood: 3))

        let sam = try Phone("sam")
        try sam.seedParent()
        _ = try sam.engine.merge(try alex.replica())

        XCTAssertTrue(try sam.allRows(Wellbeing.self).isEmpty)
        XCTAssertEqual(try sam.allRows(ParentProfile.self).map(\.name), ["sam"])
    }

    func testSharingFromThePairingForwardLeavesTheEarlierEntriesAtHome() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        let pairedAt = alex.clock.now + 1
        alex.clock.now += 10_000
        try alex.logFeed(80, at: 1_700_000_200_000)

        let sam = try Phone("sam")
        try sam.seedParent()
        let summary = try sam.engine.merge(try alex.replica(), since: pairedAt)

        XCTAssertEqual(summary.added, 2, "the baby, and only what was logged after pairing")
        XCTAssertEqual(summary.skipped, 1)
        XCTAssertEqual(try sam.allRows(Feeding.self).compactMap(\.amountMl), [80])
    }

    // MARK: - Local ids

    /// `id` is a per-device counter, so it must not survive the trip. The check
    /// is not cosmetic: a merged row keeping the sender's id would collide with
    /// this phone's own next insert.
    func testALocalIdNeverTravels() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        // Sam logs first, so the two phones number their rows differently.
        let sam = try Phone("sam")
        try sam.seedParent()
        try sam.addBaby("Robin")
        try sam.logFeed(60, at: 1_700_000_050_000)

        _ = try sam.engine.merge(try alex.replica())

        let ids = try sam.allRows(Feeding.self).compactMap(\.id)
        XCTAssertEqual(ids.count, 2)
        XCTAssertEqual(Set(ids).count, 2, "two rows, two ids of this phone's own")

        // And the uid — the name that *does* travel — came through unchanged.
        let alexUid = try XCTUnwrap(try alex.feedings().first?.uid)
        XCTAssertTrue(try sam.allRows(Feeding.self).contains { $0.uid == alexUid })
    }

    /// A log for a baby neither phone has left has nowhere to go. It is counted
    /// as skipped rather than dropped silently, so the summary still adds up.
    func testALogForABabyNeitherPhoneHasIsSkipped() throws {
        let alex = try Phone("alex")
        try alex.seedParent()
        try alex.addBaby()
        try alex.logFeed(90, at: 1_700_000_100_000)

        var replica = try alex.replica()
        // The baby's row falls out, the way it would if it had been erased
        // outright; its logs still name it.
        replica.babies = []

        let sam = try Phone("sam")
        try sam.seedParent()
        let summary = try sam.engine.merge(replica)

        XCTAssertEqual(summary.added, 0)
        XCTAssertEqual(summary.skipped, 1)
        XCTAssertTrue(try sam.allRows(Feeding.self).isEmpty)
    }
}
