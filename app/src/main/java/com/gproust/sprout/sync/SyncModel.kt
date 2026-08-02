package com.gproust.sprout.sync

/**
 * One syncable row, in a transport-neutral form. This is what gets written to a
 * device's Drive file and what [Reconciler] merges across devices.
 *
 * The merge key is [syncId] (a stable UUID shared across devices), NOT the local
 * Room autoincrement id — autoincrement ids would collide between devices.
 *
 * @property syncId stable cross-device identifier (UUID string).
 * @property table which entity this row belongs to, e.g. "feeding".
 * @property updatedAt wall-clock millis of the last change to this row.
 * @property deviceId the device that made that change; the deterministic
 *   tie-breaker when two devices share the same [updatedAt].
 * @property deleted tombstone: a delete is a change that must propagate, not the
 *   mere absence of a row.
 * @property fields the row's column values, serialized to strings (null = SQL
 *   NULL). Ignored when [deleted] is true.
 */
data class SyncRecord(
    val syncId: String,
    val table: String,
    val updatedAt: Long,
    val deviceId: String,
    val deleted: Boolean = false,
    val fields: Map<String, String?> = emptyMap(),
)

/**
 * One device's complete view of the shared data — the content of a single
 * `device-<deviceId>.json` file in the shared folder.
 *
 * @property deviceId the device that owns (and is the only writer of) this file.
 * @property generatedAt when this snapshot was produced (diagnostics only).
 * @property records every syncable row this device knows about, tombstones
 *   included.
 */
data class DeviceSnapshot(
    val deviceId: String,
    val generatedAt: Long,
    val records: List<SyncRecord>,
)
