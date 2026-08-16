package com.gproust.sprout.data.sync

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.Converters
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.data.local.TombstoneEntity
import com.gproust.sprout.data.local.TreatmentEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one phone sends the other: a replica of everything it is willing to
 * share, as a versioned document (ADR-0007).
 *
 * Two things are deliberately absent.
 *
 * **Local ids never travel.** `id` is a per-device counter, so sending it would
 * be sending a number that means something else on the other phone. Rows are
 * named by [Syncable.uid][com.gproust.sprout.data.local.Syncable.uid] and
 * nothing else, and a log points at its baby by that baby's uid — resolved back
 * to a local id at merge time.
 *
 * **The parent's own data never travels**: no `wellbeing`, no `parent_profile`.
 * `pumping` is here only when the sender's stash switch is on (ADR-0008).
 *
 * Soft-deleted rows *are* included, flagged. That is how a deletion reaches the
 * other phone instead of being undone by it.
 */
data class SyncPayload(
    /** Which baby's household this replica belongs to; a payload from another is refused. */
    val householdId: String,
    /** Which device produced it — for the merge summary, and to ignore our own replica. */
    val deviceId: String,
    /**
     * What to call that device in the household list — the sender's own name,
     * so a phone can say "Mamie's phone" instead of a UUID (ADR-0009).
     *
     * Optional: replicas written before households existed don't carry it, and
     * merge exactly as they did.
     */
    val deviceName: String = "",
    val createdAt: Long,
    /** The Room schema the sender was on. A newer one is refused rather than half-applied. */
    val schemaVersion: Int,
    val babies: List<BabyEntity> = emptyList(),
    val feedings: List<BabyScoped<FeedingEntity>> = emptyList(),
    val sleeps: List<BabyScoped<SleepEntity>> = emptyList(),
    val diapers: List<BabyScoped<DiaperEntity>> = emptyList(),
    val growth: List<BabyScoped<GrowthEntity>> = emptyList(),
    val treatments: List<BabyScoped<TreatmentEntity>> = emptyList(),
    val pumpings: List<PumpingEntity> = emptyList(),
    val tombstones: List<TombstoneEntity> = emptyList(),
) {
    /** How many rows this payload is offering, tombstones included. */
    val rowCount: Int
        get() = babies.size + feedings.size + sleeps.size + diapers.size +
            growth.size + treatments.size + pumpings.size + tombstones.size
}

/** A log with the uid — never the local id — of the baby it belongs to. */
data class BabyScoped<T>(val babyUid: String, val row: T)

/**
 * The payload wire format, versioned from the first byte so that a phone which
 * has not been updated can say "I don't understand this" instead of applying
 * half of it. Bump only for a change older readers cannot cope with.
 */
const val SYNC_FORMAT_VERSION: Int = 1

/** Raised when a payload cannot be read; the UI turns each case into its own sentence. */
sealed class SyncPayloadException(message: String) : Exception(message) {
    /** Written by a newer version of Sprout than this one. */
    class TooNew(val formatVersion: Int, val schemaVersion: Int) :
        SyncPayloadException("payload format $formatVersion / schema $schemaVersion is newer than this app")

    /** Not a Sprout replica at all, or damaged in transit. */
    class Unreadable(message: String) : SyncPayloadException(message)

    /** A replica from a phone this one is not paired with. */
    class WrongHousehold : SyncPayloadException("payload belongs to another household")
}

/** Serializes a payload to the bytes that get encrypted and sent. */
object SyncPayloadCodec {

    private val converters = Converters()

    fun encode(payload: SyncPayload): ByteArray = JSONObject().apply {
        put("formatVersion", SYNC_FORMAT_VERSION)
        put("schemaVersion", payload.schemaVersion)
        put("householdId", payload.householdId)
        put("deviceId", payload.deviceId)
        if (payload.deviceName.isNotBlank()) put("deviceName", payload.deviceName)
        put("createdAt", payload.createdAt)
        put("babies", payload.babies.map(::babyToJson).toJsonArray())
        put("feedings", payload.feedings.map { it.toJson(::feedingToJson) }.toJsonArray())
        put("sleeps", payload.sleeps.map { it.toJson(::sleepToJson) }.toJsonArray())
        put("diapers", payload.diapers.map { it.toJson(::diaperToJson) }.toJsonArray())
        put("growth", payload.growth.map { it.toJson(::growthToJson) }.toJsonArray())
        put("treatments", payload.treatments.map { it.toJson(::treatmentToJson) }.toJsonArray())
        put("pumpings", payload.pumpings.map(::pumpingToJson).toJsonArray())
        put("tombstones", payload.tombstones.map(::tombstoneToJson).toJsonArray())
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * Reads a payload back.
     *
     * @throws SyncPayloadException.TooNew when it comes from a newer Sprout.
     * @throws SyncPayloadException.Unreadable when it is not a replica, or is damaged.
     */
    fun decode(bytes: ByteArray, currentSchemaVersion: Int): SyncPayload {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncPayloadException.Unreadable("not a Sprout replica: ${e.message}")
        }
        val formatVersion = root.optInt("formatVersion", -1)
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (formatVersion < 0 || schemaVersion < 0) {
            throw SyncPayloadException.Unreadable("missing version")
        }
        if (formatVersion > SYNC_FORMAT_VERSION || schemaVersion > currentSchemaVersion) {
            throw SyncPayloadException.TooNew(formatVersion, schemaVersion)
        }
        return try {
            SyncPayload(
                householdId = root.getString("householdId"),
                deviceId = root.getString("deviceId"),
                deviceName = root.optString("deviceName"),
                createdAt = root.getLong("createdAt"),
                schemaVersion = schemaVersion,
                babies = root.list("babies", ::babyFromJson),
                feedings = root.list("feedings") { it.babyScoped(::feedingFromJson) },
                sleeps = root.list("sleeps") { it.babyScoped(::sleepFromJson) },
                diapers = root.list("diapers") { it.babyScoped(::diaperFromJson) },
                growth = root.list("growth") { it.babyScoped(::growthFromJson) },
                treatments = root.list("treatments") { it.babyScoped(::treatmentFromJson) },
                pumpings = root.list("pumpings", ::pumpingFromJson),
                tombstones = root.list("tombstones", ::tombstoneFromJson),
            )
        } catch (e: SyncPayloadException) {
            throw e
        } catch (e: Exception) {
            throw SyncPayloadException.Unreadable("damaged replica: ${e.message}")
        }
    }

    // --- rows -------------------------------------------------------------

    private fun babyToJson(baby: BabyEntity) = JSONObject().apply {
        putSync(baby.uid, baby.updatedAt, baby.deletedAt)
        put("name", baby.name)
        put("birthDate", baby.birthDate)
        put("archived", baby.archived)
        putOrNull("feedingReminderEnabled", baby.feedingReminderEnabled)
        putOrNull("feedingReminderIntervalMinutes", baby.feedingReminderIntervalMinutes)
    }

    private fun babyFromJson(o: JSONObject) = BabyEntity(
        name = o.getString("name"),
        birthDate = o.getLong("birthDate"),
        archived = o.getBoolean("archived"),
        feedingReminderEnabled = o.booleanOrNull("feedingReminderEnabled"),
        feedingReminderIntervalMinutes = o.intOrNull("feedingReminderIntervalMinutes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun feedingToJson(entity: FeedingEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("type", entity.type.name)
        putOrNull("side", entity.side?.name)
        putOrNull("amountMl", entity.amountMl)
        put("startTime", entity.startTime)
        putOrNull("endTime", entity.endTime)
        putOrNull("leftDurationMs", entity.leftDurationMs)
        putOrNull("rightDurationMs", entity.rightDurationMs)
        put("segments", converters.nursingSegmentsToString(entity.segments))
        putOrNull("notes", entity.notes)
    }

    private fun feedingFromJson(o: JSONObject) = FeedingEntity(
        type = FeedType.valueOf(o.getString("type")),
        side = o.stringOrNull("side")?.let { BreastSide.valueOf(it) },
        amountMl = o.intOrNull("amountMl"),
        startTime = o.getLong("startTime"),
        endTime = o.longOrNull("endTime"),
        leftDurationMs = o.longOrNull("leftDurationMs"),
        rightDurationMs = o.longOrNull("rightDurationMs"),
        segments = converters.stringToNursingSegments(o.optString("segments")),
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun sleepToJson(entity: SleepEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("startTime", entity.startTime)
        putOrNull("endTime", entity.endTime)
        putOrNull("notes", entity.notes)
    }

    private fun sleepFromJson(o: JSONObject) = SleepEntity(
        startTime = o.getLong("startTime"),
        endTime = o.longOrNull("endTime"),
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun diaperToJson(entity: DiaperEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("time", entity.time)
        put("wet", entity.wet)
        put("dirty", entity.dirty)
        putOrNull("stoolColor", entity.stoolColor?.name)
        putOrNull("notes", entity.notes)
    }

    private fun diaperFromJson(o: JSONObject) = DiaperEntity(
        time = o.getLong("time"),
        wet = o.getBoolean("wet"),
        dirty = o.getBoolean("dirty"),
        stoolColor = o.stringOrNull("stoolColor")?.let { StoolColor.valueOf(it) },
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun growthToJson(entity: GrowthEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("time", entity.time)
        putOrNull("weightGrams", entity.weightGrams)
        putOrNull("heightMm", entity.heightMm)
        putOrNull("headMm", entity.headMm)
        putOrNull("notes", entity.notes)
    }

    private fun growthFromJson(o: JSONObject) = GrowthEntity(
        time = o.getLong("time"),
        weightGrams = o.intOrNull("weightGrams"),
        heightMm = o.intOrNull("heightMm"),
        headMm = o.intOrNull("headMm"),
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun treatmentToJson(entity: TreatmentEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("name", entity.name)
        putOrNull("dose", entity.dose)
        put("intervalDays", entity.intervalDays)
        put("timesOfDay", converters.intListToString(entity.timesOfDay))
        put("startDate", entity.startDate)
        putOrNull("endDate", entity.endDate)
        put("remindersEnabled", entity.remindersEnabled)
        put("active", entity.active)
        putOrNull("notes", entity.notes)
    }

    private fun treatmentFromJson(o: JSONObject) = TreatmentEntity(
        name = o.getString("name"),
        dose = o.stringOrNull("dose"),
        intervalDays = o.getInt("intervalDays"),
        timesOfDay = converters.stringToIntList(o.optString("timesOfDay")).orEmpty(),
        startDate = o.getLong("startDate"),
        endDate = o.longOrNull("endDate"),
        remindersEnabled = o.getBoolean("remindersEnabled"),
        active = o.getBoolean("active"),
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun pumpingToJson(entity: PumpingEntity) = JSONObject().apply {
        putSync(entity.uid, entity.updatedAt, entity.deletedAt)
        put("time", entity.time)
        put("amountMl", entity.amountMl)
        putOrNull("side", entity.side?.name)
        put("storage", entity.storage.name)
        putOrNull("notes", entity.notes)
    }

    private fun pumpingFromJson(o: JSONObject) = PumpingEntity(
        time = o.getLong("time"),
        amountMl = o.getInt("amountMl"),
        side = o.stringOrNull("side")?.let { BreastSide.valueOf(it) },
        storage = MilkStorage.valueOf(o.getString("storage")),
        notes = o.stringOrNull("notes"),
        uid = o.getString("uid"),
        updatedAt = o.getLong("updatedAt"),
        deletedAt = o.longOrNull("deletedAt"),
    )

    private fun tombstoneToJson(entity: TombstoneEntity) = JSONObject().apply {
        put("uid", entity.uid)
        put("entity", entity.entity)
        put("deletedAt", entity.deletedAt)
    }

    private fun tombstoneFromJson(o: JSONObject) = TombstoneEntity(
        uid = o.getString("uid"),
        entity = o.getString("entity"),
        deletedAt = o.getLong("deletedAt"),
    )

    // --- JSON helpers -----------------------------------------------------

    private fun JSONObject.putSync(uid: String, updatedAt: Long, deletedAt: Long?) {
        put("uid", uid)
        put("updatedAt", updatedAt)
        putOrNull("deletedAt", deletedAt)
    }

    /** `put(key, null)` *removes* a key in org.json, so absent and null read alike. */
    private fun JSONObject.putOrNull(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (isNull(key)) null else getBoolean(key)

    private fun <T> BabyScoped<T>.toJson(row: (T) -> JSONObject): JSONObject =
        row(this.row).apply { put("babyUid", babyUid) }

    private fun <T> JSONObject.babyScoped(row: (JSONObject) -> T): BabyScoped<T> =
        BabyScoped(getString("babyUid"), row(this))

    private fun List<JSONObject>.toJsonArray(): JSONArray =
        JSONArray().also { array -> forEach(array::put) }

    private fun <T> JSONObject.list(key: String, item: (JSONObject) -> T): List<T> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { item(array.getJSONObject(it)) }
    }
}
