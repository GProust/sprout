package com.gproust.sprout.ui.home

import com.gproust.sprout.data.MedicineWatch
import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.medicinesNeedingAttention
import com.gproust.sprout.widget.LAST_BREAST_WINDOW_MS
import com.gproust.sprout.widget.firstNursedSide

/**
 * How far back the dashboard reads. It shows *how long ago* and *how many
 * today*, not a history, so a week is generous: past it a baby simply has no
 * "last feed" to show, which is the honest answer at that point anyway.
 */
const val HOUSEHOLD_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

/**
 * One baby's line on the dashboard: how long since each of the three things a
 * parent asks about, what today adds up to, and which breast is next.
 *
 * Every time here is an epoch millis of the *event*, not an elapsed duration —
 * formatting against "now" is the screen's job, so this stays a pure function
 * of the rows it was given.
 */
data class BabySummary(
    val baby: BabyEntity,
    /** When the last feed of any kind started, or null if none in the window. */
    val lastFeed: Long? = null,
    /** When the last finished sleep ended, or null if none in the window. */
    val lastSleep: Long? = null,
    /** When the last nappy was changed, or null if none in the window. */
    val lastDiaper: Long? = null,
    val feedsToday: Int = 0,
    val sleepTodayMs: Long = 0L,
    val diapersToday: Int = 0,
    /**
     * The breast to start the next feed on — the one the last breastfeed did
     * *not* begin on. Null when nothing was nursed in the last day, in which
     * case there is no alternation to continue and the screen offers both.
     */
    val nextSide: BreastSide? = null,
    /** A sleep that has begun and not ended, or null if this baby is awake. */
    val ongoingSleep: SleepEntity? = null,
    /**
     * The as-needed medicines with something to say right now — a wait running,
     * or one just finished (BDR-16). Empty for a household that is not in the
     * middle of anything, which is what keeps the card off the dashboard rather
     * than showing an empty one.
     */
    val medicines: List<MedicineWatch> = emptyList(),
)

/**
 * Fold the household's recent rows into one summary per baby.
 *
 * [babies] decides both the order and the membership: a baby with no logs at
 * all still gets a card, and rows belonging to an archived or deleted baby are
 * dropped rather than shown without a name.
 */
fun summariseHousehold(
    babies: List<BabyEntity>,
    feedings: List<FeedingEntity>,
    sleeps: List<SleepEntity>,
    diapers: List<DiaperEntity>,
    ongoingSleeps: List<SleepEntity>,
    medicines: List<MedicineEntity> = emptyList(),
    medicineDoses: List<MedicineDoseEntity> = emptyList(),
    /** What *Dismiss* put away: medicine uid to the dose it was dismissed against. */
    dismissedMedicines: Map<String, Long> = emptyMap(),
    dayStart: Long,
    now: Long,
): List<BabySummary> {
    val feedsBy = feedings.groupBy { it.babyId }
    val sleepsBy = sleeps.groupBy { it.babyId }
    val diapersBy = diapers.groupBy { it.babyId }
    val ongoingBy = ongoingSleeps.groupBy { it.babyId }
    val medicinesBy = medicines.groupBy { it.babyId }
    // Doses are filtered per medicine by `medicinesNeedingAttention`, but they
    // are grouped by baby first so one baby's dose can never be counted against
    // another's medicine — the uids make that impossible in practice, and this
    // makes it impossible by construction.
    val dosesBy = medicineDoses.groupBy { it.babyId }

    return babies.map { baby ->
        val babyFeeds = feedsBy[baby.id].orEmpty()
        val babySleeps = sleepsBy[baby.id].orEmpty()
        val babyDiapers = diapersBy[baby.id].orEmpty()
        val ongoingSleep = ongoingBy[baby.id].orEmpty().maxByOrNull { it.startTime }

        BabySummary(
            baby = baby,
            lastFeed = babyFeeds.maxOfOrNull { it.startTime },
            // A sleep still running is reported by [BabySummary.ongoingSleep]; "last
            // slept" is about the last one that finished.
            lastSleep = babySleeps.mapNotNull { it.endTime }.maxOrNull(),
            lastDiaper = babyDiapers.maxOfOrNull { it.time },
            feedsToday = babyFeeds.count { it.startTime >= dayStart },
            sleepTodayMs = sleepMillisSince(babySleeps, dayStart, now),
            diapersToday = babyDiapers.count { it.time >= dayStart },
            nextSide = nextBreast(babyFeeds, now),
            ongoingSleep = ongoingSleep,
            medicines = medicinesNeedingAttention(
                medicines = medicinesBy[baby.id].orEmpty(),
                doses = dosesBy[baby.id].orEmpty(),
                now = now,
                dismissed = dismissedMedicines,
            ),
        )
    }
}

/**
 * How much of [sleeps] falls on or after [dayStart], counting a sleep that is
 * still running as running up to [now].
 *
 * Matches what the daily statistics do, so the dashboard and the Trends screen
 * can never disagree about the same day.
 */
internal fun sleepMillisSince(sleeps: List<SleepEntity>, dayStart: Long, now: Long): Long =
    sleeps.filter { it.startTime >= dayStart }
        .sumOf { ((it.endTime ?: now) - it.startTime).coerceAtLeast(0L) }

/**
 * Which breast to offer next: the other one from where the last breastfeed
 * *began*.
 *
 * This is the widget's rule, reused rather than restated — a session that went
 * left then right still counts as left, and a bottle given in between doesn't
 * take the side with it. Null when nothing was nursed within a day, since by
 * then there is no alternation left to continue.
 */
internal fun nextBreast(feeds: List<FeedingEntity>, now: Long): BreastSide? {
    val lastBreast = feeds
        .filter { it.type == FeedType.BREAST && now - it.startTime <= LAST_BREAST_WINDOW_MS }
        .maxByOrNull { it.startTime }
        ?: return null
    return when (firstNursedSide(lastBreast)) {
        BreastSide.LEFT -> BreastSide.RIGHT
        BreastSide.RIGHT -> BreastSide.LEFT
        // BOTH on an older entry says which breasts were used but not which
        // came first, so it settles nothing; so does a session with no side.
        else -> null
    }
}
