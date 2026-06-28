package com.gproust.sprout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BabyDao {
    /** Babies still being tracked, in birth order (oldest first). */
    @Query("SELECT * FROM baby WHERE archived = 0 ORDER BY birthDate ASC, id ASC")
    fun observeActiveBabies(): Flow<List<BabyEntity>>

    /** Babies the user has stopped tracking. */
    @Query("SELECT * FROM baby WHERE archived = 1 ORDER BY birthDate ASC, id ASC")
    fun observeArchivedBabies(): Flow<List<BabyEntity>>

    @Query("SELECT * FROM baby WHERE id = :id LIMIT 1")
    fun observeBaby(id: Long): Flow<BabyEntity?>

    @Query("SELECT * FROM baby WHERE archived = 0 ORDER BY birthDate ASC, id ASC LIMIT 1")
    suspend fun firstActiveBaby(): BabyEntity?

    @Query("SELECT name FROM baby WHERE id = :id LIMIT 1")
    suspend fun nameById(id: Long): String?

    @Insert
    suspend fun insert(baby: BabyEntity): Long

    @Upsert
    suspend fun upsert(baby: BabyEntity)

    @Query("UPDATE baby SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM baby WHERE id = :id")
    suspend fun deleteById(id: Long)
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
}

@Dao
interface FeedingDao {
    @Query("SELECT * FROM feeding WHERE babyId = :babyId ORDER BY startTime DESC")
    fun observeForBaby(babyId: Long): Flow<List<FeedingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FeedingEntity)

    @Delete
    suspend fun delete(entity: FeedingEntity)

    @Query("DELETE FROM feeding WHERE babyId = :babyId")
    suspend fun deleteForBaby(babyId: Long)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep WHERE babyId = :babyId ORDER BY startTime DESC")
    fun observeForBaby(babyId: Long): Flow<List<SleepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SleepEntity)

    @Delete
    suspend fun delete(entity: SleepEntity)

    @Query("DELETE FROM sleep WHERE babyId = :babyId")
    suspend fun deleteForBaby(babyId: Long)
}

@Dao
interface DiaperDao {
    @Query("SELECT * FROM diaper WHERE babyId = :babyId ORDER BY time DESC")
    fun observeForBaby(babyId: Long): Flow<List<DiaperEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiaperEntity)

    @Delete
    suspend fun delete(entity: DiaperEntity)

    @Query("DELETE FROM diaper WHERE babyId = :babyId")
    suspend fun deleteForBaby(babyId: Long)
}

@Dao
interface GrowthDao {
    @Query("SELECT * FROM growth WHERE babyId = :babyId ORDER BY time DESC")
    fun observeForBaby(babyId: Long): Flow<List<GrowthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GrowthEntity)

    @Delete
    suspend fun delete(entity: GrowthEntity)

    @Query("DELETE FROM growth WHERE babyId = :babyId")
    suspend fun deleteForBaby(babyId: Long)
}

@Dao
interface TreatmentDao {
    /** Active treatments for a baby. */
    @Query("SELECT * FROM treatment WHERE babyId = :babyId AND active = 1 ORDER BY name ASC")
    fun observeForBaby(babyId: Long): Flow<List<TreatmentEntity>>

    @Query("SELECT * FROM treatment WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TreatmentEntity?

    /** Every active treatment with reminders on — used to (re)schedule alarms, e.g. after a reboot. */
    @Query("SELECT * FROM treatment WHERE active = 1 AND remindersEnabled = 1")
    suspend fun activeWithReminders(): List<TreatmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TreatmentEntity): Long

    @Update
    suspend fun update(entity: TreatmentEntity)

    @Delete
    suspend fun delete(entity: TreatmentEntity)

    @Query("DELETE FROM treatment WHERE babyId = :babyId")
    suspend fun deleteForBaby(babyId: Long)
}

@Dao
interface WellbeingDao {
    @Query("SELECT * FROM wellbeing ORDER BY time DESC")
    fun observeAll(): Flow<List<WellbeingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WellbeingEntity)

    @Delete
    suspend fun delete(entity: WellbeingEntity)
}
