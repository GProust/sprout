package com.gproust.sprout.data.local

import androidx.room.Entity
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
 * The baby's profile. A single row (id = 1) is used by the app.
 */
@Entity(tableName = "baby")
data class BabyEntity(
    @PrimaryKey val id: Long = 1L,
    val name: String,
    val birthDate: Long,
)

@Entity(tableName = "feeding")
data class FeedingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: FeedType,
    val side: BreastSide? = null,
    val amountMl: Int? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val notes: String? = null,
)

@Entity(tableName = "sleep")
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val startTime: Long,
    val endTime: Long? = null,
    val notes: String? = null,
)

@Entity(tableName = "diaper")
data class DiaperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val time: Long,
    val type: DiaperType,
    val notes: String? = null,
)

@Entity(tableName = "growth")
data class GrowthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
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
)
