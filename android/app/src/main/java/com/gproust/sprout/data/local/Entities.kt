package com.gproust.sprout.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gproust.sprout.data.sync.newUid

/**
 * A row that partner sync can merge between two phones (ADR-0007).
 *
 * The three columns are what make a merge possible at all:
 *
 * - [uid] names the row across devices, since the primary key `id` is only a
 *   local counter. Assigned once, never reused.
 * - [updatedAt] arbitrates two concurrent edits of the same row: the later
 *   write wins.
 * - [deletedAt] is a tombstone. Deleting sets it rather than removing the row,
 *   so the deletion is something we can *tell* the other phone about; without
 *   it, the partner's copy would resurrect the row at the next merge, forever.
 *   Every read filters `deletedAt IS NULL`, and compaction erases tombstones
 *   older than the retention window.
 *
 * The parent's own data ([WellbeingEntity], [ParentProfileEntity]) deliberately
 * does not implement this: it never leaves the device.
 */
interface Syncable {
    val uid: String
    val updatedAt: Long
    val deletedAt: Long?
}

/** Type of a feeding session. */
enum class FeedType { BREAST, BOTTLE, SOLID }

/** Which breast was used during a breastfeeding session. */
enum class BreastSide { LEFT, RIGHT, BOTH }

/**
 * Predefined stool ("selles") colours, offered as a colour scale when logging a
 * diaper change. Inspired by the infant stool colour cards used for newborn
 * cholestasis screening (e.g. the HUG Geneva "carte colorimétrique des selles"):
 * the common healthy colours first, then the pale/acholic range those cards
 * exist to flag, and finally the blood-related colours.
 */
enum class StoolColor { YELLOW, GREEN, BROWN, PALE, CLAY, WHITE, BLACK, RED }

/**
 * Where a batch of expressed milk went after pumping. [USED] is the milk that
 * never went into storage (given straight away, or since drunk/discarded) — it
 * stays in the history but is out of the stash.
 */
enum class MilkStorage { FRIDGE, FREEZER, ROOM, USED }

/**
 * How the baby was lying when they were put down.
 *
 * Recorded, not judged: Sprout shows what was logged and attaches no advice or
 * warning to any of the three (BDR-0014). A parent asking "was it the belly
 * naps that went better?" is reading their own log, not being marked on it.
 */
enum class SleepPosition { BACK, SIDE, BELLY }

/**
 * Where the baby slept. [OTHER] carries the parent's own name for the place in
 * [SleepEntity.placeNote] — a pram, a car seat, grandmother's arms — because a
 * list of six can't cover where a baby actually falls asleep.
 */
enum class SleepPlace { OWN_BED, BEDSIDE_COT, PARENTS_BED, ON_A_PARENT, AT_BREAST, OTHER }

/**
 * How much of a food a solid meal held: [TASTE] is a lick of the spoon or a
 * piece to explore, [SERVING] a real helping. Both are an offer of the food,
 * and both count (BDR-20).
 */
enum class FoodPortion { TASTE, SERVING }

/**
 * How a food went at one meal, when the parent noticed — their reading, not
 * Sprout's. Null is "not noted", never a fourth answer, and [REFUSED] is a fact
 * about one meal rather than a reaction: there is deliberately no allergy field
 * (BDR-20).
 */
enum class FoodReception { LIKED, MIXED, REFUSED }

/**
 * One food in a solid meal (BDR-20): one from Sprout's list, or one the
 * household typed in.
 *
 * [ref] is the [com.gproust.sprout.data.FoodCatalog] id of a food from the list,
 * and what makes it the same food on every phone whatever language each is in;
 * [name] is still saved with it, as it read when it was logged, so a phone that
 * does not know the id has something to show. A food the household added has no
 * [ref] and is its [name], matched ignoring case and spacing — see `foodKey`.
 */
data class MealFood(
    val name: String,
    val portion: FoodPortion = FoodPortion.TASTE,
    val reception: FoodReception? = null,
    val ref: String? = null,
)

/** Postpartum bleeding (lochia) intensity. */
enum class Bleeding { NONE, LIGHT, MODERATE, HEAVY }

/** Breast comfort state (for a breastfeeding parent). */
enum class BreastState { NORMAL, TENDER, ENGORGED, PAINFUL }

/** How a parent's postpartum recovery / healing feels. */
enum class Recovery { GREAT, GOOD, SORE, PAINFUL }

/** How the baby was delivered — tailors the healing question. */
enum class DeliveryType { VAGINAL, CESAREAN }

/**
 * A baby's profile. The app supports several (twins, or siblings over time);
 * each gets an auto-generated [id]. [archived] babies are kept but hidden from
 * the active rotation ("stop tracking") so their history isn't lost.
 */
@Entity(tableName = "baby", indices = [Index(value = ["uid"], unique = true)])
data class BabyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val birthDate: Long,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /**
     * Per-baby feeding-reminder override. Null means "follow the device-global
     * setting"; non-null overrides whether this baby is reminded and after how
     * long. The two fields move together in the UI (a baby either uses the
     * default or has its own custom setting), but stay independently nullable so
     * the effective value resolves each field as `override ?? global`.
     */
    val feedingReminderEnabled: Boolean? = null,
    val feedingReminderIntervalMinutes: Int? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * One uninterrupted stretch on a single breast within a breastfeeding session —
 * the unit a live timer records between switches. [side] is only LEFT or RIGHT.
 */
data class NursingSegment(
    val side: BreastSide,
    val startTime: Long,
    val endTime: Long,
)

@Entity(
    tableName = "feeding",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class FeedingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Which baby this entry belongs to; stamped by the repository on insert. */
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val type: FeedType,
    /** Which breast(s) were used; [BreastSide.BOTH] when the session switched sides. */
    val side: BreastSide? = null,
    val amountMl: Int? = null,
    /**
     * How much solid food was eaten, in grams ([FeedType.SOLID] only). Null on
     * every other kind of feed, and on solids logged before there was anywhere
     * to put the number — so an absent amount reads as "not recorded" rather
     * than as zero, and the statistics leave those days' totals alone.
     */
    val amountGrams: Int? = null,
    val startTime: Long,
    val endTime: Long? = null,
    /** Time spent on the left breast, in millis (breastfeeding sessions only). */
    val leftDurationMs: Long? = null,
    /** Time spent on the right breast, in millis (breastfeeding sessions only). */
    val rightDurationMs: Long? = null,
    /**
     * The ordered back-and-forth segments of a breastfeed — each an uninterrupted
     * stretch on one breast, with its own start/end. Empty for non-breast feeds
     * and for entries logged before per-segment timing was tracked (those fall
     * back to [leftDurationMs] / [rightDurationMs]).
     */
    @ColumnInfo(defaultValue = "''") val segments: List<NursingSegment> = emptyList(),
    /**
     * What a [FeedType.SOLID] meal was made of, in the order the parent listed
     * it (BDR-20). Empty on every other kind of feed, and on every solid meal
     * logged before foods could be listed — which reads as a meal that named
     * nothing, not as a meal of nothing. The meal's weight stays [amountGrams]:
     * what gets weighed is the bowl, not each food in it.
     */
    @ColumnInfo(defaultValue = "''") val foods: List<MealFood> = emptyList(),
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

@Entity(
    tableName = "sleep",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val startTime: Long,
    val endTime: Long? = null,
    /** How they were lying, when it was noted; null is "not recorded". */
    val position: SleepPosition? = null,
    /** Where they slept, when it was noted; null is "not recorded". */
    val place: SleepPlace? = null,
    /**
     * The parent's own name for the place, for [SleepPlace.OTHER] only. Blank
     * or null there means "somewhere else" with nothing more said; the
     * statistics group named places by this text, so it is what a parent typed
     * rather than anything the app chose.
     */
    val placeNote: String? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * A diaper change. Rather than a single mutually-exclusive type, a change is a
 * checklist of what was present: [wet] (urines) and/or [dirty] (selles). When
 * stool is present, [stoolColor] optionally records its colour.
 */
@Entity(
    tableName = "diaper",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class DiaperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val time: Long,
    /** Urine present in this change ("urines"). */
    @ColumnInfo(defaultValue = "0") val wet: Boolean = false,
    /** Stool present in this change ("selles"). */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    /** Stool colour, when stool is present; null otherwise. */
    val stoolColor: StoolColor? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

@Entity(
    tableName = "growth",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class GrowthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val time: Long,
    val weightGrams: Int? = null,
    val heightMm: Int? = null,
    val headMm: Int? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * A recurring treatment/medication for a baby — e.g. "Vitamin D, 1 drop, every
 * day for a year". Belongs to a baby (stamped by the repository on insert) and
 * drives optional daily reminders.
 *
 * The schedule is "every [intervalDays] days, at each time in [timesOfDay]",
 * running from [startDate] through [endDate] (inclusive; null = ongoing).
 */
@Entity(
    tableName = "treatment",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class TreatmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Which baby this treatment is for; stamped by the repository on insert. */
    val babyId: Long = 0L,
    val name: String,
    /** Optional dose description, e.g. "400 IU" or "1 drop". */
    val dose: String? = null,
    /** Days between doses: 1 = daily, 7 = weekly, N = every N days. */
    val intervalDays: Int = 1,
    /** Reminder times on a dosing day, as minutes since midnight (e.g. 540 = 09:00). */
    val timesOfDay: List<Int> = listOf(9 * 60),
    /** First dosing day (epoch millis). */
    val startDate: Long,
    /** Last dosing day, inclusive (epoch millis); null = ongoing. */
    val endDate: Long? = null,
    val remindersEnabled: Boolean = true,
    /** False once the treatment is removed/stopped; kept out of the active list. */
    val active: Boolean = true,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * A medicine given **when it is needed** rather than on a calendar — the
 * paracetamol case (BDR-15). The course-shaped counterpart is [TreatmentEntity].
 *
 * Everything here is the parent's own number, typed from a prescriber or a
 * leaflet. Sprout ships no drug list, no default dose and no interval for any
 * named substance: it counts the hours between the doses in
 * [MedicineDoseEntity] and restates these figures back, and it supplies no
 * medical knowledge of its own.
 */
@Entity(
    tableName = "medicine",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Which baby this medicine is for; stamped by the repository on insert. */
    val babyId: Long = 0L,
    val name: String,
    /** Optional dose description, e.g. "2.5 ml" or "half a sachet". */
    val dose: String? = null,
    /**
     * The shortest gap the parent was told to keep, in minutes. Before it has
     * elapsed the medicine reads red.
     *
     * **Zero means there is no gap rule at all** — a teething gel is "six times
     * a day", with nothing said about how far apart. Such a medicine is held
     * only by [maxPerDay] and [maxAmountPerDay], and reads green until one of
     * those is spent (BDR-18).
     */
    val minIntervalMinutes: Int,
    /**
     * The gap the prescriber actually wants kept, in minutes; null when only a
     * minimum was given. Between the two the medicine reads amber — allowed,
     * sooner than ideal — and a null here means there is no amber band at all.
     */
    val comfortIntervalMinutes: Int? = null,
    /**
     * Most doses allowed in twenty-four hours; null when the parent set none.
     * A limit an interval cannot express: the wait can elapse while the day's
     * allowance is spent.
     */
    val maxPerDay: Int? = null,
    /**
     * How much one application uses, as a number — 1.5 for "1.5 cm of gel".
     * Null when the parent never gave one, which is every medicine measured in
     * whole doses rather than in a quantity.
     *
     * [dose] stays the sentence the card shows ("2.5 ml", "half a sachet");
     * this is the same figure in a form that can be added up, and it is only
     * ever what the parent typed.
     */
    val doseAmount: Double? = null,
    /**
     * The parent's own unit for [doseAmount] and [maxAmountPerDay] — "cm",
     * "ml", "mg", "puffs". Free text and never a list Sprout ships: a list of
     * units is one step from a list of medicines, which is the addition this
     * feature must not grow (BDR-15).
     */
    val doseUnit: String? = null,
    /**
     * The most of [doseUnit] allowed in twenty-four hours — "no more than
     * 1.5 cm of gel a day". Null when the parent set none.
     *
     * A second kind of daily ceiling beside [maxPerDay], because a leaflet
     * often gives both and they do not imply each other: six small applications
     * and three generous ones can reach the same quantity.
     */
    val maxAmountPerDay: Double? = null,
    /** Opt-in, off until asked for, like every other notification here. */
    val remindWhenDue: Boolean = false,
    /**
     * Which wait the reminder fires at: the comfortable interval when true, the
     * minimum when false. Ignored when [comfortIntervalMinutes] is null, since
     * there is then only one boundary to fire at.
     */
    val remindAtComfort: Boolean = false,
    /** False once the parent stops using it; kept out of the active list. */
    val active: Boolean = true,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * Something a baby is allergic to, as the household came to know it (BDR-21).
 *
 * The parent's record and nothing more: what it is, when it was noticed, and
 * whether a health professional confirmed it. Sprout does not diagnose,
 * suggest or infer one — a food refused three times is not an allergy, and a
 * rash is a note on a meal until a parent decides otherwise — and nothing
 * here is checked against a list of allergens, because Sprout ships none.
 *
 * What it names is a food in the First foods sense (BDR-20): [ref] is its id
 * on Sprout's list when it was picked from there, so the label follows the
 * carrot in every language; otherwise [name] is the parent's own words, which
 * can be anything — cow's milk protein, egg, a medicine.
 */
@Entity(
    tableName = "allergy",
    indices = [Index("babyId"), Index(value = ["uid"], unique = true)],
)
data class AllergyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Whose allergy it is; stamped by the repository on insert. */
    val babyId: Long = 0L,
    /** What it is, as it was written when noted — the list's name, or the parent's. */
    val name: String,
    /** Its id on Sprout's food list ([com.gproust.sprout.data.FoodCatalog]), or null. */
    val ref: String? = null,
    /** The day it was first noticed (epoch millis), when the parent gave one. */
    val noticedAt: Long? = null,
    /**
     * Whether a health professional confirmed it. False is "noted by us", not
     * "doubtful": it is the parent's statement either way, and the document
     * that leaves says which.
     */
    val confirmed: Boolean = false,
    /** What was seen, what the doctor said — the parent's words, as typed. */
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * One dose of a [MedicineEntity], actually given. These are the record; the
 * traffic light is derived from them every time it is asked for, so that a
 * correction or a deletion moves it (BDR-15).
 *
 * It points at its medicine by **uid**, not by the local `id`: `id` is a
 * per-device counter and would name a different row on the other phone. Storing
 * the uid rather than resolving one at merge time also means a dose cannot be
 * attached to the wrong medicine by an exchange that arrived out of order.
 */
@Entity(
    tableName = "medicine_dose",
    indices = [
        Index("babyId"),
        Index("medicineUid"),
        Index(value = ["uid"], unique = true),
    ],
)
data class MedicineDoseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val babyId: Long = 0L,
    /** The [MedicineEntity.uid] this dose was of. */
    val medicineUid: String,
    /** When it was given (epoch millis) — the wait is counted from here. */
    val time: Long,
    /**
     * How much was actually used, in the medicine's own
     * [MedicineEntity.doseUnit]. Filled in from
     * [MedicineEntity.doseAmount] when the dose is logged and editable
     * afterwards, because "I only used half of it" is a correction a parent
     * makes and the day's total has to follow it.
     *
     * Null for every dose of a medicine that carries no amount, and for every
     * dose logged before amounts existed — which is a dose that said nothing
     * about quantity, not a dose of nothing.
     */
    val amount: Double? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * One pumping session: when it happened, how much milk came out and where that
 * milk went (fridge, freezer, room temperature — or straight to the baby).
 *
 * Like the wellbeing check-ins, and unlike the feeding/sleep/diaper logs, this
 * belongs to the *parent* rather than to a baby: the stash is expressed by one
 * body and, with twins, feeds either of them. So there is no `babyId`, and
 * deleting a baby leaves the pumping history alone.
 */
@Entity(tableName = "pumping", indices = [Index(value = ["uid"], unique = true)])
data class PumpingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** When the milk was expressed (epoch millis) — the stash ages from here. */
    val time: Long,
    val amountMl: Int,
    /** Which breast(s) were expressed; null when it wasn't recorded. */
    val side: BreastSide? = null,
    val storage: MilkStorage = MilkStorage.FRIDGE,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "''") override val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") override val updatedAt: Long = 0L,
    override val deletedAt: Long? = null,
) : Syncable

/**
 * A parent's daily wellbeing check-in. Mood and notes apply to everyone;
 * the postpartum fields (bleeding, recovery) and breast comfort are only
 * filled in when they're relevant to that parent.
 */
@Entity(tableName = "wellbeing")
data class WellbeingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val time: Long,
    /** Mood on a 1 (low) to 5 (great) scale. */
    val mood: Int,
    val bleeding: Bleeding? = null,
    val recovery: Recovery? = null,
    val breast: BreastState? = null,
    val notes: String? = null,
)

/**
 * The profile of the parent using this device (a single row, id = 1).
 * Capabilities ([gaveBirth], [breastfeeding]) — not a fixed role — decide which
 * wellbeing questions are shown, so every kind of family is covered. Stored
 * per-device so that, once partner sync arrives, each phone carries its own
 * parent identity.
 */
@Entity(tableName = "parent_profile")
data class ParentProfileEntity(
    @PrimaryKey val id: Long = 1L,
    val name: String,
    val gaveBirth: Boolean = false,
    val breastfeeding: Boolean = false,
    /** Only meaningful when [gaveBirth] is true; tailors the healing question. */
    val deliveryType: DeliveryType? = null,
    /**
     * Whether the daily check-in still asks each body question. [askHealing]
     * and [askBleeding] only matter when [gaveBirth] is true, [askBreasts]
     * when [breastfeeding] is true. The parent can opt out of any of them once
     * it no longer needs a daily look — right from the check-in, or with the
     * Settings toggles that also turn them back on.
     */
    @ColumnInfo(defaultValue = "1") val askHealing: Boolean = true,
    @ColumnInfo(defaultValue = "1") val askBleeding: Boolean = true,
    @ColumnInfo(defaultValue = "1") val askBreasts: Boolean = true,
    /**
     * Whether the parent tracks their own wellbeing at all. Chosen during
     * onboarding, alongside their name, and changeable in Settings. When false,
     * the dashboard stops offering the daily check-in and drops the wellbeing
     * shortcut — nothing is deleted, and turning it back on brings the history
     * back with it.
     */
    @ColumnInfo(defaultValue = "1") val trackWellbeing: Boolean = true,
    /** Epoch millis of the last completed/dismissed daily check-in. */
    val lastCheckIn: Long? = null,
    /** Which baby is currently selected for viewing/logging; null if none yet. */
    val activeBabyId: Long? = null,
)

/**
 * The record that a row was deleted, for rows that no longer exist to carry a
 * `deletedAt` of their own.
 *
 * An ordinary delete is soft: the row stays, flagged, and *is* its own
 * tombstone. But "permanently delete a baby and all of their logs" promises the
 * data is really gone, and keeping it flagged-but-present would quietly break
 * that promise. So those rows are erased and only their [uid] is kept here —
 * enough to tell the partner's phone "this is deleted, don't send it back",
 * and nothing about what the entry contained.
 *
 * Compacted on the same retention window as soft-deleted rows.
 */
@Entity(tableName = "tombstone")
data class TombstoneEntity(
    @PrimaryKey val uid: String,
    /** The table the erased row belonged to, e.g. `feeding`. */
    val entity: String,
    val deletedAt: Long,
)
