import Foundation
import GRDB
import SproutKit

/// Single point of access to persisted data, backing every screen.
///
/// Mirrors `data/SproutRepository.kt`, and keeps its two load-bearing rules:
///
/// - **The baby-scoped logs always follow the *active* baby.** Reads filter by
///   it and writes are stamped with it, so no screen has to thread a baby id
///   around and none can forget to.
/// - **This is the only place that stamps `uid` and `updatedAt`.** A record
///   written straight to the database is an unstamped row, which then loses
///   every merge. Keep it that way.
///
/// Reads are `AsyncSequence`s from GRDB's observation, which is what Room's
/// `Flow` is on the other side: the screen gets the current value and then every
/// change to it.
public final class SproutRepository: @unchecked Sendable {

    public let database: DatabaseQueue

    /// Called after any write that can change what the widget shows. Wired by
    /// the app; a no-op in tests.
    private let onWidgetDataChanged: @Sendable () async -> Void

    /// The clock, injectable so tests are not at the mercy of one.
    private let now: @Sendable () -> Int64

    public static let tombstoneRetentionMs = SproutKit.tombstoneRetentionMs

    public init(
        database: DatabaseQueue,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        onWidgetDataChanged: @escaping @Sendable () async -> Void = {}
    ) {
        self.database = database
        self.now = now
        self.onWidgetDataChanged = onWidgetDataChanged
    }

    // MARK: - Parent profile

    public var parentProfile: AsyncValueObservation<ParentProfile?> {
        observe { db in try ParentProfile.fetchOne(db, key: ParentProfile.singletonId) }
    }

    public func parentProfileOnce() throws -> ParentProfile? {
        try database.read { db in try ParentProfile.fetchOne(db, key: ParentProfile.singletonId) }
    }

    public func saveParentProfile(_ profile: ParentProfile) throws {
        try database.write { db in try profile.save(db) }
    }

    public func updateParentLastCheckIn(_ time: Int64) throws {
        try updateProfile { $0.lastCheckIn = time }
    }

    public func setAskHealing(_ ask: Bool) throws { try updateProfile { $0.askHealing = ask } }
    public func setAskBleeding(_ ask: Bool) throws { try updateProfile { $0.askBleeding = ask } }
    public func setAskBreasts(_ ask: Bool) throws { try updateProfile { $0.askBreasts = ask } }

    /// Turn the parent's own wellbeing tracking on or off; kept history is
    /// untouched, so switching it back on finds everything still there.
    public func setTrackWellbeing(_ track: Bool) throws {
        try updateProfile { $0.trackWellbeing = track }
    }

    private func updateProfile(_ change: (inout ParentProfile) -> Void) throws {
        try database.write { db in
            guard var profile = try ParentProfile.fetchOne(db, key: ParentProfile.singletonId) else { return }
            change(&profile)
            try profile.update(db)
        }
    }

    // MARK: - Babies

    /// Babies currently being tracked, in birth order.
    public var babies: AsyncValueObservation<[Baby]> {
        observe { db in
            try Baby
                .filter(Column("archived") == false && Column("deletedAt") == nil)
                .order(Column("birthDate"))
                .fetchAll(db)
        }
    }

    /// Babies the user has stopped tracking — kept, but out of the rotation.
    public var archivedBabies: AsyncValueObservation<[Baby]> {
        observe { db in
            try Baby
                .filter(Column("archived") == true && Column("deletedAt") == nil)
                .order(Column("birthDate"))
                .fetchAll(db)
        }
    }

    /// The active baby, or `nil` before onboarding has run.
    public var baby: AsyncValueObservation<Baby?> {
        observe { db in try Self.activeBaby(db) }
    }

    public func activeBabies() throws -> [Baby] {
        try database.read { db in
            try Baby
                .filter(Column("archived") == false && Column("deletedAt") == nil)
                .order(Column("birthDate"))
                .fetchAll(db)
        }
    }

    public func activeBaby(id: Int64) throws -> Baby? {
        try database.read { db in
            try Baby
                .filter(Column("id") == id && Column("archived") == false && Column("deletedAt") == nil)
                .fetchOne(db)
        }
    }

    public func activeBabyIdNow() throws -> Int64? {
        try parentProfileOnce()?.activeBabyId
    }

    public func babyName(id: Int64) throws -> String? {
        try database.read { db in try Baby.fetchOne(db, key: id)?.name }
    }

    public func activeBabyName() throws -> String? {
        try database.read { db in try Self.activeBaby(db)?.name }
    }

    @discardableResult
    public func addBaby(name: String, birthDate: Int64) throws -> Int64 {
        let timestamp = now()
        return try database.write { db in
            var baby = Baby(name: name.trimmingCharacters(in: .whitespacesAndNewlines), birthDate: birthDate)
            baby.uid = newUid()
            baby.updatedAt = timestamp
            try baby.insert(db)
            let id = baby.id ?? 0

            // The first baby becomes the active one; later ones do not steal
            // the selection from whoever is being tracked right now.
            if var profile = try ParentProfile.fetchOne(db, key: ParentProfile.singletonId),
               profile.activeBabyId == nil {
                profile.activeBabyId = id
                try profile.update(db)
            }
            return id
        }
    }

    public func updateBaby(_ baby: Baby) throws {
        let timestamp = now()
        try database.write { db in
            var copy = baby
            copy.updatedAt = timestamp
            if copy.uid.isEmpty { copy.uid = newUid() }
            try copy.update(db)
        }
    }

    public func setActiveBaby(id: Int64) throws {
        try updateProfile { $0.activeBabyId = id }
        Task { await onWidgetDataChanged() }
    }

    /// "Stop tracking": the baby leaves the rotation and the history stays.
    public func archiveBaby(id: Int64) throws {
        try setArchived(id: id, archived: true)
    }

    public func restoreBaby(id: Int64) throws {
        try setArchived(id: id, archived: false)
    }

    private func setArchived(id: Int64, archived: Bool) throws {
        let timestamp = now()
        try database.write { db in
            guard var baby = try Baby.fetchOne(db, key: id) else { return }
            baby.archived = archived
            baby.updatedAt = timestamp
            try baby.update(db)

            // Archiving the active baby has to hand the selection to someone,
            // or the app opens on a baby nobody is tracking.
            if archived,
               var profile = try ParentProfile.fetchOne(db, key: ParentProfile.singletonId),
               profile.activeBabyId == id {
                profile.activeBabyId = try Baby
                    .filter(Column("archived") == false && Column("deletedAt") == nil)
                    .order(Column("birthDate"))
                    .fetchOne(db)?.id
                try profile.update(db)
            }
        }
        Task { await onWidgetDataChanged() }
    }

    // MARK: - Logs

    public var feedings: AsyncValueObservation<[Feeding]> {
        observeForActiveBaby { db, babyId in
            try Feeding
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("startTime").desc)
                .fetchAll(db)
        }
    }

    public var sleeps: AsyncValueObservation<[Sleep]> {
        observeForActiveBaby { db, babyId in
            try Sleep
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("startTime").desc)
                .fetchAll(db)
        }
    }

    public var diapers: AsyncValueObservation<[Diaper]> {
        observeForActiveBaby { db, babyId in
            try Diaper
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("time").desc)
                .fetchAll(db)
        }
    }

    public var growth: AsyncValueObservation<[Growth]> {
        observeForActiveBaby { db, babyId in
            try Growth
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("time").desc)
                .fetchAll(db)
        }
    }

    public var treatments: AsyncValueObservation<[Treatment]> {
        observeForActiveBaby { db, babyId in
            try Treatment
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("name"))
                .fetchAll(db)
        }
    }

    /// Pumping belongs to the parent, not to a baby (BDR-0007), so it is not
    /// scoped by the active one.
    public var pumpings: AsyncValueObservation<[Pumping]> {
        observe { db in
            try Pumping
                .filter(Column("deletedAt") == nil)
                .order(Column("time").desc)
                .fetchAll(db)
        }
    }

    public var wellbeing: AsyncValueObservation<[Wellbeing]> {
        observe { db in try Wellbeing.order(Column("time").desc).fetchAll(db) }
    }

    /// Sleeps still running, across every tracked baby — the dashboard shows
    /// them whichever baby is selected.
    public var ongoingSleeps: AsyncValueObservation<[Sleep]> {
        observe { db in
            try Sleep
                .filter(Column("endTime") == nil && Column("deletedAt") == nil)
                .order(Column("startTime").desc)
                .fetchAll(db)
        }
    }

    // MARK: - Writes

    public func addFeeding(_ feeding: Feeding) throws {
        guard let babyId = try activeBabyIdNow() else { return }
        try addFeeding(feeding, for: babyId)
    }

    public func addFeeding(_ feeding: Feeding, for babyId: Int64) throws {
        try insert(feeding, babyId: babyId)
        Task { await onWidgetDataChanged() }
    }

    public func deleteFeeding(_ feeding: Feeding) throws {
        try softDelete(feeding)
        Task { await onWidgetDataChanged() }
    }

    public func addSleep(_ sleep: Sleep) throws {
        guard let babyId = try activeBabyIdNow() else { return }
        try insert(sleep, babyId: babyId)
    }

    public func updateSleep(_ sleep: Sleep) throws {
        let timestamp = now()
        try database.write { db in
            var copy = sleep
            copy.updatedAt = timestamp
            try copy.update(db)
        }
    }

    public func deleteSleep(_ sleep: Sleep) throws { try softDelete(sleep) }

    public func addDiaper(_ diaper: Diaper) throws {
        guard let babyId = try activeBabyIdNow() else { return }
        try insert(diaper, babyId: babyId)
    }

    public func deleteDiaper(_ diaper: Diaper) throws { try softDelete(diaper) }

    public func addGrowth(_ growth: Growth) throws {
        guard let babyId = try activeBabyIdNow() else { return }
        try insert(growth, babyId: babyId)
    }

    public func deleteGrowth(_ growth: Growth) throws { try softDelete(growth) }

    @discardableResult
    public func addTreatment(_ treatment: Treatment) throws -> Int64? {
        guard let babyId = try activeBabyIdNow() else { return nil }
        let timestamp = now()
        return try database.write { db in
            var copy = treatment
            copy.babyId = babyId
            copy.uid = copy.uid.isEmpty ? newUid() : copy.uid
            copy.updatedAt = timestamp
            try copy.insert(db)
            return copy.id
        }
    }

    public func updateTreatment(_ treatment: Treatment) throws {
        let timestamp = now()
        try database.write { db in
            var copy = treatment
            copy.updatedAt = timestamp
            try copy.update(db)
        }
    }

    public func deleteTreatment(_ treatment: Treatment) throws { try softDelete(treatment) }

    public func treatment(id: Int64) throws -> Treatment? {
        try database.read { db in try Treatment.fetchOne(db, key: id) }
    }

    /// Active treatments that want a reminder — what the scheduler re-arms from.
    public func treatmentsWithReminders() throws -> [Treatment] {
        try database.read { db in
            try Treatment
                .filter(
                    Column("active") == true
                        && Column("remindersEnabled") == true
                        && Column("deletedAt") == nil
                )
                .fetchAll(db)
        }
    }

    public func addPumping(_ pumping: Pumping) throws {
        try insert(pumping)
    }

    public func deletePumping(_ pumping: Pumping) throws { try softDelete(pumping) }

    public func addWellbeing(_ entry: Wellbeing) throws {
        try database.write { db in
            var copy = entry
            try copy.insert(db)
        }
    }

    public func deleteWellbeing(_ entry: Wellbeing) throws {
        guard let id = entry.id else { return }
        _ = try database.write { db in try Wellbeing.deleteOne(db, key: id) }
    }

    // MARK: - Queries the reminders and the widget need

    public func lastFeedTime(babyId: Int64) throws -> Int64? {
        try database.read { db in
            try Feeding
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("startTime").desc)
                .fetchOne(db)?.startTime
        }
    }

    public func lastFeedForActiveBaby() throws -> Feeding? {
        guard let babyId = try activeBabyIdNow() else { return nil }
        return try database.read { db in
            try Feeding
                .filter(Column("babyId") == babyId && Column("deletedAt") == nil)
                .order(Column("startTime").desc)
                .fetchOne(db)
        }
    }

    /// The most recent breastfeed since `since`, for the widget's "which side
    /// next" line.
    public func lastBreastFeed(babyId: Int64, since: Int64) throws -> Feeding? {
        try database.read { db in
            try Feeding
                .filter(
                    Column("babyId") == babyId
                        && Column("type") == FeedType.BREAST.rawValue
                        && Column("startTime") >= since
                        && Column("deletedAt") == nil
                )
                .order(Column("startTime").desc)
                .fetchOne(db)
        }
    }

    public func lastBreastFeedForActiveBaby(since: Int64) throws -> Feeding? {
        guard let babyId = try activeBabyIdNow() else { return nil }
        return try lastBreastFeed(babyId: babyId, since: since)
    }

    // MARK: - Tombstones

    /// Erases tombstones past the retention window, and with them the soft-deleted
    /// rows they stand for.
    public func compactTombstones() throws {
        let cutoff = now() - Self.tombstoneRetentionMs
        try database.write { db in
            try db.execute(sql: "DELETE FROM tombstone WHERE deletedAt < ?", arguments: [cutoff])
            for table in ["baby", "feeding", "sleep", "diaper", "growth", "treatment", "pumping"] {
                try db.execute(
                    sql: "DELETE FROM \(table) WHERE deletedAt IS NOT NULL AND deletedAt < ?",
                    arguments: [cutoff]
                )
            }
        }
    }

    // MARK: - Plumbing

    /// Stamps and inserts a row that belongs to a baby.
    ///
    /// This is the only place `babyId`, `uid` and `updatedAt` are set. A record
    /// inserted anywhere else is an unstamped row, which then loses every merge.
    private func insert<T: SyncableRecord & BabyScoped>(_ record: T, babyId: Int64) throws {
        let timestamp = now()
        try database.write { db in
            var copy = record
            copy.babyId = babyId
            if copy.uid.isEmpty { copy.uid = newUid() }
            copy.updatedAt = timestamp
            try copy.insert(db)
        }
    }

    /// The same, for a row that belongs to the parent rather than to a baby.
    private func insert<T: SyncableRecord>(_ record: T) throws {
        let timestamp = now()
        try database.write { db in
            var copy = record
            if copy.uid.isEmpty { copy.uid = newUid() }
            copy.updatedAt = timestamp
            try copy.insert(db)
        }
    }

    /// Soft delete: flag the row, keep it (see ``Syncable``).
    ///
    /// `updatedAt` moves too, or the deletion loses to an older edit on the
    /// other phone and the row comes back.
    private func softDelete<T: SyncableRecord>(_ record: T) throws {
        guard record.id != nil else { return }
        let timestamp = now()
        try database.write { db in
            var copy = record
            copy.deletedAt = timestamp
            copy.updatedAt = timestamp
            try copy.update(db)
        }
    }

    private static func activeBaby(_ db: Database) throws -> Baby? {
        guard let activeId = try ParentProfile.fetchOne(db, key: ParentProfile.singletonId)?.activeBabyId
        else { return nil }
        return try Baby
            .filter(Column("id") == activeId && Column("deletedAt") == nil)
            .fetchOne(db)
    }

    private func observe<T>(
        _ fetch: @escaping @Sendable (Database) throws -> T
    ) -> AsyncValueObservation<T> {
        ValueObservation.tracking(fetch).values(in: database)
    }

    /// Reads that follow the active baby, and yield an empty result when there
    /// isn't one — before onboarding, or after the last baby is archived.
    private func observeForActiveBaby<T>(
        _ fetch: @escaping @Sendable (Database, Int64) throws -> [T]
    ) -> AsyncValueObservation<[T]> {
        observe { db in
            guard let babyId = try Self.activeBaby(db)?.id else { return [] }
            return try fetch(db, babyId)
        }
    }
}
