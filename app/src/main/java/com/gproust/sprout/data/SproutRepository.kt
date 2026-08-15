package com.gproust.sprout.data

import androidx.room.withTransaction
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.TreatmentEntity
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
class SproutRepository(
    private val db: SproutDatabase,
    /**
     * Invoked after any write that can change what the home-screen widget
     * shows (feeding logs, or which baby is active). Wired by the
     * application to refresh the widget; a no-op in tests.
     */
    private val onWidgetDataChanged: suspend () -> Unit = {},
) {

    // Parent profile (the owner of this device)
    val parentProfile: Flow<ParentProfileEntity?> = db.parentProfileDao().observeProfile()
    suspend fun saveParentProfile(profile: ParentProfileEntity) = db.parentProfileDao().upsert(profile)
    suspend fun updateParentLastCheckIn(time: Long) = db.parentProfileDao().updateLastCheckIn(time)
    suspend fun setAskHealing(ask: Boolean) = db.parentProfileDao().updateAskHealing(ask)
    suspend fun setAskBleeding(ask: Boolean) = db.parentProfileDao().updateAskBleeding(ask)
    suspend fun setAskBreasts(ask: Boolean) = db.parentProfileDao().updateAskBreasts(ask)

    /** Turn the parent's own wellbeing tracking on or off; kept history is untouched. */
    suspend fun setTrackWellbeing(track: Boolean) = db.parentProfileDao().updateTrackWellbeing(track)

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
            onWidgetDataChanged()
        }
        return id
    }

    suspend fun updateBaby(baby: BabyEntity) = db.babyDao().upsert(baby)

    /** All tracked babies as a one-shot list (for (re)scheduling feeding reminders). */
    suspend fun activeBabies(): List<BabyEntity> = db.babyDao().activeBabiesOnce()

    /** A baby by id, only if it still exists and is tracked (used when a reminder fires). */
    suspend fun activeBaby(babyId: Long): BabyEntity? = db.babyDao().activeBabyById(babyId)

    /** The id of the currently selected baby, read once (off a flow). */
    suspend fun activeBabyIdNow(): Long? = db.parentProfileDao().profileOnce()?.activeBabyId

    /** The name of a baby by id (for notifications); null if it no longer exists. */
    suspend fun babyName(id: Long): String? = db.babyDao().nameById(id)

    suspend fun setActiveBaby(babyId: Long) {
        db.parentProfileDao().updateActiveBaby(babyId)
        onWidgetDataChanged()
    }

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
            db.treatmentDao().deleteForBaby(babyId)
            db.babyDao().deleteById(babyId)
        }
        reassignActiveIfNeeded(babyId)
    }

    /** When the active baby goes away, fall back to another tracked baby (or none). */
    private suspend fun reassignActiveIfNeeded(removedId: Long) {
        val profile = db.parentProfileDao().profileOnce() ?: return
        if (profile.activeBabyId == removedId) {
            db.parentProfileDao().updateActiveBaby(db.babyDao().firstActiveBaby()?.id)
            onWidgetDataChanged()
        }
    }

    // Feeding
    val feedings: Flow<List<FeedingEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.feedingDao().observeForBaby(id)
    }
    suspend fun addFeeding(entity: FeedingEntity) {
        val id = activeBabyId.first() ?: return
        db.feedingDao().insert(entity.copy(babyId = id))
        onWidgetDataChanged()
    }
    suspend fun deleteFeeding(entity: FeedingEntity) {
        db.feedingDao().delete(entity)
        onWidgetDataChanged()
    }

    /** Epoch millis of a baby's most recent feed, or null if none yet. */
    suspend fun lastFeedTime(babyId: Long): Long? = db.feedingDao().lastFeedTime(babyId)

    /** The active baby's most recent feed of any kind, or null if none (for the widget). */
    suspend fun lastFeedForActiveBaby(): FeedingEntity? {
        val id = activeBabyIdNow() ?: return null
        return db.feedingDao().lastFeed(id)
    }

    /**
     * The active baby's most recent breastfeed started at or after [since], or
     * null. The widget asks for this on top of [lastFeedForActiveBaby] so that
     * a bottle given between two nursings doesn't take the last breast with it.
     */
    suspend fun lastBreastFeedForActiveBaby(since: Long): FeedingEntity? {
        val id = activeBabyIdNow() ?: return null
        return db.feedingDao().lastBreastFeedSince(id, since)
    }

    /**
     * The active baby's name. The widget shows it so that with twins you can
     * tell whose feed you're looking at.
     */
    suspend fun activeBabyName(): String? = activeBabyIdNow()?.let { db.babyDao().nameById(it) }

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

    // Treatments (medications/reminders — per active baby)
    val treatments: Flow<List<TreatmentEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.treatmentDao().observeForBaby(id)
    }
    suspend fun addTreatment(entity: TreatmentEntity): Long? {
        val id = activeBabyId.first() ?: return null
        return db.treatmentDao().insert(entity.copy(babyId = id))
    }
    suspend fun updateTreatment(entity: TreatmentEntity) = db.treatmentDao().update(entity)
    suspend fun deleteTreatment(entity: TreatmentEntity) = db.treatmentDao().delete(entity)
    suspend fun getTreatment(id: Long): TreatmentEntity? = db.treatmentDao().getById(id)

    /** All active treatments (across babies) that want reminders — for (re)scheduling alarms. */
    suspend fun treatmentsWithReminders(): List<TreatmentEntity> = db.treatmentDao().activeWithReminders()

    // Pumping (expressed milk — the parent's stash, not a baby's log)
    val pumpings: Flow<List<PumpingEntity>> = db.pumpingDao().observeAll()
    suspend fun addPumping(entity: PumpingEntity) = db.pumpingDao().insert(entity)
    suspend fun deletePumping(entity: PumpingEntity) = db.pumpingDao().delete(entity)

    // Wellbeing (parent check-ins — per parent, not per baby)
    val wellbeing: Flow<List<WellbeingEntity>> = db.wellbeingDao().observeAll()
    suspend fun addWellbeing(entity: WellbeingEntity) = db.wellbeingDao().insert(entity)
    suspend fun deleteWellbeing(entity: WellbeingEntity) = db.wellbeingDao().delete(entity)
}
