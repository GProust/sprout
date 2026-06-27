package com.gproust.sprout.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BabyDao {
    @Query("SELECT * FROM baby WHERE id = 1 LIMIT 1")
    fun observeBaby(): Flow<BabyEntity?>

    @Upsert
    suspend fun upsert(baby: BabyEntity)
}

@Dao
interface ParentProfileDao {
    @Query("SELECT * FROM parent_profile WHERE id = 1 LIMIT 1")
    fun observeProfile(): Flow<ParentProfileEntity?>

    @Upsert
    suspend fun upsert(profile: ParentProfileEntity)

    @Query("UPDATE parent_profile SET lastCheckIn = :time WHERE id = 1")
    suspend fun updateLastCheckIn(time: Long)
}

@Dao
interface FeedingDao {
    @Query("SELECT * FROM feeding ORDER BY startTime DESC")
    fun observeAll(): Flow<List<FeedingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FeedingEntity)

    @Delete
    suspend fun delete(entity: FeedingEntity)
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep ORDER BY startTime DESC")
    fun observeAll(): Flow<List<SleepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SleepEntity)

    @Delete
    suspend fun delete(entity: SleepEntity)
}

@Dao
interface DiaperDao {
    @Query("SELECT * FROM diaper ORDER BY time DESC")
    fun observeAll(): Flow<List<DiaperEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DiaperEntity)

    @Delete
    suspend fun delete(entity: DiaperEntity)
}

@Dao
interface GrowthDao {
    @Query("SELECT * FROM growth ORDER BY time DESC")
    fun observeAll(): Flow<List<GrowthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GrowthEntity)

    @Delete
    suspend fun delete(entity: GrowthEntity)
}

@Dao
interface MotherHealthDao {
    @Query("SELECT * FROM mother_health ORDER BY time DESC")
    fun observeAll(): Flow<List<MotherHealthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MotherHealthEntity)

    @Delete
    suspend fun delete(entity: MotherHealthEntity)
}

@Dao
interface CoParentHealthDao {
    @Query("SELECT * FROM co_parent_health ORDER BY time DESC")
    fun observeAll(): Flow<List<CoParentHealthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CoParentHealthEntity)

    @Delete
    suspend fun delete(entity: CoParentHealthEntity)
}
