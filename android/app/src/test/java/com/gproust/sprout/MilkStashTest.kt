package com.gproust.sprout

import com.gproust.sprout.data.local.MilkStorage
import com.gproust.sprout.data.local.PumpingEntity
import com.gproust.sprout.ui.pumping.bestBefore
import com.gproust.sprout.ui.pumping.isPastBestBefore
import com.gproust.sprout.ui.pumping.keepsForMillis
import com.gproust.sprout.ui.pumping.milkStash
import com.gproust.sprout.ui.pumping.pumpedSince
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkStashTest {

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    private fun entry(
        amountMl: Int,
        storage: MilkStorage,
        time: Long = now,
    ) = PumpingEntity(time = time, amountMl = amountMl, storage = storage)

    @Test
    fun stash_addsUpPerStorage() {
        val stash = milkStash(
            listOf(
                entry(120, MilkStorage.FRIDGE),
                entry(30, MilkStorage.FRIDGE),
                entry(200, MilkStorage.FREEZER),
                entry(60, MilkStorage.ROOM),
            ),
            now,
        )
        assertEquals(150, stash.fridgeMl)
        assertEquals(200, stash.freezerMl)
        assertEquals(60, stash.roomMl)
        assertEquals(410, stash.totalMl)
        assertFalse(stash.isEmpty)
    }

    @Test
    fun stash_leavesOutUsedMilk() {
        val stash = milkStash(listOf(entry(90, MilkStorage.USED)), now)
        assertEquals(0, stash.totalMl)
        assertTrue(stash.isEmpty)
    }

    @Test
    fun stash_leavesOutMilkPastItsGuidance() {
        val stash = milkStash(
            listOf(
                // Five days old: out of the fridge window (4 days).
                entry(150, MilkStorage.FRIDGE, time = now - 5 * day),
                // Three days old: still counted.
                entry(50, MilkStorage.FRIDGE, time = now - 3 * day),
                // Five days old, but frozen — well within six months.
                entry(200, MilkStorage.FREEZER, time = now - 5 * day),
            ),
            now,
        )
        assertEquals(50, stash.fridgeMl)
        assertEquals(200, stash.freezerMl)
    }

    @Test
    fun bestBefore_followsTheStorageGuidance() {
        assertEquals(now + 4 * hour, bestBefore(entry(100, MilkStorage.ROOM)))
        assertEquals(now + 4 * day, bestBefore(entry(100, MilkStorage.FRIDGE)))
        assertEquals(now + 180 * day, bestBefore(entry(100, MilkStorage.FREEZER)))
        // Used milk is gone: nothing to date.
        assertNull(bestBefore(entry(100, MilkStorage.USED)))
        assertNull(MilkStorage.USED.keepsForMillis)
    }

    @Test
    fun pastBestBefore_isExclusiveOnTheBoundary() {
        val fresh = entry(100, MilkStorage.ROOM, time = now - 4 * hour)
        assertFalse(isPastBestBefore(fresh, now))
        assertTrue(isPastBestBefore(fresh, now + 1))
        // Used milk never reads as expired.
        assertFalse(isPastBestBefore(entry(100, MilkStorage.USED, time = now - 400 * day), now))
    }

    @Test
    fun pumpedSince_countsEverythingExpressed_whereverItWent() {
        val entries = listOf(
            entry(120, MilkStorage.FRIDGE, time = now - hour),
            entry(90, MilkStorage.USED, time = now - 2 * hour),
            entry(80, MilkStorage.FRIDGE, time = now - 30 * hour),
        )
        assertEquals(210, pumpedSince(entries, now - 24 * hour))
        assertEquals(290, pumpedSince(entries, now - 48 * hour))
        assertEquals(0, pumpedSince(entries, now))
    }
}
