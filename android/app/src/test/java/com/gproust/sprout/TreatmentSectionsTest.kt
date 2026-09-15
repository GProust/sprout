package com.gproust.sprout

import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.ui.treatments.hasEnded
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Which side of the treatments list a course lands on. The end date is
 * inclusive and the comparison is by calendar day, so the boundary cases —
 * a course whose last dose is today, one that ends at one minute past
 * midnight — are the ones worth pinning down.
 */
class TreatmentSectionsTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 3, 15)
    private val now = today.atTime(14, 30).atZone(zone).toInstant().toEpochMilli()

    private fun millisAt(date: LocalDate, time: LocalTime = LocalTime.NOON): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun treatment(endDate: Long?) = TreatmentEntity(
        name = "Vitamin D",
        startDate = millisAt(today.minusDays(30)),
        endDate = endDate,
    )

    @Test
    fun noEndDate_isNeverPast() {
        assertFalse(hasEnded(treatment(endDate = null), now))
    }

    @Test
    fun endsToday_staysActive() {
        assertFalse(hasEnded(treatment(millisAt(today)), now))
        // Even when the stored instant is earlier in the day than "now".
        assertFalse(hasEnded(treatment(millisAt(today, LocalTime.of(0, 1))), now))
    }

    @Test
    fun endedYesterday_isPast() {
        assertTrue(hasEnded(treatment(millisAt(today.minusDays(1))), now))
        // Right up to the last minute of yesterday.
        assertTrue(hasEnded(treatment(millisAt(today.minusDays(1), LocalTime.of(23, 59))), now))
    }

    @Test
    fun endsInTheFuture_isActive() {
        assertFalse(hasEnded(treatment(millisAt(today.plusDays(1))), now))
        assertFalse(hasEnded(treatment(millisAt(today.plusYears(1))), now))
    }

    @Test
    fun notStartedYet_isActiveNotPast() {
        val upcoming = TreatmentEntity(
            name = "Iron",
            startDate = millisAt(today.plusDays(7)),
            endDate = millisAt(today.plusDays(21)),
        )
        assertFalse(hasEnded(upcoming, now))
    }
}
