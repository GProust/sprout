package com.gproust.sprout.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON encoding for a [DeviceSnapshot] — the exact bytes written to a device's
 * file in the shared folder.
 *
 * Uses `org.json` (bundled with Android, and available to JVM unit tests via
 * Robolectric) so no extra serialization dependency is pulled into the build.
 * The format is intentionally simple and forward-compatible: unknown top-level
 * keys are ignored on read, and a missing `fields` map decodes to empty.
 */
object SnapshotJson {

    private const val VERSION = 1

    fun encode(snapshot: DeviceSnapshot): String {
        val records = JSONArray()
        for (record in snapshot.records) {
            val fields = JSONObject()
            for ((key, value) in record.fields) {
                // JSONObject.put ignores null values, so encode NULL explicitly.
                fields.put(key, value ?: JSONObject.NULL)
            }
            records.put(
                JSONObject()
                    .put("syncId", record.syncId)
                    .put("table", record.table)
                    .put("updatedAt", record.updatedAt)
                    .put("deviceId", record.deviceId)
                    .put("deleted", record.deleted)
                    .put("fields", fields),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("deviceId", snapshot.deviceId)
            .put("generatedAt", snapshot.generatedAt)
            .put("records", records)
            .toString()
    }

    fun decode(json: String): DeviceSnapshot {
        val root = JSONObject(json)
        val recordsJson = root.optJSONArray("records") ?: JSONArray()
        val records = ArrayList<SyncRecord>(recordsJson.length())
        for (i in 0 until recordsJson.length()) {
            val obj = recordsJson.getJSONObject(i)
            records.add(
                SyncRecord(
                    syncId = obj.getString("syncId"),
                    table = obj.getString("table"),
                    updatedAt = obj.getLong("updatedAt"),
                    deviceId = obj.getString("deviceId"),
                    deleted = obj.optBoolean("deleted", false),
                    fields = decodeFields(obj.optJSONObject("fields")),
                ),
            )
        }
        return DeviceSnapshot(
            deviceId = root.getString("deviceId"),
            generatedAt = root.optLong("generatedAt", 0L),
            records = records,
        )
    }

    private fun decodeFields(obj: JSONObject?): Map<String, String?> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String?>()
        for (key in obj.keys()) {
            out[key] = if (obj.isNull(key)) null else obj.getString(key)
        }
        return out
    }
}
