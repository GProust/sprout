import Foundation
import GRDB

// The value model, mirroring `data/local/Entities.kt`.
//
// Enum cases are stored as their *names*, exactly as Room's converters store
// them, and the raw values here are those names. They are not display strings
// and must never be renamed to read better: they are in every user's database
// and in every replica that crosses between two phones.

/// Type of a feeding session.
public enum FeedType: String, Codable, CaseIterable, Sendable { case BREAST, BOTTLE, SOLID }

/// Which breast was used during a breastfeeding session.
public enum BreastSide: String, Codable, CaseIterable, Sendable { case LEFT, RIGHT, BOTH }

/// Predefined stool colours, offered as a colour scale when logging a nappy.
///
/// The order is the one the scale is drawn in: the common healthy colours, then
/// the pale/acholic range the newborn cholestasis cards exist to flag, then the
/// blood-related ones.
public enum StoolColor: String, Codable, CaseIterable, Sendable {
    case YELLOW, GREEN, BROWN, PALE, CLAY, WHITE, BLACK, RED
}

/// Where a batch of expressed milk went. `USED` never entered the stash.
public enum MilkStorage: String, Codable, CaseIterable, Sendable { case FRIDGE, FREEZER, ROOM, USED }

/// How the baby was lying when they were put down. Recorded, never judged
/// (BDR-0014).
public enum SleepPosition: String, Codable, CaseIterable, Sendable { case BACK, SIDE, BELLY }

/// Where the baby slept. `OTHER` carries the parent's own word in `placeNote`.
public enum SleepPlace: String, Codable, CaseIterable, Sendable {
    case OWN_BED, BEDSIDE_COT, PARENTS_BED, ON_A_PARENT, AT_BREAST, OTHER
}

/// Postpartum bleeding (lochia) intensity.
public enum Bleeding: String, Codable, CaseIterable, Sendable { case NONE, LIGHT, MODERATE, HEAVY }

/// Breast comfort state, for a breastfeeding parent.
public enum BreastState: String, Codable, CaseIterable, Sendable { case NORMAL, TENDER, ENGORGED, PAINFUL }

/// How a parent's postpartum recovery feels.
public enum Recovery: String, Codable, CaseIterable, Sendable { case GREAT, GOOD, SORE, PAINFUL }

/// How the baby was delivered — tailors the healing question.
public enum DeliveryType: String, Codable, CaseIterable, Sendable { case VAGINAL, CESAREAN }

extension FeedType: DatabaseValueConvertible {}
extension BreastSide: DatabaseValueConvertible {}
extension StoolColor: DatabaseValueConvertible {}
extension MilkStorage: DatabaseValueConvertible {}
extension SleepPosition: DatabaseValueConvertible {}
extension SleepPlace: DatabaseValueConvertible {}
extension Bleeding: DatabaseValueConvertible {}
extension BreastState: DatabaseValueConvertible {}
extension Recovery: DatabaseValueConvertible {}
extension DeliveryType: DatabaseValueConvertible {}

/// A row partner sync can merge between two phones (ADR-0007).
///
/// - `uid` names the row across devices, since `id` is only a local counter.
/// - `updatedAt` arbitrates two concurrent edits: the later write wins.
/// - `deletedAt` is a tombstone, so a deletion is something we can *tell* the
///   other phone about. Without it the partner's copy resurrects the row at the
///   next merge, forever.
///
/// The parent's own data (``Wellbeing``, ``ParentProfile``) deliberately does not
/// conform: it never leaves the device.
public protocol Syncable {
    var uid: String { get set }
    var updatedAt: Int64 { get set }
    var deletedAt: Int64? { get set }
}

/// A ``Syncable`` that is also a database row, which is every one of them.
///
/// The settable `id` is what lets the repository stamp and soft-delete any of
/// them through one code path instead of seven near-identical ones.
public protocol SyncableRecord: Syncable, MutablePersistableRecord, Codable, Sendable {
    var id: Int64? { get set }
}

/// A row that belongs to one baby, and is therefore scoped to the active one on
/// every read and stamped with it on every write.
///
/// Pumping is deliberately absent: it belongs to the parent (BDR-0007).
public protocol BabyScoped {
    var babyId: Int64 { get set }
}

/// One uninterrupted stretch on a single breast — the unit a live timer records
/// between switches. `side` is only `.LEFT` or `.RIGHT`.
public struct NursingSegment: Equatable, Sendable {
    public var side: BreastSide
    public var startTime: Int64
    public var endTime: Int64

    public init(side: BreastSide, startTime: Int64, endTime: Int64) {
        self.side = side
        self.startTime = startTime
        self.endTime = endTime
    }

    public var durationMs: Int64 { max(0, endTime - startTime) }
}

// MARK: - Records

/// A baby's profile. Several are supported — twins, or siblings over time.
/// `archived` babies are kept but out of the rotation, so their history is not
/// lost to "stop tracking".
public struct Baby: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord {
    public static let databaseTableName = "baby"

    public var id: Int64?
    public var name: String
    public var birthDate: Int64
    public var archived: Bool = false
    /// Per-baby feeding-reminder override. `nil` means "follow the device
    /// setting". The two fields move together in the UI but stay independently
    /// nullable, so the effective value resolves each as `override ?? global`.
    public var feedingReminderEnabled: Bool?
    public var feedingReminderIntervalMinutes: Int?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        name: String,
        birthDate: Int64,
        archived: Bool = false,
        feedingReminderEnabled: Bool? = nil,
        feedingReminderIntervalMinutes: Int? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.birthDate = birthDate
        self.archived = archived
        self.feedingReminderEnabled = feedingReminderEnabled
        self.feedingReminderIntervalMinutes = feedingReminderIntervalMinutes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

public struct Feeding: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord, BabyScoped {
    public static let databaseTableName = "feeding"

    public var id: Int64?
    /// Which baby this belongs to; stamped by the repository on insert.
    public var babyId: Int64 = 0
    public var type: FeedType
    /// Which breast(s) were used; `.BOTH` when the session switched sides.
    public var side: BreastSide?
    public var amountMl: Int?
    public var amountGrams: Int?
    public var startTime: Int64
    public var endTime: Int64?
    public var leftDurationMs: Int64?
    public var rightDurationMs: Int64?
    /// Encoded as Room stores it — see ``NursingSegmentCoding``.
    public var segments: String = ""
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        babyId: Int64 = 0,
        type: FeedType,
        side: BreastSide? = nil,
        amountMl: Int? = nil,
        amountGrams: Int? = nil,
        startTime: Int64,
        endTime: Int64? = nil,
        leftDurationMs: Int64? = nil,
        rightDurationMs: Int64? = nil,
        segments: [NursingSegment] = [],
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.babyId = babyId
        self.type = type
        self.side = side
        self.amountMl = amountMl
        self.amountGrams = amountGrams
        self.startTime = startTime
        self.endTime = endTime
        self.leftDurationMs = leftDurationMs
        self.rightDurationMs = rightDurationMs
        self.segments = NursingSegmentCoding.encode(segments)
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public var nursingSegments: [NursingSegment] { NursingSegmentCoding.decode(segments) }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

public struct Sleep: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord, BabyScoped {
    public static let databaseTableName = "sleep"

    public var id: Int64?
    public var babyId: Int64 = 0
    public var startTime: Int64
    /// `nil` while the baby is still asleep.
    public var endTime: Int64?
    public var position: SleepPosition?
    public var place: SleepPlace?
    /// The parent's own word for the place, when ``place`` is `.OTHER`.
    public var placeNote: String?
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        babyId: Int64 = 0,
        startTime: Int64,
        endTime: Int64? = nil,
        position: SleepPosition? = nil,
        place: SleepPlace? = nil,
        placeNote: String? = nil,
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.babyId = babyId
        self.startTime = startTime
        self.endTime = endTime
        self.position = position
        self.place = place
        self.placeNote = placeNote
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public var isOngoing: Bool { endTime == nil }
    public var durationMs: Int64? { endTime.map { max(0, $0 - startTime) } }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

public struct Diaper: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord, BabyScoped {
    public static let databaseTableName = "diaper"

    public var id: Int64?
    public var babyId: Int64 = 0
    public var time: Int64
    public var wet: Bool = false
    public var dirty: Bool = false
    public var stoolColor: StoolColor?
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        babyId: Int64 = 0,
        time: Int64,
        wet: Bool = false,
        dirty: Bool = false,
        stoolColor: StoolColor? = nil,
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.babyId = babyId
        self.time = time
        self.wet = wet
        self.dirty = dirty
        self.stoolColor = stoolColor
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

public struct Growth: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord, BabyScoped {
    public static let databaseTableName = "growth"

    public var id: Int64?
    public var babyId: Int64 = 0
    public var time: Int64
    public var weightGrams: Int?
    public var heightMm: Int?
    public var headMm: Int?
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        babyId: Int64 = 0,
        time: Int64,
        weightGrams: Int? = nil,
        heightMm: Int? = nil,
        headMm: Int? = nil,
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.babyId = babyId
        self.time = time
        self.weightGrams = weightGrams
        self.heightMm = heightMm
        self.headMm = headMm
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

/// A recurring treatment, e.g. *Vitamin D, 1 drop, every day for a year*.
public struct Treatment: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord, BabyScoped {
    public static let databaseTableName = "treatment"

    public var id: Int64?
    public var babyId: Int64 = 0
    public var name: String
    /// Optional dose description, e.g. "400 IU" or "1 drop".
    public var dose: String?
    /// Days between doses: 1 = daily, 7 = weekly, N = every N days.
    public var intervalDays: Int = 1
    /// Reminder times on a dosing day, minutes since midnight, comma-separated
    /// as Room stores them.
    public var timesOfDay: String = "540"
    public var startDate: Int64
    /// Last dosing day, inclusive; `nil` = ongoing.
    public var endDate: Int64?
    public var remindersEnabled: Bool = true
    /// False once stopped; kept out of the active list but not deleted.
    public var active: Bool = true
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        babyId: Int64 = 0,
        name: String,
        dose: String? = nil,
        intervalDays: Int = 1,
        timesOfDay: [Int] = [9 * 60],
        startDate: Int64,
        endDate: Int64? = nil,
        remindersEnabled: Bool = true,
        active: Bool = true,
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.babyId = babyId
        self.name = name
        self.dose = dose
        self.intervalDays = intervalDays
        self.timesOfDay = IntListCoding.encode(timesOfDay)
        self.startDate = startDate
        self.endDate = endDate
        self.remindersEnabled = remindersEnabled
        self.active = active
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    public var reminderTimes: [Int] { IntListCoding.decode(timesOfDay) }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

/// A pumping session. Belongs to the parent, not to a baby (BDR-0007), which is
/// why there is no `babyId`.
public struct Pumping: Codable, FetchableRecord, Identifiable, Equatable, SyncableRecord {
    public static let databaseTableName = "pumping"

    public var id: Int64?
    public var time: Int64
    public var amountMl: Int
    public var side: BreastSide?
    public var storage: MilkStorage = .FRIDGE
    public var notes: String?
    public var uid: String = newUid()
    public var updatedAt: Int64 = 0
    public var deletedAt: Int64?

    public init(
        id: Int64? = nil,
        time: Int64,
        amountMl: Int,
        side: BreastSide? = nil,
        storage: MilkStorage = .FRIDGE,
        notes: String? = nil,
        uid: String = newUid(),
        updatedAt: Int64 = 0,
        deletedAt: Int64? = nil
    ) {
        self.id = id
        self.time = time
        self.amountMl = amountMl
        self.side = side
        self.storage = storage
        self.notes = notes
        self.uid = uid
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }

    /// Whether this batch is still in the stash. `.USED` milk stays in the
    /// history but is out of the count.
    public var isStashed: Bool { storage != .USED }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

/// The parent's own daily check-in. Deliberately **not** ``Syncable`` — this is
/// the one thing that never leaves the phone, not even to a partner's.
public struct Wellbeing: Codable, FetchableRecord, MutablePersistableRecord, Identifiable, Equatable, Sendable {
    public static let databaseTableName = "wellbeing"

    public var id: Int64?
    public var time: Int64
    /// 1–5.
    public var mood: Int
    public var bleeding: Bleeding?
    public var recovery: Recovery?
    public var breast: BreastState?
    public var notes: String?

    public init(
        id: Int64? = nil,
        time: Int64,
        mood: Int,
        bleeding: Bleeding? = nil,
        recovery: Recovery? = nil,
        breast: BreastState? = nil,
        notes: String? = nil
    ) {
        self.id = id
        self.time = time
        self.mood = mood
        self.bleeding = bleeding
        self.recovery = recovery
        self.breast = breast
        self.notes = notes
    }

    public mutating func didInsert(_ inserted: InsertionSuccess) { id = inserted.rowID }
}

/// The owner of this device. A single row, id 1. Also **not** ``Syncable``.
///
/// Capability rather than role (BDR-0001): `gaveBirth` and `breastfeeding` are
/// what decide which questions the check-in asks, not a label like "mother".
public struct ParentProfile: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "parent_profile"
    public static let singletonId: Int64 = 1

    public var id: Int64 = ParentProfile.singletonId
    public var name: String
    public var gaveBirth: Bool
    public var breastfeeding: Bool
    public var deliveryType: DeliveryType?
    public var askHealing: Bool = true
    public var askBleeding: Bool = true
    public var askBreasts: Bool = true
    public var trackWellbeing: Bool = true
    public var lastCheckIn: Int64?
    public var activeBabyId: Int64?

    public init(
        id: Int64 = ParentProfile.singletonId,
        name: String,
        gaveBirth: Bool,
        breastfeeding: Bool,
        deliveryType: DeliveryType? = nil,
        askHealing: Bool = true,
        askBleeding: Bool = true,
        askBreasts: Bool = true,
        trackWellbeing: Bool = true,
        lastCheckIn: Int64? = nil,
        activeBabyId: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.gaveBirth = gaveBirth
        self.breastfeeding = breastfeeding
        self.deliveryType = deliveryType
        self.askHealing = askHealing
        self.askBleeding = askBleeding
        self.askBreasts = askBreasts
        self.trackWellbeing = trackWellbeing
        self.lastCheckIn = lastCheckIn
        self.activeBabyId = activeBabyId
    }
}

/// What is left of a row erased outright.
///
/// "Permanently delete a baby" removes the rows and keeps only their uids here,
/// so the deletion still travels to the other phones without the data lingering
/// on this one. Compacted after ``SproutRepository/tombstoneRetentionMs``.
public struct Tombstone: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {
    public static let databaseTableName = "tombstone"

    public var uid: String
    public var entity: String
    public var deletedAt: Int64

    public init(uid: String, entity: String, deletedAt: Int64) {
        self.uid = uid
        self.entity = entity
        self.deletedAt = deletedAt
    }
}
