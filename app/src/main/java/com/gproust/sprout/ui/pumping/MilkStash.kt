package com.gproust.sprout.ui.pumping

import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.PumpingEntity

/**
 * How long expressed milk keeps in each place, from the widely published
 * "4 hours – 4 days – 6 months" storage guidance for healthy full-term babies
 * (CDC; Santé publique France gives the same figures). Null for
 * [MilkStorage.USED] — that milk is gone, so nothing is counted or dated.
 *
 * Sprout only ever *shows* these as a reminder that a batch is getting old: it
 * never deletes anything, and a hospital or a premature baby may be given
 * stricter advice.
 */
val MilkStorage.keepsForMillis: Long?
    get() = when (this) {
        MilkStorage.ROOM -> 4 * HOUR
        MilkStorage.FRIDGE -> 4 * DAY
        // "About six months" — counted as 180 days, which is all this precision
        // is worth for a batch that old.
        MilkStorage.FREEZER -> 180 * DAY
        MilkStorage.USED -> null
    }

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** When a batch stops being within its storage guidance; null once used. */
fun bestBefore(entry: PumpingEntity): Long? =
    entry.storage.keepsForMillis?.let { entry.time + it }

/** True once [entry] has been kept longer than its storage guidance allows. */
fun isPastBestBefore(entry: PumpingEntity, now: Long): Boolean =
    bestBefore(entry)?.let { now > it } == true

/**
 * What is in the stash right now, per place, in millilitres. Milk that was used
 * — and milk kept past its guidance (see [keepsForMillis]) — is left out, so
 * the totals are what can actually be given today rather than everything ever
 * expressed.
 */
data class MilkStash(
    val fridgeMl: Int = 0,
    val freezerMl: Int = 0,
    val roomMl: Int = 0,
) {
    val totalMl: Int get() = fridgeMl + freezerMl + roomMl
    val isEmpty: Boolean get() = totalMl == 0
}

fun milkStash(entries: List<PumpingEntity>, now: Long): MilkStash {
    val available = entries.filter { it.storage != MilkStorage.USED && !isPastBestBefore(it, now) }
    fun total(storage: MilkStorage) = available.filter { it.storage == storage }.sumOf { it.amountMl }
    return MilkStash(
        fridgeMl = total(MilkStorage.FRIDGE),
        freezerMl = total(MilkStorage.FREEZER),
        roomMl = total(MilkStorage.ROOM),
    )
}

/** Everything expressed since [from] (inclusive), wherever it ended up. */
fun pumpedSince(entries: List<PumpingEntity>, from: Long): Int =
    entries.filter { it.time >= from }.sumOf { it.amountMl }
