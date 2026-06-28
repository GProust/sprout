package com.gproust.sprout.data

import androidx.room.withTransaction
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.data.local.SproutDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Single point of access to persisted data, backing all ViewModels.
 *
 * The baby-scoped logs (feeding/sleep/diaper/growth) always follow the
 * currently *active* baby: reads filter by it and writes are stamped with it,
 * so screens never have to thread a baby id around.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SproutRepository(private val db: SproutDatabase) {

    // Parent profile (the owner of this device)
    val parentProfile: Flow<ParentProfileEntity?> = db.parentProfileDao().observeProfile()
    suspend fun saveParentProfile(profile: ParentProfileEntity) = db.parentProfileDao().upsert(profile)
    suspend fun updateParentLastCheckIn(time: Long) = db.parentProfileDao().updateLastCheckIn(time)

    private val activeBabyId: Flow<Long?> = parentProfile
        .map { it?.activeBabyId }
        .distinctUntilChanged()

    // Babies
    /** Babies currently being tracked, in birth order. */
    val babies: Flow<List<BabyEntity>> = db.babyDao().observeActiveBabies()

    /** Babies the user has stopped tracking (kept, but out of the rotation). */
    val archivedBabies: Flow<List<BabyEntity>> = db.babyDao().observeArchivedBabies()

    /** The baby currently selected for viewing and logging. */
    val baby: Flow<BabyEntity?> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(null) else db.babyDao().observeBaby(id)
    }

    /** Adds a baby and returns its new id, selecting it if none is active yet. */
    suspend fun addBaby(name: String, birthDate: Long): Long {
        val id = db.babyDao().insert(BabyEntity(name = name.trim(), birthDate = birthDate))
        if (db.parentProfileDao().profileOnce()?.activeBabyId == null) {
            db.parentProfileDao().updateActiveBaby(id)
        }
        return id
    }

    suspend fun updateBaby(baby: BabyEntity) = db.babyDao().upsert(baby)

    suspend fun setActiveBaby(babyId: Long) = db.parentProfileDao().updateActiveBaby(babyId)

    /** Stop tracking a baby: keep its data but take it out of the active rotation. */
    suspend fun archiveBaby(babyId: Long) {
        db.babyDao().setArchived(babyId, true)
        reassignActiveIfNeeded(babyId)
    }

    suspend fun restoreBaby(babyId: Long) = db.babyDao().setArchived(babyId, false)

    /** Permanently delete a baby together with all of its logs. */
    suspend fun deleteBaby(babyId: Long) {
        db.withTransaction {
            db.feedingDao().deleteForBaby(babyId)
            db.sleepDao().deleteForBaby(babyId)
            db.diaperDao().deleteForBaby(babyId)
            db.growthDao().deleteForBaby(babyId)
            db.babyDao().deleteById(babyId)
        }
        reassignActiveIfNeeded(babyId)
    }

    /** When the active baby goes away, fall back to another tracked baby (or none). */
    private suspend fun reassignActiveIfNeeded(removedId: Long) {
        val profile = db.parentProfileDao().profileOnce() ?: return
        if (profile.activeBabyId == removedId) {
            db.parentProfileDao().updateActiveBaby(db.babyDao().firstActiveBaby()?.id)
        }
    }

    // Feeding
    val feedings: Flow<List<FeedingEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.feedingDao().observeForBaby(id)
    }
    suspend fun addFeeding(entity: FeedingEntity) {
        val id = activeBabyId.first() ?: return
        db.feedingDao().insert(entity.copy(babyId = id))
    }
    suspend fun deleteFeeding(entity: FeedingEntity) = db.feedingDao().delete(entity)

    // Sleep
    val sleeps: Flow<List<SleepEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.sleepDao().observeForBaby(id)
    }
    suspend fun addSleep(entity: SleepEntity) {
        val id = activeBabyId.first() ?: return
        db.sleepDao().insert(entity.copy(babyId = id))
    }
    suspend fun deleteSleep(entity: SleepEntity) = db.sleepDao().delete(entity)

    // Diaper
    val diapers: Flow<List<DiaperEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.diaperDao().observeForBaby(id)
    }
    suspend fun addDiaper(entity: DiaperEntity) {
        val id = activeBabyId.first() ?: return
        db.diaperDao().insert(entity.copy(babyId = id))
    }
    suspend fun deleteDiaper(entity: DiaperEntity) = db.diaperDao().delete(entity)

    // Growth
    val growth: Flow<List<GrowthEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.growthDao().observeForBaby(id)
    }
    suspend fun addGrowth(entity: GrowthEntity) {
        val id = activeBabyId.first() ?: return
        db.growthDao().insert(entity.copy(babyId = id))
    }
    suspend fun deleteGrowth(entity: GrowthEntity) = db.growthDao().delete(entity)

    // Wellbeing (parent check-ins — per parent, not per baby)
    val wellbeing: Flow<List<WellbeingEntity>> = db.wellbeingDao().observeAll()
    suspend fun addWellbeing(entity: WellbeingEntity) = db.wellbeingDao().insert(entity)
    suspend fun deleteWellbeing(entity: WellbeingEntity) = db.wellbeingDao().delete(entity)
}
