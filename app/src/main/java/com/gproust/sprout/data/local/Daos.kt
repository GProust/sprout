package com.gproust.sprout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Deleting a syncable row is a two-step business since ADR-0007.
 *
 * `softDelete` is the ordinary one: the row stays, stamped with `deletedAt`, so
 * the deletion can be told to the partner's phone instead of being silently
 * undone by it at the next merge. Every read therefore filters
 * `deletedAt IS NULL` — a missing filter shows deleted entries again.
 *
 * `purge*` is the permanent one, behind "delete a baby and all of their logs":
 * the rows really are erased, and `SproutRepository` records their uids in
 * `tombstone` first so the deletion still travels.
 *
 * `compact` finishes both off once the retention window has passed.
 */

@Dao
interface BabyDao {
    /** Babies still being tracked, in birth order (oldest first). */
    @Query("SELECT * FROM baby WHERE archived = 0 AND deletedAt IS NULL ORDER BY birthDate ASC, id ASC")
    fun observeActiveBabies(): Flow<List<BabyEntity>>

    /** Babies the user has stopped tracking. */
    @Query("SELECT * FROM baby WHERE archived = 1 AND deletedAt IS NULL ORDER BY birthDate ASC, id ASC")
    fun observeArchivedBabies(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM baby WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    fun observeBaby(id: Long): Flow<BabyEntity?>

    @Query("SELECT * FROM baby WHERE archived = 0 AND deletedAt IS NULL ORDER BY birthDate ASC, id ASC LIMIT 1")
    suspend fun firstActiveBaby(): BabyEntity?

    /** All tracked babies, as a one-shot list (for (re)scheduling reminders). */
    @Query("SELECT * FROM baby WHERE archived = 0 AND deletedAt IS NULL")
    suspend fun activeBabiesOnce(): List<BabyEntity>

    /** A baby by id, only if it still exists and is being tracked. */
    @Query("SELECT * FROM baby WHERE id = :id AND archived = 0 AND deletedAt IS NULL LIMIT 1")
    suspend fun activeBabyById(id: Long): BabyEntity?

    @Query("SELECT name FROM baby WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun nameById(id: Long): String?

    @Query("SELECT uid FROM baby WHERE id = :id LIMIT 1")
    suspend fun uidById(id: Long): String?

    @Insert
    suspend fun insert(baby: BabyEntity): Long

    @Upsert
    suspend fun upsert(baby: BabyEntity)

    @Query("UPDATE baby SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    /** Erase a baby for good — the deletion is carried by a `tombstone` row instead. */
    @Query("DELETE FROM baby WHERE id = :id")
    suspend fun purgeById(id: Long)
}

@Dao
interface ParentProfileDao {
    @Query("SELECT * FROM parent_profile WHERE id = 1 LIMIT 1")
    fun observeProfile(): Flow<ParentProfileEntity?>

    @Query("SELECT * FROM parent_profile WHERE id = 1 LIMIT 1")
    suspend fun profileOnce(): ParentProfileEntity?

    @Upsert
    suspend fun upsert(profile: ParentProfileEntity)

    @Query("UPDATE parent_profile SET lastCheckIn = :time WHERE id = 1")
    suspend fun updateLastCheckIn(time: Long)

    @Query("UPDATE parent_profile SET activeBabyId = :babyId WHERE id = 1")
    suspend fun updateActiveBaby(babyId: Long?)

    @Query("UPDATE parent_profile SET askHealing = :ask WHERE id = 1")
    suspend fun updateAskHealing(ask: Boolean)

    @Query("UPDATE parent_profile SET askBleeding = :ask WHERE id = 1")
    suspend fun updateAskBleeding(ask: Boolean)

    @Query("UPDATE parent_profile SET askBreasts = :ask WHERE id = 1")
    suspend fun updateAskBreasts(ask: Boolean)

    @Query("UPDATE parent_profile SET trackWellbeing = :track WHERE id = 1")
    suspend fun updateTrackWellbeing(track: Boolean)
}

@Dao
interface FeedingDao {
    @Query("SELECT * FROM feeding WHERE babyId = :babyId AND deletedAt IS NULL ORDER BY startTime DESC")
    fun observeForBaby(babyId: Long): Flow<List<FeedingEntity>>

    /** Epoch millis of the most recent feed for a baby, or null if none yet. */
    @Query("SELECT MAX(startTime) FROM feeding WHERE babyId = :babyId AND deletedAt IS NULL")
    suspend fun lastFeedTime(babyId: Long): Long?

    /** The most recent feed of any kind for a baby, or null if none yet (for the widget). */
    @Query(
        "SELECT * FROM feeding WHERE babyId = :babyId AND deletedAt IS NULL " +
            "ORDER BY startTime DESC LIMIT 1",
    )
    suspend fun lastFeed(babyId: Long): FeedingEntity?

    /**
     * The most recent breastfeed started at or after [since], or null. Kept
     * apart from [lastFeed] because a bottle in between must not hide which
     * breast the next feed should start on (for the widget).
     */
    @Query(
        "SELECT * FROM feeding WHERE babyId = :babyId AND type = 'BREAST' " +
            "AND startTime >= :since AND deletedAt IS NULL ORDER BY startTime DESC LIMIT 1",
    )
    suspend fun lastBreastFeedSince(babyId: Long, since: Long): FeedingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FeedingEntity)

    @Query("UPDATE feeding SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("SELECT uid FROM feeding WHERE babyId = :babyId")
    suspend fun uidsForBaby(babyId: Long): List<String>

    @Query("DELETE FROM feeding WHERE babyId = :babyId")
    suspend fun purgeForBaby(babyId: Long)

    @Query("DELETE FROM feeding WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep WHERE babyId = :babyId AND deletedAt IS NULL ORDER BY startTime DESC")
    fun observeForBaby(babyId: Long): Flow<List<SleepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SleepEntity)

    @Query("UPDATE sleep SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("SELECT uid FROM sleep WHERE babyId = :babyId")
    suspend fun uidsForBaby(babyId: Long): List<String>

    @Query("DELETE FROM sleep WHERE babyId = :babyId")
    suspend fun purgeForBaby(babyId: Long)

    @Query("DELETE FROM sleep WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

@Dao
interface DiaperDao {
    @Query("SELECT * FROM diaper WHERE babyId = :babyId AND deletedAt IS NULL ORDER BY time DESC")
    fun observeForBaby(babyId: Long): Flow<List<DiaperEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiaperEntity)

    @Query("UPDATE diaper SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("SELECT uid FROM diaper WHERE babyId = :babyId")
    suspend fun uidsForBaby(babyId: Long): List<String>

    @Query("DELETE FROM diaper WHERE babyId = :babyId")
    suspend fun purgeForBaby(babyId: Long)

    @Query("DELETE FROM diaper WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

@Dao
interface GrowthDao {
    @Query("SELECT * FROM growth WHERE babyId = :babyId AND deletedAt IS NULL ORDER BY time DESC")
    fun observeForBaby(babyId: Long): Flow<List<GrowthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GrowthEntity)

    @Query("UPDATE growth SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("SELECT uid FROM growth WHERE babyId = :babyId")
    suspend fun uidsForBaby(babyId: Long): List<String>

    @Query("DELETE FROM growth WHERE babyId = :babyId")
    suspend fun purgeForBaby(babyId: Long)

    @Query("DELETE FROM growth WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

@Dao
interface TreatmentDao {
    /** Active treatments for a baby. */
    @Query(
        "SELECT * FROM treatment WHERE babyId = :babyId AND active = 1 " +
            "AND deletedAt IS NULL ORDER BY name ASC",
    )
    fun observeForBaby(babyId: Long): Flow<List<TreatmentEntity>>

    @Query("SELECT * FROM treatment WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun getById(id: Long): TreatmentEntity?

    /** Every active treatment with reminders on — used to (re)schedule alarms, e.g. after a reboot. */
    @Query("SELECT * FROM treatment WHERE active = 1 AND remindersEnabled = 1 AND deletedAt IS NULL")
    suspend fun activeWithReminders(): List<TreatmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TreatmentEntity): Long

    @Update
    suspend fun update(entity: TreatmentEntity)

    @Query("UPDATE treatment SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("SELECT uid FROM treatment WHERE babyId = :babyId")
    suspend fun uidsForBaby(babyId: Long): List<String>

    @Query("DELETE FROM treatment WHERE babyId = :babyId")
    suspend fun purgeForBaby(babyId: Long)

    @Query("DELETE FROM treatment WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

@Dao
interface PumpingDao {
    /** Every pumping session, newest first (the parent's, not a baby's). */
    @Query("SELECT * FROM pumping WHERE deletedAt IS NULL ORDER BY time DESC")
    fun observeAll(): Flow<List<PumpingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PumpingEntity)

    @Query("UPDATE pumping SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("DELETE FROM pumping WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}

/**
 * The parent's own check-ins. Never synced (ADR-0007), so no uid, no tombstone:
 * a delete here is a plain delete.
 */
@Dao
interface WellbeingDao {
    @Query("SELECT * FROM wellbeing ORDER BY time DESC")
    fun observeAll(): Flow<List<WellbeingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WellbeingEntity)

    @Delete
    suspend fun delete(entity: WellbeingEntity)
}

@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<TombstoneEntity>)

    /** Whether a row is known to be deleted — asked before merging one in (phase 1). */
    @Query("SELECT EXISTS(SELECT 1 FROM tombstone WHERE uid = :uid)")
    suspend fun exists(uid: String): Boolean

    @Query("SELECT * FROM tombstone")
    suspend fun all(): List<TombstoneEntity>

    @Query("DELETE FROM tombstone WHERE deletedAt < :cutoff")
    suspend fun compact(cutoff: Long)
}
