package com.gproust.sprout

import com.gproust.sprout.ui.stats.GrowthMeasure
import com.gproust.sprout.ui.stats.WHO_MAX_AGE_MONTHS
import com.gproust.sprout.ui.stats.WhoSex
import com.gproust.sprout.ui.stats.Z_P15
import com.gproust.sprout.ui.stats.Z_P3
import com.gproust.sprout.ui.stats.Z_P50
import com.gproust.sprout.ui.stats.Z_P85
import com.gproust.sprout.ui.stats.Z_P97
import com.gproust.sprout.ui.stats.ageInMonths
import com.gproust.sprout.ui.stats.percentileOf
import com.gproust.sprout.ui.stats.whoBand
import com.gproust.sprout.ui.stats.whoPlacement
import com.gproust.sprout.ui.stats.whoValueAt
import com.gproust.sprout.ui.stats.whoZScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WHO tables are reference data a parent may act on, so these tests check
 * them against values published by the WHO itself rather than against whatever
 * the code happens to produce: the medians and the ±2 SD figures below are
 * printed in the standards' own z-score tables.
 */
class WhoGrowthTest {

    /** Rounded the way the published tables round, so the two are comparable. */
    private fun oneDecimal(value: Double) = Math.round(value * 10) / 10.0

    @Test
    fun medians_matchThePublishedTables() {
        // Weight-for-age medians, in kg.
        assertEquals(3.3464, whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 0.0, Z_P50)!!, 1e-4)
        assertEquals(3.2322, whoValueAt(GrowthMeasure.WEIGHT, WhoSex.GIRLS, 0.0, Z_P50)!!, 1e-4)
        assertEquals(9.6479, whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 12.0, Z_P50)!!, 1e-4)
        assertEquals(8.9481, whoValueAt(GrowthMeasure.WEIGHT, WhoSex.GIRLS, 12.0, Z_P50)!!, 1e-4)

        // Length-for-age and head-circumference-for-age medians, in cm.
        assertEquals(49.8842, whoValueAt(GrowthMeasure.LENGTH, WhoSex.BOYS, 0.0, Z_P50)!!, 1e-4)
        assertEquals(49.1477, whoValueAt(GrowthMeasure.LENGTH, WhoSex.GIRLS, 0.0, Z_P50)!!, 1e-4)
        assertEquals(34.4618, whoValueAt(GrowthMeasure.HEAD, WhoSex.BOYS, 0.0, Z_P50)!!, 1e-4)
        assertEquals(33.8787, whoValueAt(GrowthMeasure.HEAD, WhoSex.GIRLS, 0.0, Z_P50)!!, 1e-4)
    }

    @Test
    fun standardDeviations_matchThePublishedTables() {
        // Birth weight: the familiar 2.5 kg / 4.4 kg boundaries for a boy.
        assertEquals(2.5, oneDecimal(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 0.0, -2.0)!!), 0.0)
        assertEquals(4.4, oneDecimal(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 0.0, 2.0)!!), 0.0)
        assertEquals(2.4, oneDecimal(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.GIRLS, 0.0, -2.0)!!), 0.0)
        assertEquals(4.2, oneDecimal(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.GIRLS, 0.0, 2.0)!!), 0.0)

        // And two more, further along each table.
        assertEquals(9.8, oneDecimal(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 6.0, 2.0)!!), 0.0)
        assertEquals(43.6, oneDecimal(whoValueAt(GrowthMeasure.LENGTH, WhoSex.GIRLS, 0.0, -3.0)!!), 0.0)
    }

    @Test
    fun zScore_isTheInverseOfTheValue() {
        for (measure in GrowthMeasure.entries) {
            for (sex in WhoSex.entries) {
                for (age in listOf(0.0, 0.7, 5.0, 11.9, 24.0)) {
                    for (z in listOf(-2.5, -1.0, 0.0, 0.5, 2.5)) {
                        val value = whoValueAt(measure, sex, age, z)!!
                        assertEquals(z, whoZScore(measure, sex, age, value)!!, 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun percentiles_matchTheirZScores() {
        assertEquals(3.0, percentileOf(Z_P3), 0.01)
        assertEquals(15.0, percentileOf(Z_P15), 0.01)
        assertEquals(50.0, percentileOf(Z_P50), 0.01)
        assertEquals(85.0, percentileOf(Z_P85), 0.01)
        assertEquals(97.0, percentileOf(Z_P97), 0.01)
        // The tails stay inside the scale rather than wrapping round it.
        assertTrue(percentileOf(-6.0) in 0.0..0.001)
        assertTrue(percentileOf(6.0) in 99.999..100.0)
    }

    @Test
    fun ageBetweenWholeMonths_isInterpolated() {
        val oneMonth = whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 1.0, Z_P50)!!
        val twoMonths = whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 2.0, Z_P50)!!
        val halfway = whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, 1.5, Z_P50)!!
        assertEquals(5.0192, halfway, 1e-3)
        assertTrue(halfway > oneMonth && halfway < twoMonths)
    }

    @Test
    fun pastTheTables_thereIsNoAnswer() {
        assertNull(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, WHO_MAX_AGE_MONTHS + 0.1, Z_P50))
        assertNull(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, -0.1, Z_P50))
        assertNull(whoPlacement(GrowthMeasure.WEIGHT, 30.0, 12.0))
        // The last month itself is covered.
        assertNotNull(whoValueAt(GrowthMeasure.WEIGHT, WhoSex.BOYS, WHO_MAX_AGE_MONTHS, Z_P50))
    }

    @Test
    fun anUnmeasurableValue_hasNoPlacement() {
        assertNull(whoZScore(GrowthMeasure.WEIGHT, WhoSex.BOYS, 3.0, 0.0))
        assertNull(whoPlacement(GrowthMeasure.WEIGHT, 3.0, -1.0))
    }

    @Test
    fun placement_readsBothReferences() {
        // A boy's median weight at 3 months, read as if the sex were unknown:
        // the 50th percentile for boys, higher for girls (whose median is lower).
        val placement = whoPlacement(GrowthMeasure.WEIGHT, 3.0, 6.3762)!!
        assertEquals(50.0, placement.boysPercentile, 0.05)
        assertTrue(placement.girlsPercentile > 50.0)
        assertEquals(placement.boysPercentile, placement.lowPercentile, 0.0)
        assertEquals(placement.girlsPercentile, placement.highPercentile, 0.0)
        assertTrue(placement.insideBand)
    }

    @Test
    fun insideTheBand_isTrueWhenEitherReferenceWouldSaySo() {
        // Just inside the girls' 97th at 6 months is well past the boys' — and
        // still "inside", because the baby may well be a girl.
        val girls97 = whoValueAt(GrowthMeasure.WEIGHT, WhoSex.GIRLS, 6.0, Z_P97)!!
        val justUnder = whoPlacement(GrowthMeasure.WEIGHT, 6.0, girls97 - 0.01)!!
        assertTrue(justUnder.insideBand)

        // Far above both references, it is not.
        assertFalse(whoPlacement(GrowthMeasure.WEIGHT, 6.0, girls97 + 3.0)!!.insideBand)
        // And far below both.
        assertFalse(whoPlacement(GrowthMeasure.WEIGHT, 6.0, 3.0)!!.insideBand)
    }

    @Test
    fun band_isOrderedAndBracketsBothMedians() {
        val band = whoBand(GrowthMeasure.WEIGHT, 0.0, WHO_MAX_AGE_MONTHS, steps = 24)
        assertEquals(25, band.size)
        assertEquals(0.0, band.first().ageMonths, 1e-9)
        assertEquals(WHO_MAX_AGE_MONTHS, band.last().ageMonths, 1e-9)
        band.forEach {
            assertTrue(it.p3 < it.p15)
            assertTrue(it.p15 < minOf(it.girlsMedian, it.boysMedian))
            assertTrue(it.p85 > maxOf(it.girlsMedian, it.boysMedian))
            assertTrue(it.p85 < it.p97)
            // Boys are the heavier reference throughout the first two years.
            assertTrue(it.boysMedian > it.girlsMedian)
        }
        // And the curve rises.
        band.zipWithNext().forEach { (a, b) -> assertTrue(b.boysMedian > a.boysMedian) }
    }

    @Test
    fun band_stopsWhereTheTablesDo() {
        assertTrue(whoBand(GrowthMeasure.WEIGHT, 20.0, 40.0, steps = 20).all { it.ageMonths <= WHO_MAX_AGE_MONTHS })
        assertTrue(whoBand(GrowthMeasure.WEIGHT, 30.0, 40.0, steps = 10).isEmpty())
        assertTrue(whoBand(GrowthMeasure.WEIGHT, 10.0, 5.0, steps = 10).isEmpty())
        assertTrue(whoBand(GrowthMeasure.WEIGHT, steps = 0).isEmpty())
    }

    @Test
    fun ageInMonths_countsWhoMonths() {
        val day = 24 * 60 * 60 * 1000L
        assertEquals(0.0, ageInMonths(0L, 0L), 1e-9)
        // The WHO month is a twelfth of a year: 30.4375 days. Which is why a
        // 365-day-old baby is a shade under twelve months, not exactly twelve.
        assertEquals(1.0, ageInMonths(0L, (30.4375 * day).toLong()), 1e-6)
        assertEquals(12.0, ageInMonths(0L, (365.25 * day).toLong()), 1e-6)
        assertEquals(11.9918, ageInMonths(0L, 365L * day), 1e-4)
    }
}
