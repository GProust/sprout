package com.gproust.sprout.data

import androidx.room.withTransaction
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.local.ParentProfileEntity
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.TombstoneEntity
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.data.local.WellbeingEntity
import com.gproust.sprout.data.local.SproutDatabase
import com.gproust.sprout.data.sync.TOMBSTONE_RETENTION_MS
import com.gproust.sprout.data.sync.newUid
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
 *
 * It is also the one place that stamps the sync columns (ADR-0007). Screens
 * build entities without knowing about `uid` or `updatedAt`, and deleting an
 * entry means flagging it rather than removing it — with one exception,
 * [deleteBaby], which really does erase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SproutRepository(
    private val db: SproutDatabase,
    /** The clock every write is stamped with; swappable in tests. */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Invoked after any write that can change what the home-screen widget
     * shows (feeding logs, or which baby is active). Wired by the
     * application to refresh the widget; a no-op in tests. Stays last so the
     * application can keep passing it as a trailing lambda.
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

    // The household reads (BDR-9). Everything above and below follows the
    // active baby; these three deliberately do not, because the dashboard
    // summarises every tracked baby at once. They are summaries rather than
    // logs, so they are bounded by HOUSEHOLD_WINDOW_MS instead of returning a
    // baby's whole history — the log screens are still where that lives.

    /** Every tracked baby's feeds since [since]. */
    fun householdFeedings(since: Long): Flow<List<FeedingEntity>> =
        db.feedingDao().observeAllSince(since)

    /** Every tracked baby's sleeps since [since]. */
    fun householdSleeps(since: Long): Flow<List<SleepEntity>> =
        db.sleepDao().observeAllSince(since)

    /** Every tracked baby's nappies since [since]. */
    fun householdDiapers(since: Long): Flow<List<DiaperEntity>> =
        db.diaperDao().observeAllSince(since)

    /** Every tracked baby's as-needed medicines (BDR-15). */
    val householdMedicines: Flow<List<MedicineEntity>> = db.medicineDao().observeAllActive()

    /**
     * Every tracked baby's doses since [since].
     *
     * The window has to be wider than the daily maximum's, not equal to it: a
     * medicine whose minimum wait is longer than a day would otherwise read as
     * never given, and so as ready, on the one screen that says whether it is.
     */
    fun householdMedicineDoses(since: Long): Flow<List<MedicineDoseEntity>> =
        db.medicineDoseDao().observeAllSince(since)

    // Sync stamping (ADR-0007). Screens build entities without a uid or an
    // updatedAt; every write goes through one of these on its way to the DAO,
    // so no call site has to remember. `ifEmpty` keeps the unique index safe
    // even if an entity is ever built by hand without one.
    private fun BabyEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun FeedingEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun SleepEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun DiaperEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun GrowthEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun TreatmentEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun MedicineEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun MedicineDoseEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())
    private fun PumpingEntity.stamped() = copy(uid = uid.ifEmpty { newUid() }, updatedAt = now())

    /** Adds a baby and returns its new id, selecting it if none is active yet. */
    suspend fun addBaby(name: String, birthDate: Long): Long {
        val id = db.babyDao().insert(BabyEntity(name = name.trim(), birthDate = birthDate).stamped())
        if (db.parentProfileDao().profileOnce()?.activeBabyId == null) {
            db.parentProfileDao().updateActiveBaby(id)
            onWidgetDataChanged()
        }
        return id
    }

    suspend fun updateBaby(baby: BabyEntity) = db.babyDao().upsert(baby.stamped())

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
        db.babyDao().setArchived(babyId, true, now())
        reassignActiveIfNeeded(babyId)
    }

    suspend fun restoreBaby(babyId: Long) = db.babyDao().setArchived(babyId, false, now())

    /**
     * Permanently delete a baby together with all of its logs.
     *
     * Unlike deleting a single entry, this really erases: the rows are gone,
     * not flagged. Only their uids are kept, in `tombstone`, so that a partner's
     * phone learns the entries were deleted instead of sending them all back at
     * the next merge (ADR-0007). A tombstone says nothing about what the entry
     * contained.
     */
    suspend fun deleteBaby(babyId: Long) {
        val deletedAt = now()
        db.withTransaction {
            val tombstones = buildList {
                addAll(db.feedingDao().uidsForBaby(babyId).toTombstones("feeding", deletedAt))
                addAll(db.sleepDao().uidsForBaby(babyId).toTombstones("sleep", deletedAt))
                addAll(db.diaperDao().uidsForBaby(babyId).toTombstones("diaper", deletedAt))
                addAll(db.growthDao().uidsForBaby(babyId).toTombstones("growth", deletedAt))
                addAll(db.treatmentDao().uidsForBaby(babyId).toTombstones("treatment", deletedAt))
                addAll(db.medicineDao().uidsForBaby(babyId).toTombstones("medicine", deletedAt))
                addAll(db.medicineDoseDao().uidsForBaby(babyId).toTombstones("medicine_dose", deletedAt))
                addAll(listOfNotNull(db.babyDao().uidById(babyId)).toTombstones("baby", deletedAt))
            }
            db.tombstoneDao().insertAll(tombstones)

            db.feedingDao().purgeForBaby(babyId)
            db.sleepDao().purgeForBaby(babyId)
            db.diaperDao().purgeForBaby(babyId)
            db.growthDao().purgeForBaby(babyId)
            db.treatmentDao().purgeForBaby(babyId)
            db.medicineDao().purgeForBaby(babyId)
            db.medicineDoseDao().purgeForBaby(babyId)
            db.babyDao().purgeById(babyId)
        }
        reassignActiveIfNeeded(babyId)
    }

    private fun List<String>.toTombstones(entity: String, deletedAt: Long) =
        map { TombstoneEntity(uid = it, entity = entity, deletedAt = deletedAt) }

    /**
     * Erase soft-deleted rows, and the tombstones of erased ones, once they are
     * older than the retention window — otherwise a delete would cost storage
     * for the life of the install. Called on launch.
     */
    suspend fun compactTombstones() {
        val cutoff = now() - TOMBSTONE_RETENTION_MS
        db.withTransaction {
            db.feedingDao().compact(cutoff)
            db.sleepDao().compact(cutoff)
            db.diaperDao().compact(cutoff)
            db.growthDao().compact(cutoff)
            db.treatmentDao().compact(cutoff)
            db.medicineDao().compact(cutoff)
            db.medicineDoseDao().compact(cutoff)
            db.pumpingDao().compact(cutoff)
            db.tombstoneDao().compact(cutoff)
        }
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
        db.feedingDao().insert(entity.copy(babyId = id).stamped())
        onWidgetDataChanged()
    }

    /**
     * Add a feed to a named baby rather than to whichever one is active.
     *
     * The dashboard's quick feed button starts a session from a card, and the
     * baby it belongs to is decided by the card that was tapped — not by a mode
     * set elsewhere. Going through [addFeeding] there would resolve the active
     * baby at insert time, minutes later, which with twins is how a feed lands
     * on the wrong child.
     */
    suspend fun addFeedingFor(babyId: Long, entity: FeedingEntity) {
        db.feedingDao().insert(entity.copy(babyId = babyId).stamped())
        onWidgetDataChanged()
    }

    /** A named baby's most recent breastfeed started at or after [since], or null. */
    suspend fun lastBreastFeedFor(babyId: Long, since: Long): FeedingEntity? =
        db.feedingDao().lastBreastFeedSince(babyId, since)
    suspend fun deleteFeeding(entity: FeedingEntity) {
        db.feedingDao().softDelete(entity.id, now())
        onWidgetDataChanged()
    }

    /**
     * Joins two breastfeeds saved apart into the one feed they were (BDR-19):
     * [earlier] gains [later]'s stretches, with the minutes between them kept
     * as a break, and [later] is deleted. Returns whether anything was written.
     *
     * Both rows are read again inside the transaction rather than trusted from
     * the screen. The confirmation can sit open while an exchange with another
     * phone edits or deletes one of them, and joining a copy that has since
     * changed would quietly undo that edit — or bring a deleted feed back
     * inside another one.
     *
     * One transaction, so no phone is ever left holding a joined feed while the
     * later one still exists — which would count its stretches twice.
     */
    suspend fun joinBreastfeeds(earlier: FeedingEntity, later: FeedingEntity): Boolean {
        val joined = db.withTransaction {
            val first = db.feedingDao().findByUid(earlier.uid)?.takeIf { it.deletedAt == null }
            val second = db.feedingDao().findByUid(later.uid)?.takeIf { it.deletedAt == null }
            if (first == null || second == null || breastfeedJoinGap(first, second) == null) {
                return@withTransaction false
            }
            val at = now()
            db.feedingDao().insert(joinedBreastfeed(first, second).copy(updatedAt = at))
            db.feedingDao().softDelete(second.id, at)
            true
        }
        if (joined) onWidgetDataChanged()
        return joined
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
        db.sleepDao().insert(entity.copy(babyId = id).stamped())
    }
    suspend fun deleteSleep(entity: SleepEntity) = db.sleepDao().softDelete(entity.id, now())

    /**
     * Save a change to an existing sleep — in practice, closing one that was
     * started with no end time.
     *
     * Until this existed a sleep logged as "still asleep" could only be deleted
     * and re-entered, and in the meantime it read as *still running*: both the
     * dashboard and the daily statistics take `endTime ?: now`, so the day's
     * total climbed on its own.
     */
    suspend fun updateSleep(entity: SleepEntity) = db.sleepDao().update(entity.stamped())

    /** Sleeps that have begun and not yet ended, for any baby. */
    val ongoingSleeps: Flow<List<SleepEntity>> = db.sleepDao().observeOngoing()

    // Diaper
    val diapers: Flow<List<DiaperEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.diaperDao().observeForBaby(id)
    }
    suspend fun addDiaper(entity: DiaperEntity) {
        val id = activeBabyId.first() ?: return
        db.diaperDao().insert(entity.copy(babyId = id).stamped())
    }
    suspend fun deleteDiaper(entity: DiaperEntity) = db.diaperDao().softDelete(entity.id, now())

    // Growth
    val growth: Flow<List<GrowthEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.growthDao().observeForBaby(id)
    }
    suspend fun addGrowth(entity: GrowthEntity) {
        val id = activeBabyId.first() ?: return
        db.growthDao().insert(entity.copy(babyId = id).stamped())
    }
    suspend fun deleteGrowth(entity: GrowthEntity) = db.growthDao().softDelete(entity.id, now())

    // Treatments (medications/reminders — per active baby)
    val treatments: Flow<List<TreatmentEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.treatmentDao().observeForBaby(id)
    }
    suspend fun addTreatment(entity: TreatmentEntity): Long? {
        val id = activeBabyId.first() ?: return null
        return db.treatmentDao().insert(entity.copy(babyId = id).stamped())
    }
    suspend fun updateTreatment(entity: TreatmentEntity) = db.treatmentDao().update(entity.stamped())
    suspend fun deleteTreatment(entity: TreatmentEntity) = db.treatmentDao().softDelete(entity.id, now())
    suspend fun getTreatment(id: Long): TreatmentEntity? = db.treatmentDao().getById(id)

    /** All active treatments (across babies) that want reminders — for (re)scheduling alarms. */
    suspend fun treatmentsWithReminders(): List<TreatmentEntity> = db.treatmentDao().activeWithReminders()

    // As-needed medicine (BDR-15) — the paracetamol case, per active baby.
    //
    // Two streams rather than one joined read: the medicines change when the
    // parent edits one, the doses change every time one is given, and the
    // screen needs both to draw a traffic light. Joining them in SQL would put
    // the arithmetic somewhere it cannot be unit-tested.

    val medicines: Flow<List<MedicineEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.medicineDao().observeForBaby(id)
    }

    /** Every dose of the active baby's medicines, newest first. */
    val medicineDoses: Flow<List<MedicineDoseEntity>> = activeBabyId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else db.medicineDoseDao().observeForBaby(id)
    }

    suspend fun addMedicine(entity: MedicineEntity): Long? {
        val id = activeBabyId.first() ?: return null
        return db.medicineDao().insert(entity.copy(babyId = id).stamped())
    }

    suspend fun updateMedicine(entity: MedicineEntity) = db.medicineDao().update(entity.stamped())

    /**
     * Removes a medicine and, with it, the doses that were of it.
     *
     * Both soft-deleted in one transaction. Leaving the doses behind would leave
     * rows naming a medicine nothing points at any more — and a medicine added
     * later could not be given the same uid, but a merge carrying the old one
     * back would resurrect a history the parent thought they had removed.
     */
    suspend fun deleteMedicine(entity: MedicineEntity) {
        val deletedAt = now()
        db.withTransaction {
            db.medicineDoseDao().softDeleteForMedicine(entity.uid, deletedAt)
            db.medicineDao().softDelete(entity.id, deletedAt)
        }
    }

    suspend fun getMedicine(id: Long): MedicineEntity? = db.medicineDao().getById(id)

    /** Records a dose as given. Returns the new row id, or null with no active baby. */
    suspend fun addMedicineDose(entity: MedicineDoseEntity): Long? {
        val id = activeBabyId.first() ?: return null
        return db.medicineDoseDao().insert(entity.copy(babyId = id).stamped())
    }

    /**
     * Records a dose of [medicine] at [time], on the baby the medicine belongs
     * to rather than on whichever one is selected.
     *
     * The dashboard offers a dose for every baby in the household at once
     * (BDR-16), and with twins the selected baby and the card that was touched
     * are not the same thing — resolving the baby from the selection is how a
     * sibling's paracetamol ends up in the wrong history.
     *
     * The amount is the medicine's own, copied onto the dose rather than read
     * back through it: the day's total has to keep meaning what it meant when
     * the doses were given, and editing "1.5 cm" to "1 cm" tomorrow must not
     * rewrite what was used yesterday. A parent who used less says so on the
     * dose itself (BDR-18).
     */
    suspend fun giveMedicineDose(medicine: MedicineEntity, time: Long): Long =
        db.medicineDoseDao().insert(
            MedicineDoseEntity(
                babyId = medicine.babyId,
                medicineUid = medicine.uid,
                time = time,
                amount = medicine.doseAmount,
            ).stamped(),
        )

    suspend fun updateMedicineDose(entity: MedicineDoseEntity) =
        db.medicineDoseDao().update(entity.stamped())

    suspend fun deleteMedicineDose(entity: MedicineDoseEntity) =
        db.medicineDoseDao().softDelete(entity.id, now())

    /**
     * The doses of one medicine within the last [windowMs] — everything the
     * traffic light and the daily count need, and nothing more.
     */
    suspend fun recentDosesOf(medicineUid: String, windowMs: Long): List<MedicineDoseEntity> =
        db.medicineDoseDao().recentFor(medicineUid, now() - windowMs)

    /** All active medicines (across babies) that want a reminder — for (re)arming alarms. */
    suspend fun medicinesWithReminders(): List<MedicineEntity> = db.medicineDao().activeWithReminders()

    // Reading one named baby's whole log, for a report (BDR-0012)
    //
    // The screens above always follow the *active* baby, which is right for
    // logging and wrong for exporting: a report says whose record it is on its
    // front page, and the baby it names is the one whose page it was started
    // from. These read that baby by id instead, once, rather than observing.

    suspend fun feedingsForBabyOnce(babyId: Long): List<FeedingEntity> =
        db.feedingDao().observeForBaby(babyId).first()

    suspend fun sleepsForBabyOnce(babyId: Long): List<SleepEntity> =
        db.sleepDao().observeForBaby(babyId).first()

    suspend fun diapersForBabyOnce(babyId: Long): List<DiaperEntity> =
        db.diaperDao().observeForBaby(babyId).first()

    suspend fun growthForBabyOnce(babyId: Long): List<GrowthEntity> =
        db.growthDao().observeForBaby(babyId).first()

    suspend fun treatmentsForBabyOnce(babyId: Long): List<TreatmentEntity> =
        db.treatmentDao().observeForBaby(babyId).first()

    suspend fun medicinesForBabyOnce(babyId: Long): List<MedicineEntity> =
        db.medicineDao().observeForBaby(babyId).first()

    /** Every medicine of a baby, retired ones included, and every dose — for the report. */
    suspend fun allMedicinesForBabyOnce(babyId: Long): List<MedicineEntity> =
        db.medicineDao().allForBabyOnce(babyId)

    suspend fun medicineDosesForBabyOnce(babyId: Long): List<MedicineDoseEntity> =
        db.medicineDoseDao().allForBabyOnce(babyId)

    // Pumping (expressed milk — the parent's stash, not a baby's log)
    val pumpings: Flow<List<PumpingEntity>> = db.pumpingDao().observeAll()
    suspend fun addPumping(entity: PumpingEntity) = db.pumpingDao().insert(entity.stamped())
    suspend fun deletePumping(entity: PumpingEntity) = db.pumpingDao().softDelete(entity.id, now())

    // Wellbeing (parent check-ins — per parent, not per baby)
    val wellbeing: Flow<List<WellbeingEntity>> = db.wellbeingDao().observeAll()
    suspend fun addWellbeing(entity: WellbeingEntity) = db.wellbeingDao().insert(entity)
    suspend fun deleteWellbeing(entity: WellbeingEntity) = db.wellbeingDao().delete(entity)
}
