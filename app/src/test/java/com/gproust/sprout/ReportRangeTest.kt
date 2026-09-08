package com.gproust.sprout

import com.gproust.sprout.ui.report.ReportPeriod
import com.gproust.sprout.ui.report.reportRange
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Which days a report actually covers.
 *
 * The two clamps are the whole of it: never past today, never before the birth.
 * Both exist so that a figure printed as "per day" is divided by days the baby
 * lived — the same rule the Statistics window follows, for the same reason.
 */
class ReportRangeTest {

    private val today = LocalDate.of(2026, 9, 8)

    @Test
    fun sevenDaysIsThisWeekIncludingToday() {
        val range = reportRange(ReportPeriod.WEEK, today, birthDay = LocalDate.of(2026, 1, 1))
        assertEquals(LocalDate.of(2026, 9, 2), range.from)
        assertEquals(today, range.to)
        assertEquals(7, range.dayCount)
    }

    @Test
    fun thirtyDaysIsThirtyColumns() {
        val range = reportRange(ReportPeriod.MONTH, today, birthDay = LocalDate.of(2025, 1, 1))
        assertEquals(30, range.dayCount)
        assertEquals(LocalDate.of(2026, 8, 10), range.from)
    }

    @Test
    fun theRangeNeverReachesBackPastTheBirth() {
        val born = LocalDate.of(2026, 8, 27)
        val range = reportRange(ReportPeriod.QUARTER, today, birthDay = born)
        assertEquals(born, range.from)
        assertEquals(13, range.dayCount)
    }

    @Test
    fun sinceBirthStartsAtTheBirth() {
        val born = LocalDate.of(2026, 6, 2)
        val range = reportRange(ReportPeriod.SINCE_BIRTH, today, birthDay = born)
        assertEquals(born, range.from)
        assertEquals(today, range.to)
        assertEquals(99, range.dayCount)
    }

    @Test
    fun aCustomRangeIsHonoured() {
        val range = reportRange(
            ReportPeriod.CUSTOM,
            today,
            birthDay = LocalDate.of(2026, 1, 1),
            customFrom = LocalDate.of(2026, 8, 1),
            customTo = LocalDate.of(2026, 8, 31),
        )
        assertEquals(LocalDate.of(2026, 8, 1), range.from)
        assertEquals(LocalDate.of(2026, 8, 31), range.to)
        assertEquals(31, range.dayCount)
    }

    @Test
    fun aCustomRangeCannotEndAfterToday() {
        val range = reportRange(
            ReportPeriod.CUSTOM,
            today,
            birthDay = LocalDate.of(2026, 1, 1),
            customFrom = LocalDate.of(2026, 9, 1),
            customTo = LocalDate.of(2026, 12, 25),
        )
        assertEquals(today, range.to)
    }

    @Test
    fun aCustomRangeCannotStartBeforeTheBirth() {
        val born = LocalDate.of(2026, 8, 20)
        val range = reportRange(
            ReportPeriod.CUSTOM,
            today,
            birthDay = born,
            customFrom = LocalDate.of(2026, 1, 1),
            customTo = today,
        )
        assertEquals(born, range.from)
    }

    @Test
    fun aCustomRangeTypedBackToFrontStillCoversADay() {
        val range = reportRange(
            ReportPeriod.CUSTOM,
            today,
            birthDay = LocalDate.of(2026, 1, 1),
            customFrom = LocalDate.of(2026, 9, 5),
            customTo = LocalDate.of(2026, 9, 1),
        )
        assertEquals(1, range.dayCount)
        assertEquals(range.from, range.to)
    }

    @Test
    fun aBirthDateInTheFutureLeavesToday() {
        val range = reportRange(ReportPeriod.MONTH, today, birthDay = today.plusDays(20))
        assertEquals(today, range.from)
        assertEquals(today, range.to)
        assertEquals(1, range.dayCount)
    }

    @Test
    fun daysAreIndexedFromTheStartOfTheRange() {
        val range = reportRange(ReportPeriod.WEEK, today, birthDay = LocalDate.of(2026, 1, 1))
        assertEquals(0, range.indexOf(range.from))
        assertEquals(6, range.indexOf(today))
        assertEquals(null, range.indexOf(today.plusDays(1)))
        assertEquals(null, range.indexOf(range.from.minusDays(1)))
        assertEquals(7, range.days().size)
    }
}
