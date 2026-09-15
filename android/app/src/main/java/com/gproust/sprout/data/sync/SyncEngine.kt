package com.gproust.sprout.data.sync

import androidx.room.withTransaction
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.local.Syncable
import com.gproust.sprout.data.local.TombstoneEntity

/**
 * What a merge actually did — the thing to put in front of the user afterwards.
 *
 * A merge that reports nothing is indistinguishable from one that failed
 * (ADR-0007), and "nothing new" is a perfectly good outcome that still deserves
 * saying out loud.
 */
data class MergeSummary(
    val added: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val alreadyKnown: Int = 0,
    /** Rows left out: already deleted here, or older than a pairing cut-off. */
    val skipped: Int = 0,
) {
    val changed: Boolean get() = added > 0 || updated > 0 || deleted > 0

    operator fun plus(other: MergeSummary) = MergeSummary(
        added = added + other.added,
        updated = updated + other.updated,
        deleted = deleted + other.deleted,
        alreadyKnown = alreadyKnown + other.alreadyKnown,
        skipped = skipped + other.skipped,
    )
}

/**
 * Builds replicas, and merges the ones that arrive (ADR-0007).
 *
 * The merge is **commutative and idempotent**: applying the same replica twice
 * changes nothing the second time, and A-then-B lands on the same database as
 * B-then-A. That is what lets an exchange be repeated, interrupted or run out of
 * order without damage — and it is the property the tests are really about.
 */
class SyncEngine(
    private val db: SproutDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Everything this phone is willing to share, ready to be encrypted and sent.
     *
     * @param includePumping the stash switch (ADR-0008): off means the pumping
     * rows simply never leave, and a replica without them merges like any other.
     */
    suspend fun buildPayload(
        householdId: String,
        deviceId: String,
        deviceName: String,
        includePumping: Boolean,
    ): SyncPayload = db.withTransaction {
        val babies = db.babyDao().allForSync()
        val uidOf = babies.associate { it.id to it.uid }

        // A log whose baby was permanently deleted has nothing to point at; the
        // baby's tombstone is what travels instead.
        fun <T> scoped(rows: List<T>, babyId: (T) -> Long): List<BabyScoped<T>> =
            rows.mapNotNull { row -> uidOf[babyId(row)]?.let { BabyScoped(it, row) } }

        SyncPayload(
            householdId = householdId,
            deviceId = deviceId,
            deviceName = deviceName,
            createdAt = now(),
            schemaVersion = schemaVersion(),
            babies = babies,
            feedings = scoped(db.feedingDao().allForSync()) { it.babyId },
            sleeps = scoped(db.sleepDao().allForSync()) { it.babyId },
            diapers = scoped(db.diaperDao().allForSync()) { it.babyId },
            growth = scoped(db.growthDao().allForSync()) { it.babyId },
            treatments = scoped(db.treatmentDao().allForSync()) { it.babyId },
            pumpings = if (includePumping) db.pumpingDao().allForSync() else emptyList(),
            tombstones = db.tombstoneDao().all(),
        )
    }

    /**
     * The Room schema this build speaks, asked of the database rather than kept
     * as a constant beside it — one of the two would eventually be wrong.
     */
    fun schemaVersion(): Int = db.openHelper.readableDatabase.version

    /** Whether this phone has any baby data of its own yet (ADR-0008's adoption case). */
    suspend fun hasOwnHistory(): Boolean = db.babyDao().countForSync() > 0

    /**
     * Merges an arriving replica into this database.
     *
     * @param since when set, only rows written at or after this moment are taken
     * — the "share from the pairing forward" answer for two parents who both
     * tracked before pairing (ADR-0008). Rows are judged by when they were
     * *written*, not by the time they describe, so backdating an entry doesn't
     * hide it.
     */
    suspend fun merge(payload: SyncPayload, since: Long? = null): MergeSummary =
        db.withTransaction {
            var summary = applyTombstones(payload.tombstones)

            // Babies first: a log points at its baby by uid, and can only be
            // stored once that baby has a local id to point at.
            //
            // They ignore `since` on purpose. "Share from the pairing forward"
            // is about entries, not about who the baby is — filtering the baby
            // out would leave every later log with nothing to attach to.
            summary += mergeRows(
                rows = payload.babies,
                since = null,
                find = { db.babyDao().findByUid(it) },
                insert = { db.babyDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.babyDao().upsert(incoming.copy(id = local.id)) },
            )
            val babyIdOf = db.babyDao().allForSync().associate { it.uid to it.id }

            summary += mergeScoped(
                payload.feedings, since, babyIdOf,
                withBaby = { row, babyId -> row.copy(babyId = babyId) },
                find = { db.feedingDao().findByUid(it) },
                insert = { db.feedingDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.feedingDao().update(incoming.copy(id = local.id)) },
            )
            summary += mergeScoped(
                payload.sleeps, since, babyIdOf,
                withBaby = { row, babyId -> row.copy(babyId = babyId) },
                find = { db.sleepDao().findByUid(it) },
                insert = { db.sleepDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.sleepDao().update(incoming.copy(id = local.id)) },
            )
            summary += mergeScoped(
                payload.diapers, since, babyIdOf,
                withBaby = { row, babyId -> row.copy(babyId = babyId) },
                find = { db.diaperDao().findByUid(it) },
                insert = { db.diaperDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.diaperDao().update(incoming.copy(id = local.id)) },
            )
            summary += mergeScoped(
                payload.growth, since, babyIdOf,
                withBaby = { row, babyId -> row.copy(babyId = babyId) },
                find = { db.growthDao().findByUid(it) },
                insert = { db.growthDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.growthDao().update(incoming.copy(id = local.id)) },
            )
            summary += mergeScoped(
                payload.treatments, since, babyIdOf,
                withBaby = { row, babyId -> row.copy(babyId = babyId) },
                find = { db.treatmentDao().findByUid(it) },
                insert = { db.treatmentDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.treatmentDao().update(incoming.copy(id = local.id)) },
            )
            summary += mergeRows(
                rows = payload.pumpings,
                since = since,
                find = { db.pumpingDao().findByUid(it) },
                insert = { db.pumpingDao().insert(it.copy(id = 0L)) },
                update = { incoming, local -> db.pumpingDao().update(incoming.copy(id = local.id)) },
            )
            summary
        }

    /**
     * Erasures the other phone performed. The row goes, the uid stays — locally
     * too, so that this phone stops offering the entry back in its own replicas.
     */
    private suspend fun applyTombstones(tombstones: List<TombstoneEntity>): MergeSummary {
        if (tombstones.isEmpty()) return MergeSummary()
        var deleted = 0
        for (tombstone in tombstones) {
            val existed = when (tombstone.entity) {
                "baby" -> db.babyDao().findByUid(tombstone.uid)?.also { db.babyDao().purgeByUid(tombstone.uid) }
                "feeding" -> db.feedingDao().findByUid(tombstone.uid)?.also { db.feedingDao().purgeByUid(tombstone.uid) }
                "sleep" -> db.sleepDao().findByUid(tombstone.uid)?.also { db.sleepDao().purgeByUid(tombstone.uid) }
                "diaper" -> db.diaperDao().findByUid(tombstone.uid)?.also { db.diaperDao().purgeByUid(tombstone.uid) }
                "growth" -> db.growthDao().findByUid(tombstone.uid)?.also { db.growthDao().purgeByUid(tombstone.uid) }
                "treatment" -> db.treatmentDao().findByUid(tombstone.uid)?.also { db.treatmentDao().purgeByUid(tombstone.uid) }
                "pumping" -> db.pumpingDao().findByUid(tombstone.uid)?.also { db.pumpingDao().purgeByUid(tombstone.uid) }
                // A table we don't know: keep the tombstone, touch nothing.
                else -> null
            }
            if (existed != null) deleted++
        }
        db.tombstoneDao().insertAll(tombstones)
        return MergeSummary(deleted = deleted)
    }

    private suspend fun <T : Syncable> mergeScoped(
        rows: List<BabyScoped<T>>,
        since: Long?,
        babyIdOf: Map<String, Long>,
        withBaby: (T, Long) -> T,
        find: suspend (String) -> T?,
        insert: suspend (T) -> Unit,
        update: suspend (T, T) -> Unit,
    ): MergeSummary {
        // A log for a baby neither phone has (permanently deleted, most likely)
        // has nowhere to go.
        val (placeable, orphaned) = rows.partition { babyIdOf.containsKey(it.babyUid) }
        return mergeRows(
            rows = placeable.map { withBaby(it.row, babyIdOf.getValue(it.babyUid)) },
            since = since,
            find = find,
            insert = insert,
            update = update,
        ) + MergeSummary(skipped = orphaned.size)
    }

    /**
     * The merge rules themselves (ADR-0007), in one place:
     *
     * - a uid we have never seen is inserted;
     * - a uid we know takes the *later* write, by `updatedAt`;
     * - a tombstone wins on either side, whatever the clocks say;
     * - a uid we have already erased is never taken back.
     */
    private suspend fun <T : Syncable> mergeRows(
        rows: List<T>,
        since: Long?,
        find: suspend (String) -> T?,
        insert: suspend (T) -> Unit,
        update: suspend (T, T) -> Unit,
    ): MergeSummary {
        var summary = MergeSummary()
        for (incoming in rows) {
            if (since != null && incoming.updatedAt < since) {
                summary += MergeSummary(skipped = 1)
                continue
            }
            if (db.tombstoneDao().exists(incoming.uid)) {
                // Erased here for good. Taking it back would undo a deletion the
                // user asked for, which is the whole failure this design exists
                // to prevent.
                summary += MergeSummary(skipped = 1)
                continue
            }
            val local = find(incoming.uid)
            when {
                local == null && incoming.deletedAt != null -> {
                    // Created and deleted while we weren't looking: nothing to show.
                    summary += MergeSummary(skipped = 1)
                }
                local == null -> {
                    insert(incoming)
                    summary += MergeSummary(added = 1)
                }
                local.deletedAt != null -> {
                    // Deleted here already; a tombstone outranks any later edit.
                    summary += MergeSummary(alreadyKnown = 1)
                }
                incoming.deletedAt != null -> {
                    update(incoming, local)
                    summary += MergeSummary(deleted = 1)
                }
                incoming.updatedAt > local.updatedAt -> {
                    update(incoming, local)
                    summary += MergeSummary(updated = 1)
                }
                else -> summary += MergeSummary(alreadyKnown = 1)
            }
        }
        return summary
    }
}
