package com.gproust.sprout.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Type of a feeding session. */
enum class FeedType { BREAST, BOTTLE, SOLID }

/** Which breast was used during a breastfeeding session. */
enum class BreastSide { LEFT, RIGHT, BOTH }

/** Contents of a diaper change. */
enum class DiaperType { WET, DIRTY, MIXED }

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
@Entity(tableName = "baby")
data class BabyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val birthDate: Long,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
)

@Entity(tableName = "feeding", indices = [Index("babyId")])
data class FeedingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Which baby this entry belongs to; stamped by the repository on insert. */
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val type: FeedType,
    /** Which breast(s) were used; [BreastSide.BOTH] when the session switched sides. */
    val side: BreastSide? = null,
    val amountMl: Int? = null,
    val startTime: Long,
    val endTime: Long? = null,
    /** Time spent on the left breast, in millis (breastfeeding sessions only). */
    val leftDurationMs: Long? = null,
    /** Time spent on the right breast, in millis (breastfeeding sessions only). */
    val rightDurationMs: Long? = null,
    val notes: String? = null,
)

@Entity(tableName = "sleep", indices = [Index("babyId")])
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val startTime: Long,
    val endTime: Long? = null,
    val notes: String? = null,
)

@Entity(tableName = "diaper", indices = [Index("babyId")])
data class DiaperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val time: Long,
    val type: DiaperType,
    val notes: String? = null,
)

@Entity(tableName = "growth", indices = [Index("babyId")])
data class GrowthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(defaultValue = "1") val babyId: Long = 0L,
    val time: Long,
    val weightGrams: Int? = null,
    val heightMm: Int? = null,
    val headMm: Int? = null,
    val notes: String? = null,
)

/**
 * A recurring treatment/medication for a baby — e.g. "Vitamin D, 1 drop, every
 * day for a year". Belongs to a baby (stamped by the repository on insert) and
 * drives optional daily reminders.
 *
 * The schedule is "every [intervalDays] days, at each time in [timesOfDay]",
 * running from [startDate] through [endDate] (inclusive; null = ongoing).
 */
@Entity(tableName = "treatment", indices = [Index("babyId")])
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
)

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
    /** Epoch millis of the last completed/dismissed daily check-in. */
    val lastCheckIn: Long? = null,
    /** Which baby is currently selected for viewing/logging; null if none yet. */
    val activeBabyId: Long? = null,
)
