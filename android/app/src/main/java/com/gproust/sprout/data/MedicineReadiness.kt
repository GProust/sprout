package com.gproust.sprout.data

import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity

/**
 * Whether an as-needed medicine may be given yet, worked out from the doses
 * that were actually logged (BDR-15).
 *
 * **Arithmetic only.** No sentence, no colour, no resource id — those are the
 * screen's job, and this has to be unit-testable without a device. iOS computes
 * the same answer in `SproutData/MedicineReadiness.swift`; the two are the same
 * rules written twice, and their tests share the same cases.
 *
 * Nothing here is a medical judgement. Every number it reads
 * ([MedicineEntity.minIntervalMinutes], [MedicineEntity.comfortIntervalMinutes],
 * [MedicineEntity.maxPerDay]) was typed in by the parent from a prescriber or a
 * leaflet; this counts hours and hands the figures back.
 */

/** How long a daily maximum is counted over. */
const val MEDICINE_DAY_MS: Long = 24L * 60 * 60 * 1000

/**
 * The three states, in the order a wait passes through them.
 *
 * The names say what they mean rather than what colour they are drawn in: a
 * screen that only knew "amber" could not write the sentence that goes with it,
 * and the colour is never the only channel this is shown through.
 */
enum class MedicineLevel {
    /** Too soon: the minimum wait has not elapsed, or the daily maximum is spent. */
    TOO_SOON,

    /** Past the minimum, not yet past the comfortable interval. Allowed, sooner than ideal. */
    SOONER_THAN_IDEAL,

    /** The full wait has passed — or there was only ever one boundary, and it has. */
    READY,
}

/** Why a medicine is [MedicineLevel.TOO_SOON]; null in every other state. */
enum class TooSoonReason {
    /** The minimum gap between doses has not elapsed. */
    INTERVAL,

    /** The parent's daily maximum has been reached; the interval alone would allow it. */
    DAILY_MAXIMUM,

    /**
     * The day's allowed *quantity* is used up — 1.5 cm of gel, not six of six
     * doses. A second ceiling because a leaflet often gives both, and three
     * generous applications can reach the amount before the count (BDR-18).
     */
    DAILY_AMOUNT,
}

/**
 * How close two amounts have to be to count as equal.
 *
 * Amounts are the parent's own decimals and are added up in binary floating
 * point, where 0.3 three times is not 0.9. Without a tolerance a day that has
 * exactly reached its limit could read as a hair under it — and since nothing
 * here blocks a dose anyway, the honest thing is for the arithmetic to agree
 * with the parent's own sum rather than with the last bit of a Double.
 */
private const val MEDICINE_AMOUNT_TOLERANCE = 1e-6

/**
 * What the screen draws, and what a reminder is armed from.
 *
 * @property level which of the three states the medicine is in.
 * @property reason why, when [level] is [MedicineLevel.TOO_SOON].
 * @property lastDoseAt when the last dose was, or null if it has never been given.
 * @property nextAllowedAt the moment [level] stops being [MedicineLevel.TOO_SOON];
 *   null when it already is not.
 * @property comfortableAt the moment the comfortable interval elapses; null when
 *   the medicine has none, or it already has.
 * @property dosesInLastDay how many doses were given in the last 24 hours.
 * @property maxPerDay the parent's own daily maximum, echoed back for the screen
 *   to draw the count against; null when they set none.
 * @property amountInLastDay how much was used in the last 24 hours, in the
 *   medicine's own unit. Zero when nothing was recorded, which is not the same
 *   as nothing being used — see [MedicineDoseEntity.amount].
 * @property maxAmountPerDay the parent's own daily quantity, echoed back beside
 *   [amountInLastDay]; null when they set none.
 * @property unit the medicine's own unit for the two amounts, echoed back so the
 *   screen does not have to carry the medicine as well.
 */
data class MedicineReadiness(
    val level: MedicineLevel,
    val reason: TooSoonReason? = null,
    val lastDoseAt: Long? = null,
    val nextAllowedAt: Long? = null,
    val comfortableAt: Long? = null,
    val dosesInLastDay: Int = 0,
    val maxPerDay: Int? = null,
    val amountInLastDay: Double = 0.0,
    val maxAmountPerDay: Double? = null,
    val unit: String? = null,
)

/**
 * Works out where [medicine] stands at [now], given [doses].
 *
 * [doses] may be in any order and may contain doses of other medicines or from
 * outside the window — they are filtered here rather than at every call site, so
 * that a screen holding one list of every dose can ask about each medicine in
 * turn without slicing it up first.
 *
 * A medicine that has never been given is [MedicineLevel.READY]: no wait is
 * running, so there is nothing to wait for. That is the state the screen should
 * open in for a medicine added a moment ago, and the alternative — red, with an
 * unknown countdown — would be a lie.
 *
 * A medicine with **no gap rule at all** ([MedicineEntity.minIntervalMinutes]
 * zero) is held only by its daily ceilings: it reads green from the moment it
 * is given until the day's doses or the day's quantity run out (BDR-18).
 */
fun medicineReadiness(
    medicine: MedicineEntity,
    doses: List<MedicineDoseEntity>,
    now: Long,
): MedicineReadiness {
    val mine = doses
        .filter { it.medicineUid == medicine.uid && it.deletedAt == null }
        .sortedByDescending { it.time }

    // Doses in the future are the parent correcting a time, or two phones whose
    // clocks disagree. They still count as given — a dose logged at 21:05 by a
    // phone five minutes fast is the dose that was just given, not one to
    // ignore — so the wait runs from the latest of them either way.
    val lastDoseAt = mine.firstOrNull()?.time
        ?: return MedicineReadiness(
            level = MedicineLevel.READY,
            maxPerDay = medicine.maxPerDay,
            maxAmountPerDay = medicine.maxAmountPerDay,
            unit = medicine.doseUnit,
        )

    val minGapMs = medicine.minIntervalMinutes.coerceAtLeast(0) * 60_000L
    val intervalEndsAt = lastDoseAt + minGapMs

    val comfortEndsAt = medicine.comfortIntervalMinutes
        // A comfortable interval shorter than the minimum is a typo, not a
        // second boundary. Taking the larger keeps the two in the order the
        // states assume rather than producing an amber band that ends before
        // it begins.
        ?.let { lastDoseAt + maxOf(it.coerceAtLeast(0) * 60_000L, minGapMs) }

    // The daily maximum, counted over a rolling 24 hours rather than a calendar
    // day: "no more than four in a day" is about the last day, and a calendar
    // reset would allow four at 23:00 and four more at 00:30.
    val windowStart = now - MEDICINE_DAY_MS
    val inWindow = mine.filter { it.time > windowStart && it.time <= now }
    val cap = medicine.maxPerDay?.takeIf { it > 0 }
    val capReachedUntil = cap
        ?.takeIf { inWindow.size >= it }
        // `inWindow` is newest-first, so the cap-th most recent dose is the one
        // that has to age out of the window before another is allowed.
        ?.let { inWindow[it - 1].time + MEDICINE_DAY_MS }

    // The day's quantity, the same rolling window and the same shape as the
    // count. Doses that recorded no amount add nothing — the arithmetic cannot
    // total what was never typed, and guessing a size for them would be the app
    // inventing a figure the parent never gave.
    val amountUsed = inWindow.sumOf { it.amount ?: 0.0 }
    val amountCap = medicine.maxAmountPerDay?.takeIf { it > 0 }
    val amountReachedUntil = amountCap?.let { amountFreeAt(inWindow, it) }

    val blockedUntil = listOfNotNull(
        intervalEndsAt.takeIf { it > now },
        capReachedUntil?.takeIf { it > now },
        amountReachedUntil?.takeIf { it > now },
    ).maxOrNull()

    if (blockedUntil != null) {
        return MedicineReadiness(
            // The interval is named only when it is the thing still running: once
            // it has elapsed, "the daily maximum" is the honest answer to why the
            // screen is still red.
            level = MedicineLevel.TOO_SOON,
            reason = when {
                // The interval keeps its precedence: while it is running it is
                // what the parent is waiting on first, whichever ceiling also
                // happens to be spent.
                intervalEndsAt > now -> TooSoonReason.INTERVAL
                capReachedUntil != null && capReachedUntil > now -> TooSoonReason.DAILY_MAXIMUM
                else -> TooSoonReason.DAILY_AMOUNT
            },
            lastDoseAt = lastDoseAt,
            nextAllowedAt = blockedUntil,
            comfortableAt = comfortEndsAt?.takeIf { it > now },
            dosesInLastDay = inWindow.size,
            maxPerDay = medicine.maxPerDay,
            amountInLastDay = amountUsed,
            maxAmountPerDay = medicine.maxAmountPerDay,
            unit = medicine.doseUnit,
        )
    }

    val level = if (comfortEndsAt != null && comfortEndsAt > now) {
        MedicineLevel.SOONER_THAN_IDEAL
    } else {
        MedicineLevel.READY
    }

    return MedicineReadiness(
        level = level,
        lastDoseAt = lastDoseAt,
        comfortableAt = comfortEndsAt?.takeIf { it > now },
        dosesInLastDay = inWindow.size,
        maxPerDay = medicine.maxPerDay,
        amountInLastDay = amountUsed,
        maxAmountPerDay = medicine.maxAmountPerDay,
        unit = medicine.doseUnit,
    )
}

/**
 * When the day's quantity drops back below [max], or null while it never
 * reached it.
 *
 * The same rule the dose count uses, applied to a total rather than a tally:
 * doses age out of the rolling window oldest first, and the moment the ones
 * still inside it add up to less than the limit is the moment there is room
 * again. Doses that recorded no amount contribute nothing on the way in and
 * free nothing on the way out.
 */
private fun amountFreeAt(inWindow: List<MedicineDoseEntity>, max: Double): Long? {
    val oldestFirst = inWindow.sortedBy { it.time }
    var remaining = oldestFirst.sumOf { it.amount ?: 0.0 }
    if (remaining < max - MEDICINE_AMOUNT_TOLERANCE) return null

    for (dose in oldestFirst) {
        remaining -= dose.amount ?: 0.0
        if (remaining < max - MEDICINE_AMOUNT_TOLERANCE) return dose.time + MEDICINE_DAY_MS
    }
    // One dose was the whole day's allowance on its own: nothing is free until
    // the last of them has aged out.
    return oldestFirst.lastOrNull()?.time?.plus(MEDICINE_DAY_MS)
}

/**
 * One medicine the dashboard should mention, and where it stands.
 *
 * The readiness is carried rather than recomputed by the screen, so the card
 * and the medicines screen cannot disagree about the same medicine at the same
 * instant.
 */
data class MedicineWatch(
    val medicine: MedicineEntity,
    val readiness: MedicineReadiness,
)

/**
 * The medicines worth a line on the dashboard (BDR-16).
 *
 * A medicine earns its place when **a wait is running** — it cannot be given
 * yet, or it can but sooner than ideal — or when it was **given within the last
 * day and the wait has since passed**. That last case is the one a parent is
 * actually waiting for, and the dashboard is where they should not have to go
 * looking for it.
 *
 * A medicine that has never been given, or whose last dose is older than the
 * window, is left off: it is set up rather than in play, and the screen that
 * lists every medicine is one tap away. That is what keeps this card absent
 * from the dashboard of a household that is not in the middle of anything.
 *
 * Ordered by **how freely it may be given**: the full wait passed, then allowed
 * but sooner than ideal, then not yet — with the soonest wait first inside each
 * group and the name breaking the last tie, so the list does not reshuffle under
 * a parent who is reading it.
 *
 * Green above amber, and not merely "givable first". Both may be given, but one
 * of them is the dose the parent was told to give and the other is the dose they
 * are allowed to give early — so the medicine that needs no second thought is the
 * one at the top. It is an ordering, not a recommendation: the amber line says
 * what it says, and its button works exactly as well (BDR-15).
 *
 * [dismissed] is what *Dismiss* on the card put away, as medicine uid to the
 * dose it was dismissed against. A dismissal therefore expires by itself: give
 * another dose and the medicine's last dose is no longer the one that was put
 * away, so it comes back. There is nothing to clear and nothing to leak — a
 * medicine that is deleted takes its entry out of use with it.
 */
fun medicinesNeedingAttention(
    medicines: List<MedicineEntity>,
    doses: List<MedicineDoseEntity>,
    now: Long,
    dismissed: Map<String, Long> = emptyMap(),
): List<MedicineWatch> = medicines
    .asSequence()
    .filter { it.active && it.deletedAt == null }
    .map { MedicineWatch(it, medicineReadiness(it, doses, now)) }
    // Never given is not "in play": there is no wait to report and nothing has
    // happened that the dashboard needs to carry.
    .filter { it.readiness.lastDoseAt != null }
    .filter { it.readiness.level != MedicineLevel.READY || it.readiness.dosesInLastDay > 0 }
    .filter { dismissed[it.medicine.uid] != it.readiness.lastDoseAt }
    // Rank first, then the moment the group's own wait ends — `nextAllowedAt`
    // for a medicine still too soon, `comfortableAt` for one that is early, and
    // neither for one that is simply ready. Both are null in that last case, so
    // the elvis leaves the name to order them.
    .sortedWith(
        compareBy(
            { it.readiness.level.givingOrder },
            { it.readiness.nextAllowedAt ?: it.readiness.comfortableAt ?: 0L },
            { it.medicine.name },
        ),
    )
    .toList()

/**
 * Where a state sits when the dashboard asks "what may I give?".
 *
 * Written out rather than taken from [MedicineLevel]'s `ordinal`, which runs the
 * other way: that enum is declared in the order a *wait* passes through, and this
 * is the order a *parent* wants to read.
 */
private val MedicineLevel.givingOrder: Int
    get() = when (this) {
        MedicineLevel.READY -> 0
        MedicineLevel.SOONER_THAN_IDEAL -> 1
        MedicineLevel.TOO_SOON -> 2
    }

/**
 * When to remind the parent that [medicine] can be given again, or null when it
 * wants no reminder, is already there, or has never been given.
 *
 * The boundary is the parent's own choice ([MedicineEntity.remindAtComfort]),
 * and the daily maximum can push it later than either interval — being told a
 * medicine is available while the day's allowance is spent would be the app
 * getting its own arithmetic wrong out loud.
 *
 * Strictly after [now]: a moment that has already passed is not a reminder, and
 * delivering one late tells a parent something they can already see (ADR-0019).
 */
fun nextMedicineReminder(
    medicine: MedicineEntity,
    doses: List<MedicineDoseEntity>,
    now: Long,
): Long? {
    if (!medicine.remindWhenDue || !medicine.active || medicine.deletedAt != null) return null
    val readiness = medicineReadiness(medicine, doses, now)
    if (readiness.lastDoseAt == null) return null

    val wanted = if (medicine.remindAtComfort) {
        // Falls back to the minimum when there is no comfortable interval: the
        // switch is then about *whether* to remind, and there is one boundary to
        // remind at.
        readiness.comfortableAt ?: readiness.nextAllowedAt
    } else {
        readiness.nextAllowedAt
    }
    return listOfNotNull(wanted, readiness.nextAllowedAt).maxOrNull()?.takeIf { it > now }
}
