package com.gproust.sprout.sync

/**
 * Merges per-device snapshots into a single converged view, using last-write-wins
 * per [SyncRecord.syncId].
 *
 * The winner for a given `syncId` is the record with the greatest
 * `(updatedAt, deviceId)` in lexicographic order. Because that's a *total order*,
 * the merge is commutative, associative and idempotent — so every device that
 * has seen the same set of files converges to the same result, regardless of the
 * order or timing of syncs.
 */
object Reconciler {

    /**
     * Combine [snapshots] and return the winning record per `syncId`, tombstones
     * included. Callers that only want live rows can filter out [SyncRecord.deleted].
     *
     * The result is sorted by `syncId` so the output is stable (handy for tests
     * and for diffing what changed).
     */
    fun merge(snapshots: List<DeviceSnapshot>): List<SyncRecord> {
        val winners = HashMap<String, SyncRecord>()
        for (snapshot in snapshots) {
            for (record in snapshot.records) {
                val current = winners[record.syncId]
                if (current == null || wins(record, current)) {
                    winners[record.syncId] = record
                }
            }
        }
        return winners.values.sortedBy { it.syncId }
    }

    /** Live (non-deleted) winners only. */
    fun mergeLive(snapshots: List<DeviceSnapshot>): List<SyncRecord> =
        merge(snapshots).filterNot { it.deleted }

    /**
     * True when [candidate] should beat [incumbent]: newer `updatedAt`, or the
     * same `updatedAt` with a greater `deviceId`. Deterministic for any pair, so
     * the merge result never depends on iteration order.
     */
    internal fun wins(candidate: SyncRecord, incumbent: SyncRecord): Boolean = when {
        candidate.updatedAt != incumbent.updatedAt -> candidate.updatedAt > incumbent.updatedAt
        else -> candidate.deviceId > incumbent.deviceId
    }
}
