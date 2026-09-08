package com.gproust.sprout

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.SleepPlace
import com.gproust.sprout.data.local.SleepPosition
import com.gproust.sprout.data.local.StoolColor
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.ui.report.ReportOptions
import com.gproust.sprout.ui.report.ReportPeriod
import com.gproust.sprout.ui.report.buildReport
import com.gproust.sprout.ui.report.reportRange
import com.gproust.sprout.ui.report.treatmentCourses
import com.gproust.sprout.ui.stats.SleepWhere
import com.gproust.sprout.ui.stats.WhoSex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a report says, before anything draws it.
 *
 * The cases worth pinning down are the ones where a smaller number could be
 * passed off as the same kind of fact: an amount nobody recorded, a day nobody
 * logged on, a course clamped to a range it started before.
 */
class ReportDataTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val born = LocalDate.of(2026, 6, 2)
    private val today = LocalDate.of(2026, 9, 8)
    private val now = today.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val baby = BabyEntity(
        id = 1,
        name = "Louise",
        birthDate = born.atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    private fun report(
        feedings: List<FeedingEntity> = emptyList(),
        sleeps: List<SleepEntity> = emptyList(),
        diapers: List<DiaperEntity> = emptyList(),
        growth: List<GrowthEntity> = emptyList(),
        treatments: List<TreatmentEntity> = emptyList(),
        options: ReportOptions = ReportOptions(period = ReportPeriod.WEEK),
    ) = buildReport(baby, feedings, sleeps, diapers, growth, treatments, options, now, zone)

    private fun bottle(date: LocalDate, hour: Int, ml: Int?) = FeedingEntity(
        babyId = 1,
        type = FeedType.BOTTLE,
        amountMl = ml,
        startTime = at(date, hour),
    )

    @Test
    fun everyDayInTheRangeIsThere_includingTheEmptyOnes() {
        val content = report(feedings = listOf(bottle(today, 9, 120)))
        assertEquals(7, content.days.size)
        assertEquals(1, content.daysWithEntries)
        assertEquals(120, content.bottleMlTotal)
    }

    @Test
    fun theReportSaysWhereTheySleptAndHowTheyWereLying() {
        val content = report(
            sleeps = listOf(
                SleepEntity(
                    babyId = 1,
                    startTime = at(today.minusDays(1), 20),
                    endTime = at(today, 6),
                    position = SleepPosition.BACK,
                    place = SleepPlace.BEDSIDE_COT,
                ),
                SleepEntity(
                    babyId = 1,
                    startTime = at(today, 9),
                    endTime = at(today, 10),
                    position = SleepPosition.BACK,
                    place = SleepPlace.ON_A_PARENT,
                ),
            ),
        )

        val breakdown = content.sleepBreakdown
        assertTrue(breakdown.hasPlaces)
        assertTrue(breakdown.hasPositions)
        // Ten hours in the bedside cot against one on a parent, longest first.
        assertEquals(
            listOf(SleepWhere.Offered(SleepPlace.BEDSIDE_COT), SleepWhere.Offered(SleepPlace.ON_A_PARENT)),
            breakdown.byPlace.map { it.value },
        )
        // Both sleeps were on their back, so the positions are one line of two.
        assertEquals(listOf(SleepPosition.BACK), breakdown.byPosition.map { it.value })
        assertEquals(2, breakdown.byPosition.single().count)
        assertEquals(11 * 3_600_000L, breakdown.totalMillis)
    }

    @Test
    fun sleepsThatSaidNothingAreALineOfTheirOwn_notLeftOut() {
        val content = report(
            sleeps = listOf(
                SleepEntity(
                    babyId = 1,
                    startTime = at(today, 9),
                    endTime = at(today, 10),
                    place = SleepPlace.OWN_BED,
                ),
                // Three hours nobody said anything about. A document that
                // dropped them would report every nap as being in their own
                // bed (BDR-0014).
                SleepEntity(babyId = 1, startTime = at(today.minusDays(2), 13), endTime = at(today.minusDays(2), 16)),
            ),
        )

        val places = content.sleepBreakdown.byPlace
        assertNull("the sleeps that said nothing come last", places.last().value)
        assertEquals(3 * 3_600_000L, places.last().millis)
        assertEquals(content.sleepBreakdown.totalMillis, places.sumOf { it.millis })
    }

    @Test
    fun entriesOutsideTheRangeAreLeftOut() {
        val content = report(
            feedings = listOf(
                bottle(today, 9, 120),
                bottle(today.minusDays(30), 9, 500),
            ),
        )
        assertEquals(1, content.feedTotal)
        assertEquals(120, content.bottleMlTotal)
    }

    @Test
    fun aBottleWithNoVolumeCountsAsAFeedAndNotAsZeroMillilitres() {
        val content = report(
            feedings = listOf(
                bottle(today, 9, 120),
                bottle(today, 13, null),
            ),
        )
        assertEquals(2, content.bottleCount)
        assertEquals(1, content.bottlesWithVolume)
        assertEquals(120, content.bottleMlTotal)
    }

    @Test
    fun aNightIsSplitAcrossTheTwoDaysItCovers() {
        val content = report(
            sleeps = listOf(
                SleepEntity(
                    babyId = 1,
                    startTime = at(today.minusDays(1), 20),
                    endTime = at(today, 6),
                ),
            ),
        )
        val evening = content.days.first { it.date == today.minusDays(1) }
        val morning = content.days.first { it.date == today }
        assertEquals(4 * 3_600_000L, evening.sleepMillis)
        assertEquals(6 * 3_600_000L, morning.sleepMillis)
        // The count stays on the evening: "three naps" means three times settled.
        assertEquals(1, evening.sleepCount)
        assertEquals(0, morning.sleepCount)
        assertEquals(10 * 3_600_000L, content.longestSleepMillis)
    }

    @Test
    fun aNightThatBeganBeforeTheRangeStillCounts() {
        val range = reportRange(ReportPeriod.WEEK, today, born)
        val content = report(
            sleeps = listOf(
                SleepEntity(
                    babyId = 1,
                    startTime = at(range.from.minusDays(1), 21),
                    endTime = at(range.from, 7),
                ),
            ),
        )
        assertEquals(7 * 3_600_000L, content.days.first().sleepMillis)
    }

    @Test
    fun stoolColoursAreCountedWhereTheyWereRecorded() {
        val content = report(
            diapers = listOf(
                DiaperEntity(babyId = 1, time = at(today, 8), wet = true, dirty = true, stoolColor = StoolColor.YELLOW),
                DiaperEntity(babyId = 1, time = at(today, 10), wet = true, dirty = true, stoolColor = StoolColor.YELLOW),
                DiaperEntity(babyId = 1, time = at(today, 12), wet = true, dirty = true, stoolColor = StoolColor.GREEN),
                DiaperEntity(babyId = 1, time = at(today, 14), wet = true),
            ),
        )
        assertEquals(listOf(StoolColor.YELLOW to 2, StoolColor.GREEN to 1), content.stoolColours)
        assertEquals(4, content.diaperTotal)
        assertEquals(3, content.dirtyTotal)
    }

    @Test
    fun aMeasureThatWasNotTakenHasNoCentile() {
        val content = report(
            growth = listOf(
                GrowthEntity(babyId = 1, time = at(today, 9), weightGrams = 6100, heightMm = 594),
            ),
        )
        val reading = content.growth.single()
        assertNotNull(reading.weight)
        assertNotNull(reading.length)
        assertNull(reading.head)
    }

    @Test
    fun pickingOneReferenceCollapsesTheSpanToASingleFigure() {
        val entry = GrowthEntity(babyId = 1, time = at(today, 9), weightGrams = 6100)
        val both = report(growth = listOf(entry)).growth.single().weight!!
        val girls = report(
            growth = listOf(entry),
            options = ReportOptions(period = ReportPeriod.WEEK, reference = WhoSex.GIRLS),
        ).growth.single().weight!!

        assertTrue(both.highPercentile > both.lowPercentile)
        assertEquals(girls.lowPercentile, girls.highPercentile, 1e-9)
        assertEquals(both.highPercentile, girls.highPercentile, 1e-9)
    }

    @Test
    fun growthIsTheWholeHistory_notJustTheRange() {
        val content = report(
            growth = listOf(
                GrowthEntity(babyId = 1, time = at(born, 12), weightGrams = 3240),
                GrowthEntity(babyId = 1, time = at(today, 9), weightGrams = 6100),
            ),
        )
        assertEquals(2, content.growth.size)
        assertEquals(3240, content.growth.first().entry.weightGrams)
    }

    // --- treatments -------------------------------------------------------

    private fun treatment(
        name: String,
        start: LocalDate,
        end: LocalDate?,
        intervalDays: Int = 1,
    ) = TreatmentEntity(
        babyId = 1,
        name = name,
        intervalDays = intervalDays,
        startDate = at(start, 9),
        endDate = end?.let { at(it, 9) },
    )

    @Test
    fun aCourseThatStartedBeforeTheRangeIsClampedAndSaysSo() {
        val range = reportRange(ReportPeriod.WEEK, today, born)
        val courses = treatmentCourses(listOf(treatment("Vitamin D", born, null)), range, zone)
        val course = courses.single()
        assertEquals(0, course.startIndex)
        assertEquals(range.dayCount, course.endIndex)
        assertTrue(course.startsBefore)
        assertTrue(course.runsPast)
    }

    @Test
    fun aCourseWhollyInsideTheRangeClaimsNeitherEnd() {
        val range = reportRange(ReportPeriod.WEEK, today, born)
        val courses = treatmentCourses(
            listOf(treatment("Amoxicillin", range.from.plusDays(1), range.from.plusDays(5))),
            range,
            zone,
        )
        val course = courses.single()
        assertEquals(1, course.startIndex)
        assertEquals(6, course.endIndex)
        assertFalse(course.startsBefore)
        assertFalse(course.runsPast)
    }

    @Test
    fun aCourseThatEndedBeforeTheRangeIsNotShown() {
        val range = reportRange(ReportPeriod.WEEK, today, born)
        val courses = treatmentCourses(
            listOf(treatment("Vitamin K", born, range.from.minusDays(1))),
            range,
            zone,
        )
        assertTrue(courses.isEmpty())
    }

    @Test
    fun aWeeklyCourseMarksItsDosingDaysAndADailyOneDoesNot() {
        val range = reportRange(ReportPeriod.MONTH, today, born)
        val start = range.from.minusDays(3)
        val courses = treatmentCourses(
            listOf(
                treatment("Vitamin K", start, null, intervalDays = 7),
                treatment("Vitamin D", start, null, intervalDays = 1),
            ),
            range,
            zone,
        )
        val weekly = courses.first { it.treatment.name == "Vitamin K" }
        val daily = courses.first { it.treatment.name == "Vitamin D" }
        assertTrue(daily.doseDays.isEmpty())
        // Doses fall every seventh day from the start, three days before the range.
        assertEquals(listOf(4, 11, 18, 25), weekly.doseDays)
    }

    @Test
    fun treatmentsAreLeftOutEntirelyWhenTheSwitchIsOff() {
        val content = report(
            treatments = listOf(treatment("Vitamin D", born, null)),
            options = ReportOptions(period = ReportPeriod.WEEK, includeTreatments = false),
        )
        assertTrue(content.treatments.isEmpty())
    }

    @Test
    fun anEmptyPeriodSaysSoRatherThanPretendingToHaveFigures() {
        val content = report()
        assertFalse(content.hasEntries)
        assertEquals(0, content.feedTotal)
        assertEquals(7, content.days.size)
    }
}
