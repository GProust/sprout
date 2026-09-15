import Foundation
import GRDB
import SproutKit

/// What a merge actually did — the thing to put in front of the user afterwards.
///
/// A merge that reports nothing is indistinguishable from one that failed
/// (ADR-0007), and "nothing new" is a perfectly good outcome that still deserves
/// saying out loud.
public struct MergeSummary: Equatable, Sendable {
    public var added: Int
    public var updated: Int
    public var deleted: Int
    public var alreadyKnown: Int
    /// Rows left out: already deleted here, or older than a pairing cut-off.
    public var skipped: Int

    public init(
        added: Int = 0,
        updated: Int = 0,
        deleted: Int = 0,
        alreadyKnown: Int = 0,
        skipped: Int = 0
    ) {
        self.added = added
        self.updated = updated
        self.deleted = deleted
        self.alreadyKnown = alreadyKnown
        self.skipped = skipped
    }

    public var changed: Bool { added > 0 || updated > 0 || deleted > 0 }

    public static func + (lhs: MergeSummary, rhs: MergeSummary) -> MergeSummary {
        MergeSummary(
            added: lhs.added + rhs.added,
            updated: lhs.updated + rhs.updated,
            deleted: lhs.deleted + rhs.deleted,
            alreadyKnown: lhs.alreadyKnown + rhs.alreadyKnown,
            skipped: lhs.skipped + rhs.skipped
        )
    }

    /// Spelled out rather than inherited from `+`: Swift synthesises neither.
    public static func += (lhs: inout MergeSummary, rhs: MergeSummary) {
        lhs = lhs + rhs
    }
}

/// Builds replicas, and merges the ones that arrive (ADR-0007).
/// From `data/sync/SyncEngine.kt`.
///
/// The merge is **commutative and idempotent**: applying the same replica twice
/// changes nothing the second time, and A-then-B lands on the same database as
/// B-then-A. That is what lets an exchange be repeated, interrupted or run out of
/// order without damage — and it is the property the tests are really about.
///
/// This is the one part of the app that writes rows the repository did not stamp,
/// and deliberately so: `uid` and `updatedAt` arriving from the other phone are
/// the row's identity and its place in the ordering. Re-stamping them here would
/// make every merged row look like the newest edit in the household, and the next
/// exchange would push that back over the other phone's real one.
public final class SyncEngine: @unchecked Sendable {

    private let database: DatabaseQueue
    private let now: @Sendable () -> Int64

    public init(
        database: DatabaseQueue,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.database = database
        self.now = now
    }

    /// The schema this build speaks, in Android's numbering (see
    /// ``SproutDatabase/schemaVersion``).
    public var schemaVersion: Int { SproutDatabase.schemaVersion }

    // MARK: - Building

    /// Everything this phone is willing to share, ready to be encrypted and sent.
    ///
    /// - Parameter includePumping: the stash switch (ADR-0008). Off means the
    ///   pumping rows simply never leave, and a replica without them merges like
    ///   any other.
    public func buildPayload(
        householdId: String,
        deviceId: String,
        deviceName: String,
        includePumping: Bool
    ) throws -> SyncPayload {
        try database.read { db in
            // Soft-deleted rows included, on purpose: a flagged row is how a
            // deletion reaches the other phone instead of being undone by it.
            let babies = try Baby.fetchAll(db)
            var uidOf: [Int64: String] = [:]
            for baby in babies {
                if let id = baby.id { uidOf[id] = baby.uid }
            }

            // A log whose baby was permanently deleted has nothing to point at;
            // the baby's tombstone is what travels instead.
            func scoped<T: SyncableRecord & FetchableRecord & BabyScoped & Equatable>()
                throws -> [BabyScopedRow<T>]
            {
                try T.fetchAll(db).compactMap { row in
                    uidOf[row.babyId].map { BabyScopedRow(babyUid: $0, row: row) }
                }
            }

            var payload = SyncPayload(
                householdId: householdId,
                deviceId: deviceId,
                deviceName: deviceName,
                createdAt: now(),
                schemaVersion: SproutDatabase.schemaVersion
            )
            payload.babies = babies
            payload.feedings = try scoped()
            payload.sleeps = try scoped()
            payload.diapers = try scoped()
            payload.growth = try scoped()
            payload.treatments = try scoped()
            // `try` in front of the whole conditional: Swift refuses it to the
            // right of a non-assignment operator.
            payload.pumpings = try includePumping ? Pumping.fetchAll(db) : []
            payload.tombstones = try Tombstone.fetchAll(db)
            return payload
        }
    }

    /// Whether this phone has any baby data of its own yet (ADR-0008's adoption
    /// case).
    public func hasOwnHistory() throws -> Bool {
        try database.read { db in try Baby.fetchCount(db) } > 0
    }

    // MARK: - Merging

    /// Merges an arriving replica into this database.
    ///
    /// - Parameter since: when set, only rows *written* at or after this moment
    ///   are taken — the "share from the pairing forward" answer for two parents
    ///   who both tracked before pairing (ADR-0008). Rows are judged by when they
    ///   were written, not by the time they describe, so backdating an entry does
    ///   not hide it.
    ///
    /// One transaction for the whole replica. A merge interrupted half-way would
    /// otherwise leave logs pointing at a baby whose own row never landed.
    public func merge(_ payload: SyncPayload, since: Int64? = nil) throws -> MergeSummary {
        try database.write { db in
            var summary = try applyTombstones(db, payload.tombstones)

            // Babies first: a log points at its baby by uid, and can only be
            // stored once that baby has a local id to point at.
            //
            // They ignore `since` on purpose. "Share from the pairing forward" is
            // about entries, not about who the baby is — filtering the baby out
            // would leave every later log with nothing to attach to.
            summary += try mergeRows(db, payload.babies, since: nil)

            var babyIdOf: [String: Int64] = [:]
            for baby in try Baby.fetchAll(db) {
                if let id = baby.id { babyIdOf[baby.uid] = id }
            }

            summary += try mergeScoped(db, payload.feedings, since: since, babyIdOf: babyIdOf)
            summary += try mergeScoped(db, payload.sleeps, since: since, babyIdOf: babyIdOf)
            summary += try mergeScoped(db, payload.diapers, since: since, babyIdOf: babyIdOf)
            summary += try mergeScoped(db, payload.growth, since: since, babyIdOf: babyIdOf)
            summary += try mergeScoped(db, payload.treatments, since: since, babyIdOf: babyIdOf)
            summary += try mergeRows(db, payload.pumpings, since: since)
            return summary
        }
    }

    /// Erasures the other phone performed. The row goes, the uid stays — locally
    /// too, so that this phone stops offering the entry back in its own replicas.
    private func applyTombstones(_ db: Database, _ tombstones: [Tombstone]) throws -> MergeSummary {
        if tombstones.isEmpty { return MergeSummary() }
        var deleted = 0
        for tombstone in tombstones {
            // The entity names are the table names, which is what makes this a
            // lookup rather than a switch with seven near-identical arms. A name
            // we do not know is a table from a newer Sprout: keep the tombstone,
            // touch nothing.
            guard Self.purgeableTables.contains(tombstone.entity) else { continue }
            let existed = try Int.fetchOne(
                db,
                sql: "SELECT COUNT(*) FROM \(tombstone.entity) WHERE uid = ?",
                arguments: [tombstone.uid]
            ) ?? 0
            if existed > 0 {
                try db.execute(
                    sql: "DELETE FROM \(tombstone.entity) WHERE uid = ?",
                    arguments: [tombstone.uid]
                )
                deleted += 1
            }
        }
        // `save`, not `insert`: a tombstone we already hold arriving again is the
        // same erasure, not a primary-key collision.
        for tombstone in tombstones { try tombstone.save(db) }
        return MergeSummary(deleted: deleted)
    }

    /// The tables a tombstone may name. Interpolated into SQL, so it is a closed
    /// list and not the string off the wire — a replica is parsed with nothing
    /// authenticated at all (ADR-0014).
    private static let purgeableTables: Set<String> = [
        "baby", "feeding", "sleep", "diaper", "growth", "treatment", "pumping",
    ]

    private func mergeScoped<T: SyncableRecord & FetchableRecord & BabyScoped & Equatable>(
        _ db: Database,
        _ rows: [BabyScopedRow<T>],
        since: Int64?,
        babyIdOf: [String: Int64]
    ) throws -> MergeSummary {
        // A log for a baby neither phone has (permanently deleted, most likely)
        // has nowhere to go.
        var placeable: [T] = []
        var orphaned = 0
        for scoped in rows {
            guard let babyId = babyIdOf[scoped.babyUid] else {
                orphaned += 1
                continue
            }
            var row = scoped.row
            row.babyId = babyId
            placeable.append(row)
        }
        let merged = try mergeRows(db, placeable, since: since)
        return merged + MergeSummary(skipped: orphaned)
    }

    /// The merge rules themselves (ADR-0007), in one place:
    ///
    /// - a uid we have never seen is inserted;
    /// - a uid we know takes the *later* write, by `updatedAt`;
    /// - a tombstone wins on either side, whatever the clocks say;
    /// - a uid we have already erased is never taken back.
    private func mergeRows<T: SyncableRecord & FetchableRecord>(
        _ db: Database,
        _ rows: [T],
        since: Int64?
    ) throws -> MergeSummary {
        var summary = MergeSummary()
        for incoming in rows {
            if let since, incoming.updatedAt < since {
                summary += MergeSummary(skipped: 1)
                continue
            }
            if try Tombstone.filter(Column("uid") == incoming.uid).fetchCount(db) > 0 {
                // Erased here for good. Taking it back would undo a deletion the
                // user asked for, which is the whole failure this design exists
                // to prevent.
                summary += MergeSummary(skipped: 1)
                continue
            }
            guard let local = try T.filter(Column("uid") == incoming.uid).fetchOne(db) else {
                if incoming.deletedAt != nil {
                    // Created and deleted while we weren't looking: nothing to show.
                    summary += MergeSummary(skipped: 1)
                } else {
                    var row = incoming
                    // The other phone's local counter means something else here.
                    row.id = nil
                    try row.insert(db)
                    summary += MergeSummary(added: 1)
                }
                continue
            }
            if local.deletedAt != nil {
                // Deleted here already; a tombstone outranks any later edit.
                summary += MergeSummary(alreadyKnown: 1)
            } else if incoming.deletedAt != nil {
                try replace(db, incoming, keepingIdOf: local)
                summary += MergeSummary(deleted: 1)
            } else if incoming.updatedAt > local.updatedAt {
                try replace(db, incoming, keepingIdOf: local)
                summary += MergeSummary(updated: 1)
            } else {
                summary += MergeSummary(alreadyKnown: 1)
            }
        }
        return summary
    }

    /// Writes the arriving row over the local one, keeping the local `id`.
    ///
    /// Everything else is taken wholesale, `uid` and `updatedAt` included: this is
    /// the later write winning, not a field-by-field reconciliation. Merging
    /// column by column would invent a row neither phone ever had.
    private func replace<T: SyncableRecord>(_ db: Database, _ incoming: T, keepingIdOf local: T) throws {
        var row = incoming
        row.id = local.id
        try row.update(db)
    }
}
