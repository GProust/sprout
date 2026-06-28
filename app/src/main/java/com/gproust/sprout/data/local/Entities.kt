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
    val side: BreastSide? = null,
    val amountMl: Int? = null,
    val startTime: Long,
    val endTime: Long? = null,
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
