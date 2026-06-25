package com.gproust.sprout.data

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.MotherHealthEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.SproutDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Single point of access to persisted data, backing all ViewModels.
 */
class SproutRepository(private val db: SproutDatabase) {

    // Baby profile
    val baby: Flow<BabyEntity?> = db.babyDao().observeBaby()
    suspend fun saveBaby(baby: BabyEntity) = db.babyDao().upsert(baby)

    // Feeding
    val feedings: Flow<List<FeedingEntity>> = db.feedingDao().observeAll()
    suspend fun addFeeding(entity: FeedingEntity) = db.feedingDao().insert(entity)
    suspend fun deleteFeeding(entity: FeedingEntity) = db.feedingDao().delete(entity)

    // Sleep
    val sleeps: Flow<List<SleepEntity>> = db.sleepDao().observeAll()
    suspend fun addSleep(entity: SleepEntity) = db.sleepDao().insert(entity)
    suspend fun deleteSleep(entity: SleepEntity) = db.sleepDao().delete(entity)

    // Diaper
    val diapers: Flow<List<DiaperEntity>> = db.diaperDao().observeAll()
    suspend fun addDiaper(entity: DiaperEntity) = db.diaperDao().insert(entity)
    suspend fun deleteDiaper(entity: DiaperEntity) = db.diaperDao().delete(entity)

    // Growth
    val growth: Flow<List<GrowthEntity>> = db.growthDao().observeAll()
    suspend fun addGrowth(entity: GrowthEntity) = db.growthDao().insert(entity)
    suspend fun deleteGrowth(entity: GrowthEntity) = db.growthDao().delete(entity)

    // Mother health
    val motherHealth: Flow<List<MotherHealthEntity>> = db.motherHealthDao().observeAll()
    suspend fun addMotherHealth(entity: MotherHealthEntity) = db.motherHealthDao().insert(entity)
    suspend fun deleteMotherHealth(entity: MotherHealthEntity) = db.motherHealthDao().delete(entity)
}
