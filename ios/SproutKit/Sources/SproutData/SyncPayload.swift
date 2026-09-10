import Foundation
import SproutKit

/// What one phone sends the other: a replica of everything it is willing to
/// share, as a versioned document (ADR-0007, `spec/wire-format.md` §7).
/// From `data/sync/SyncPayload.kt`.
///
/// Two things are deliberately absent.
///
/// **Local ids never travel.** `id` is a per-device counter, so sending it would
/// be sending a number that means something else on the other phone. Rows are
/// named by `uid` and nothing else, and a log points at its baby by that baby's
/// uid — resolved back to a local id at merge time.
///
/// **The parent's own data never travels**: no wellbeing, no parent profile.
/// `pumpings` is here only when the sender's stash switch is on (ADR-0008).
///
/// Soft-deleted rows *are* included, flagged. That is how a deletion reaches the
/// other phone instead of being undone by it.
public struct SyncPayload: Equatable, Sendable {
    /// Which baby's household this replica belongs to; a payload from another is
    /// refused.
    public var householdId: String
    /// Which device produced it — for the merge summary, and to ignore our own.
    public var deviceId: String
    /// What to call that device in the household list (ADR-0009).
    ///
    /// Optional: replicas written before households existed do not carry it, and
    /// merge exactly as they did.
    public var deviceName: String = ""
    public var createdAt: Int64
    /// The database schema the sender was on. A newer one is refused rather than
    /// half-applied.
    public var schemaVersion: Int
    public var babies: [Baby] = []
    public var feedings: [BabyScoped<Feeding>] = []
    public var sleeps: [BabyScoped<Sleep>] = []
    public var diapers: [BabyScoped<Diaper>] = []
    public var growth: [BabyScoped<Growth>] = []
    public var treatments: [BabyScoped<Treatment>] = []
    public var pumpings: [Pumping] = []
    public var tombstones: [Tombstone] = []

    public init(
        householdId: String,
        deviceId: String,
        deviceName: String = "",
        createdAt: Int64,
        schemaVersion: Int
    ) {
        self.householdId = householdId
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.createdAt = createdAt
        self.schemaVersion = schemaVersion
    }

    /// How many rows this payload is offering, tombstones included.
    public var rowCount: Int {
        babies.count + feedings.count + sleeps.count + diapers.count
            + growth.count + treatments.count + pumpings.count + tombstones.count
    }
}

/// A log with the uid — never the local id — of the baby it belongs to.
public struct BabyScoped<Row: Equatable & Sendable>: Equatable, Sendable {
    public let babyUid: String
    public let row: Row

    public init(babyUid: String, row: Row) {
        self.babyUid = babyUid
        self.row = row
    }
}

/// The payload wire format, versioned from the first field so that a phone which
/// has not been updated can say "I don't understand this" instead of applying
/// half of it. Bump only for a change older readers cannot cope with.
public let syncFormatVersion = 1

/// Raised when a payload cannot be read; the UI turns each case into its own
/// sentence, because "your partner's phone is newer than yours" and "this file is
/// damaged" are different things to be told.
public enum SyncPayloadError: Error, Equatable {
    /// Written by a newer version of Sprout than this one.
    case tooNew(formatVersion: Int, schemaVersion: Int)
    /// Not a Sprout replica at all, or damaged in transit.
    case unreadable(String)
    /// A replica from a phone this one is not paired with.
    case wrongHousehold
}

/// Serialises a payload to the bytes that get encrypted and sent.
public enum SyncPayloadCodec {

    // MARK: - Encoding

    public static func encode(_ payload: SyncPayload) throws -> Data {
        var root: [String: Any] = [
            "formatVersion": syncFormatVersion,
            "schemaVersion": payload.schemaVersion,
            "householdId": payload.householdId,
            "deviceId": payload.deviceId,
            "createdAt": payload.createdAt,
            "babies": payload.babies.map(baby),
            "feedings": payload.feedings.map { scoped($0, feeding) },
            "sleeps": payload.sleeps.map { scoped($0, sleep) },
            "diapers": payload.diapers.map { scoped($0, diaper) },
            "growth": payload.growth.map { scoped($0, growth) },
            "treatments": payload.treatments.map { scoped($0, treatment) },
            "pumpings": payload.pumpings.map(pumping),
            "tombstones": payload.tombstones.map(tombstone),
        ]
        if !payload.deviceName.trimmingCharacters(in: .whitespaces).isEmpty {
            root["deviceName"] = payload.deviceName
        }
        // `.sortedKeys` so two encodings of one payload are the same bytes;
        // JSON object order carries no meaning, and a reader that depended on it
        // would already be broken against Android's.
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    // MARK: - Decoding

    /// Reads a payload back.
    ///
    /// - Throws: ``SyncPayloadError/tooNew(formatVersion:schemaVersion:)`` when it
    ///   comes from a newer Sprout, ``SyncPayloadError/unreadable(_:)`` when it is
    ///   not a replica or is damaged.
    public static func decode(_ bytes: Data, currentSchemaVersion: Int) throws -> SyncPayload {
        guard let text = String(data: bytes, encoding: .utf8) else {
            throw SyncPayloadError.unreadable("not a Sprout replica: not UTF-8")
        }
        // **Before parsing, not around it.** Nesting deep enough to exhaust the
        // stack is not something a `catch` can hold on either platform, so the
        // depth is counted on the text first (see `SyncLimits`).
        if SyncLimits.exceedsMaxJsonDepth(text) {
            throw SyncPayloadError.unreadable("not a Sprout replica: implausibly nested")
        }

        let parsed = try? JSONSerialization.jsonObject(with: bytes)
        guard let root = parsed as? [String: Any] else {
            throw SyncPayloadError.unreadable("not a Sprout replica")
        }

        let formatVersion = root.int("formatVersion") ?? -1
        let schemaVersion = root.int("schemaVersion") ?? -1
        guard formatVersion >= 0, schemaVersion >= 0 else {
            throw SyncPayloadError.unreadable("missing version")
        }
        guard formatVersion <= syncFormatVersion, schemaVersion <= currentSchemaVersion else {
            throw SyncPayloadError.tooNew(formatVersion: formatVersion, schemaVersion: schemaVersion)
        }

        guard let householdId = root["householdId"] as? String,
              let deviceId = root["deviceId"] as? String,
              let createdAt = root.int64("createdAt")
        else {
            throw SyncPayloadError.unreadable("damaged replica: missing an identifying field")
        }

        var payload = SyncPayload(
            householdId: householdId,
            deviceId: deviceId,
            deviceName: root["deviceName"] as? String ?? "",
            createdAt: createdAt,
            schemaVersion: schemaVersion
        )

        do {
            payload.babies = try root.rows("babies", babyFrom)
            payload.feedings = try root.rows("feedings") { try scopedFrom($0, feedingFrom) }
            payload.sleeps = try root.rows("sleeps") { try scopedFrom($0, sleepFrom) }
            payload.diapers = try root.rows("diapers") { try scopedFrom($0, diaperFrom) }
            payload.growth = try root.rows("growth") { try scopedFrom($0, growthFrom) }
            payload.treatments = try root.rows("treatments") { try scopedFrom($0, treatmentFrom) }
            payload.pumpings = try root.rows("pumpings", pumpingFrom)
            payload.tombstones = try root.rows("tombstones", tombstoneFrom)
        } catch let error as SyncPayloadError {
            throw error
        } catch {
            throw SyncPayloadError.unreadable("damaged replica: \(error)")
        }
        return payload
    }
}

// MARK: - Rows, one per entity
//
// Every writer omits a key rather than writing `null`, and every reader treats
// absent and null alike. That is not a style choice: `org.json`'s
// `put(key, null)` *removes* the key, so a document with an explicit null is one
// Android would never write — and a reader that required the key would refuse a
// document Android reads perfectly well.

private extension SyncPayloadCodec {

    static func sync(_ row: some Syncable) -> [String: Any] {
        var json: [String: Any] = ["uid": row.uid, "updatedAt": row.updatedAt]
        json.put("deletedAt", row.deletedAt)
        return json
    }

    static func scoped<Row>(
        _ scoped: BabyScoped<Row>,
        _ encode: (Row) -> [String: Any]
    ) -> [String: Any] {
        var json = encode(scoped.row)
        json["babyUid"] = scoped.babyUid
        return json
    }

    static func scopedFrom<Row>(
        _ json: [String: Any],
        _ decode: ([String: Any]) throws -> Row
    ) throws -> BabyScoped<Row> {
        guard let babyUid = json["babyUid"] as? String else {
            throw SyncPayloadError.unreadable("a log with no baby")
        }
        return BabyScoped(babyUid: babyUid, row: try decode(json))
    }

    // MARK: Baby

    static func baby(_ row: Baby) -> [String: Any] {
        var json = sync(row)
        json["name"] = row.name
        json["birthDate"] = row.birthDate
        json["archived"] = row.archived
        json.put("feedingReminderEnabled", row.feedingReminderEnabled)
        json.put("feedingReminderIntervalMinutes", row.feedingReminderIntervalMinutes)
        return json
    }

    static func babyFrom(_ json: [String: Any]) throws -> Baby {
        Baby(
            name: try json.required("name"),
            birthDate: try json.requiredInt64("birthDate"),
            archived: json.bool("archived") ?? false,
            feedingReminderEnabled: json.bool("feedingReminderEnabled"),
            feedingReminderIntervalMinutes: json.int("feedingReminderIntervalMinutes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
    }

    // MARK: Feeding

    static func feeding(_ row: Feeding) -> [String: Any] {
        var json = sync(row)
        json["type"] = row.type.rawValue
        json.put("side", row.side?.rawValue)
        json.put("amountMl", row.amountMl)
        json.put("amountGrams", row.amountGrams)
        json["startTime"] = row.startTime
        json.put("endTime", row.endTime)
        json.put("leftDurationMs", row.leftDurationMs)
        json.put("rightDurationMs", row.rightDurationMs)
        // Packed as Room stores it, not as an array: `SIDE,start,end` joined by
        // `;`. The two platforms have to agree on the string, not on a shape.
        json["segments"] = row.segments
        json.put("notes", row.notes)
        return json
    }

    static func feedingFrom(_ json: [String: Any]) throws -> Feeding {
        var row = Feeding(
            type: try json.enumeration("type", FeedType.self),
            side: try json.optionalEnumeration("side", BreastSide.self),
            amountMl: json.int("amountMl"),
            amountGrams: json.int("amountGrams"),
            startTime: try json.requiredInt64("startTime"),
            endTime: json.int64("endTime"),
            leftDurationMs: json.int64("leftDurationMs"),
            rightDurationMs: json.int64("rightDurationMs"),
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
        row.segments = json.string("segments") ?? ""
        return row
    }

    // MARK: Sleep

    static func sleep(_ row: Sleep) -> [String: Any] {
        var json = sync(row)
        json["startTime"] = row.startTime
        json.put("endTime", row.endTime)
        json.put("position", row.position?.rawValue)
        json.put("place", row.place?.rawValue)
        json.put("placeNote", row.placeNote)
        json.put("notes", row.notes)
        return json
    }

    static func sleepFrom(_ json: [String: Any]) throws -> Sleep {
        Sleep(
            startTime: try json.requiredInt64("startTime"),
            endTime: json.int64("endTime"),
            // Absent on replicas written before sleeps recorded any of this,
            // which merge exactly as they did — as sleeps with nothing noted.
            position: try json.optionalEnumeration("position", SleepPosition.self),
            place: try json.optionalEnumeration("place", SleepPlace.self),
            placeNote: json.string("placeNote"),
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
    }

    // MARK: Diaper

    static func diaper(_ row: Diaper) -> [String: Any] {
        var json = sync(row)
        json["time"] = row.time
        json["wet"] = row.wet
        json["dirty"] = row.dirty
        json.put("stoolColor", row.stoolColor?.rawValue)
        json.put("notes", row.notes)
        return json
    }

    static func diaperFrom(_ json: [String: Any]) throws -> Diaper {
        Diaper(
            time: try json.requiredInt64("time"),
            wet: json.bool("wet") ?? false,
            dirty: json.bool("dirty") ?? false,
            stoolColor: try json.optionalEnumeration("stoolColor", StoolColor.self),
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
    }

    // MARK: Growth

    static func growth(_ row: Growth) -> [String: Any] {
        var json = sync(row)
        json["time"] = row.time
        json.put("weightGrams", row.weightGrams)
        json.put("heightMm", row.heightMm)
        json.put("headMm", row.headMm)
        json.put("notes", row.notes)
        return json
    }

    static func growthFrom(_ json: [String: Any]) throws -> Growth {
        Growth(
            time: try json.requiredInt64("time"),
            weightGrams: json.int("weightGrams"),
            heightMm: json.int("heightMm"),
            headMm: json.int("headMm"),
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
    }

    // MARK: Treatment

    static func treatment(_ row: Treatment) -> [String: Any] {
        var json = sync(row)
        json["name"] = row.name
        json.put("dose", row.dose)
        json["intervalDays"] = row.intervalDays
        // Minutes since midnight joined by `,` — Room's packing again.
        json["timesOfDay"] = row.timesOfDay
        json["startDate"] = row.startDate
        json.put("endDate", row.endDate)
        json["remindersEnabled"] = row.remindersEnabled
        json["active"] = row.active
        json.put("notes", row.notes)
        return json
    }

    static func treatmentFrom(_ json: [String: Any]) throws -> Treatment {
        var row = Treatment(
            name: try json.required("name"),
            dose: json.string("dose"),
            intervalDays: json.int("intervalDays") ?? 1,
            startDate: try json.requiredInt64("startDate"),
            endDate: json.int64("endDate"),
            remindersEnabled: json.bool("remindersEnabled") ?? true,
            active: json.bool("active") ?? true,
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
        row.timesOfDay = json.string("timesOfDay") ?? ""
        return row
    }

    // MARK: Pumping

    static func pumping(_ row: Pumping) -> [String: Any] {
        var json = sync(row)
        json["time"] = row.time
        json["amountMl"] = row.amountMl
        json.put("side", row.side?.rawValue)
        json["storage"] = row.storage.rawValue
        json.put("notes", row.notes)
        return json
    }

    static func pumpingFrom(_ json: [String: Any]) throws -> Pumping {
        Pumping(
            time: try json.requiredInt64("time"),
            amountMl: json.int("amountMl") ?? 0,
            side: try json.optionalEnumeration("side", BreastSide.self),
            storage: try json.enumeration("storage", MilkStorage.self),
            notes: json.string("notes"),
            uid: try json.required("uid"),
            updatedAt: try json.requiredInt64("updatedAt"),
            deletedAt: json.int64("deletedAt")
        )
    }

    // MARK: Tombstone

    static func tombstone(_ row: Tombstone) -> [String: Any] {
        ["uid": row.uid, "entity": row.entity, "deletedAt": row.deletedAt]
    }

    static func tombstoneFrom(_ json: [String: Any]) throws -> Tombstone {
        Tombstone(
            uid: try json.required("uid"),
            entity: try json.required("entity"),
            deletedAt: try json.requiredInt64("deletedAt")
        )
    }
}

// MARK: - Reading a loosely-typed document
//
// `JSONSerialization` hands back `Any`, and a number can arrive as `Int`,
// `Int64`, `Double` or `NSNumber` depending on how it was written. These read
// through all of that rather than trusting one shape.

private extension Dictionary where Key == String, Value == Any {

    /// Absent and `NSNull` are the same thing — see the note above the rows.
    func value(_ key: String) -> Any? {
        guard let raw = self[key], !(raw is NSNull) else { return nil }
        return raw
    }

    func string(_ key: String) -> String? { value(key) as? String }

    func bool(_ key: String) -> Bool? {
        if let flag = value(key) as? Bool { return flag }
        if let number = value(key) as? NSNumber { return number.boolValue }
        return nil
    }

    func int(_ key: String) -> Int? { (value(key) as? NSNumber)?.intValue }

    func int64(_ key: String) -> Int64? { (value(key) as? NSNumber)?.int64Value }

    func required(_ key: String) throws -> String {
        guard let text = string(key) else {
            throw SyncPayloadError.unreadable("damaged replica: no \(key)")
        }
        return text
    }

    func requiredInt64(_ key: String) throws -> Int64 {
        guard let number = int64(key) else {
            throw SyncPayloadError.unreadable("damaged replica: no \(key)")
        }
        return number
    }

    /// An enum travels as its **name**, never an ordinal: a reordered enum would
    /// silently rewrite history on the other phone.
    func enumeration<T: RawRepresentable>(_ key: String, _: T.Type) throws -> T
    where T.RawValue == String {
        guard let name = string(key), let parsed = T(rawValue: name) else {
            throw SyncPayloadError.unreadable("damaged replica: bad \(key)")
        }
        return parsed
    }

    /// The same, but a missing key is a missing value rather than a fault — an
    /// unknown *name*, though, is still damage.
    func optionalEnumeration<T: RawRepresentable>(_ key: String, _: T.Type) throws -> T?
    where T.RawValue == String {
        guard let name = string(key) else { return nil }
        guard let parsed = T(rawValue: name) else {
            throw SyncPayloadError.unreadable("damaged replica: bad \(key)")
        }
        return parsed
    }

    /// An absent list is an empty one, which is what lets a field be added
    /// without a version bump.
    func rows<T>(_ key: String, _ decode: ([String: Any]) throws -> T) throws -> [T] {
        guard let array = value(key) as? [[String: Any]] else { return [] }
        return try array.map(decode)
    }
}

private extension Dictionary where Key == String, Value == Any {
    /// Writes `value` only when there is one. A `null` in the document is a key
    /// Android would never have written.
    mutating func put(_ key: String, _ value: Any?) {
        guard let value else { return }
        self[key] = value
    }
}
