package com.gproust.sprout

import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.stats.averagesOf
import com.gproust.sprout.ui.stats.breastfeedMillis
import com.gproust.sprout.ui.stats.dailyStats
import com.gproust.sprout.ui.stats.statsWindowStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DailyStatsTest {

    // A zone with daylight saving, so the short and long days are testable.
    private val zone = ZoneId.of("Europe/Paris")
    private val day1 = LocalDate.of(2026, 3, 10)
    private val day2 = LocalDate.of(2026, 3, 11)
    private val day3 = LocalDate.of(2026, 3, 12)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun stats(
        feedings: List<FeedingEntity> = emptyList(),
        sleeps: List<SleepEntity> = emptyList(),
        diapers: List<DiaperEntity> = emptyList(),
        from: LocalDate = day1,
        to: LocalDate = day3,
        now: Long = at(day3, 23, 59),
    ) = dailyStats(feedings, sleeps, diapers, from, to, now, zone)

    @Test
    fun everyDayInTheWindowIsThere_evenTheEmptyOnes() {
        val days = stats(feedings = listOf(bottle(day2, 10, ml = 120)))
        assertEquals(listOf(day1, day2, day3), days.map { it.date })
        assertEquals(0, days[0].feedCount)
        assertEquals(1, days[1].feedCount)
        assertEquals(0, days[2].feedCount)
    }

    @Test
    fun anEmptyWindowIsEmpty() {
        assertTrue(stats(from = day3, to = day1).isEmpty())
    }

    @Test
    fun bottlesCountTheirMillilitres_andSolidsTheirGrams() {
        val days = stats(
            feedings = listOf(
                bottle(day2, 8, ml = 120),
                bottle(day2, 12, ml = 90),
                // Logged without an amount: it happened, but nothing was measured.
                bottle(day2, 16, ml = null),
                solid(day2, 18, grams = 60),
                solid(day2, 20, grams = null),
            ),
        )
        val d = days[1]
        assertEquals(3, d.bottleCount)
        assertEquals(210, d.bottleMl)
        assertEquals(2, d.solidCount)
        assertEquals(60, d.solidGrams)
        assertEquals(5, d.feedCount)
    }

    @Test
    fun breastfeedsCountTheirTimeAtTheBreast() {
        val start = at(day2, 9)
        val segmented = FeedingEntity(
            type = FeedType.BREAST,
            side = BreastSide.BOTH,
            startTime = start,
            endTime = start + 13 * minute,
            leftDurationMs = 9 * minute,
            rightDurationMs = 4 * minute,
            segments = listOf(
                NursingSegment(BreastSide.LEFT, start, start + 6 * minute),
                NursingSegment(BreastSide.RIGHT, start + 6 * minute, start + 10 * minute),
                NursingSegment(BreastSide.LEFT, start + 10 * minute, start + 13 * minute),
            ),
        )
        val days = stats(feedings = listOf(segmented))
        assertEquals(1, days[1].breastCount)
        assertEquals(13 * minute, days[1].breastMillis)
    }

    @Test
    fun breastfeedDuration_fallsBackThroughEveryGenerationOfTheEntity() {
        val start = at(day2, 9)
        // Timed stretch by stretch — the segments win, even against the totals.
        assertEquals(
            10 * minute,
            breastfeedMillis(
                FeedingEntity(
                    type = FeedType.BREAST,
                    startTime = start,
                    endTime = start + 30 * minute,
                    leftDurationMs = 99 * minute,
                    segments = listOf(NursingSegment(BreastSide.LEFT, start, start + 10 * minute)),
                ),
            ),
        )
        // Per-side totals, from before segments were recorded.
        assertEquals(
            12 * minute,
            breastfeedMillis(
                FeedingEntity(
                    type = FeedType.BREAST,
                    startTime = start,
                    leftDurationMs = 8 * minute,
                    rightDurationMs = 4 * minute,
                ),
            ),
        )
        // Nothing but a start and an end.
        assertEquals(
            7 * minute,
            breastfeedMillis(
                FeedingEntity(type = FeedType.BREAST, startTime = start, endTime = start + 7 * minute),
            ),
        )
        // A session still running has no duration yet, and an end before its
        // start does not subtract time from the day.
        assertEquals(0L, breastfeedMillis(FeedingEntity(type = FeedType.BREAST, startTime = start)))
        assertEquals(
            0L,
            breastfeedMillis(
                FeedingEntity(type = FeedType.BREAST, startTime = start, endTime = start - hour),
            ),
        )
    }

    @Test
    fun aNightIsSplitAcrossTheTwoDaysItCovers() {
        val days = stats(
            sleeps = listOf(
                SleepEntity(startTime = at(day1, 20), endTime = at(day2, 6)),
            ),
        )
        assertEquals(4 * hour, days[0].sleepMillis)
        assertEquals(6 * hour, days[1].sleepMillis)
        // But the baby only settled once, on the evening it started.
        assertEquals(1, days[0].sleepCount)
        assertEquals(0, days[1].sleepCount)
    }

    @Test
    fun napsAddUpWithinTheirDay() {
        val days = stats(
            sleeps = listOf(
                SleepEntity(startTime = at(day2, 9), endTime = at(day2, 10, 30)),
                SleepEntity(startTime = at(day2, 14), endTime = at(day2, 15)),
            ),
        )
        assertEquals(2, days[1].sleepCount)
        assertEquals(150 * minute, days[1].sleepMillis)
    }

    @Test
    fun aSleepStillRunningCountsUpToNow_andNoFurther() {
        val now = at(day3, 10)
        val days = stats(
            sleeps = listOf(SleepEntity(startTime = at(day3, 8), endTime = null)),
            now = now,
        )
        assertEquals(2 * hour, days[2].sleepMillis)
    }

    @Test
    fun aSleepEndingBeforeItStarted_contributesNothing() {
        val days = stats(
            sleeps = listOf(SleepEntity(startTime = at(day2, 10), endTime = at(day2, 8))),
        )
        assertEquals(0L, days[1].sleepMillis)
        assertEquals(1, days[1].sleepCount)
    }

    @Test
    fun sleepOutsideTheWindowIsIgnored_butItsOverlapIsNot() {
        // Starts the evening before the window and runs into its first morning.
        val days = stats(
            sleeps = listOf(SleepEntity(startTime = at(day1.minusDays(1), 22), endTime = at(day1, 5))),
        )
        assertEquals(5 * hour, days[0].sleepMillis)
        // It did not start inside the window, so it is not one of its sleeps.
        assertEquals(0, days[0].sleepCount)
    }

    @Test
    fun theShortDayOfADaylightSavingChange_isCountedAsItWasLived() {
        // Europe/Paris springs forward on 29 March 2026: 02:00 becomes 03:00.
        val spring = LocalDate.of(2026, 3, 29)
        val days = dailyStats(
            feedings = emptyList(),
            sleeps = listOf(
                SleepEntity(
                    startTime = spring.minusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
                    endTime = spring.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                ),
            ),
            diapers = emptyList(),
            from = spring.minusDays(1),
            to = spring,
            now = spring.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals(1 * hour, days[0].sleepMillis)
        // 00:00 to 07:00 on the clock, but only six hours actually happened.
        assertEquals(6 * hour, days[1].sleepMillis)
    }

    @Test
    fun aChangeCanBeBothWetAndDirty() {
        val days = stats(
            diapers = listOf(
                DiaperEntity(time = at(day2, 8), wet = true),
                DiaperEntity(time = at(day2, 11), wet = true, dirty = true),
                DiaperEntity(time = at(day2, 15), dirty = true),
            ),
        )
        assertEquals(3, days[1].diaperCount)
        assertEquals(2, days[1].wetCount)
        assertEquals(2, days[1].dirtyCount)
    }

    @Test
    fun aFeedJustBeforeMidnightBelongsToTheDayItStarted() {
        val days = stats(feedings = listOf(bottle(day1, 23, minute = 55, ml = 100)))
        assertEquals(100, days[0].bottleMl)
        assertEquals(0, days[1].bottleMl)
    }

    @Test
    fun averagesLeaveOutTheDayStillBeingLived() {
        val days = stats(
            feedings = listOf(
                bottle(day1, 9, ml = 100),
                bottle(day1, 15, ml = 100),
                bottle(day2, 9, ml = 200),
                bottle(day2, 15, ml = 200),
                // Today, half over: one feed so far.
                bottle(day3, 9, ml = 60),
            ),
        )
        val averages = averagesOf(days, today = day3)
        assertEquals(2, averages.dayCount)
        assertEquals(2.0, averages.feedsPerDay, 1e-9)
        assertEquals(300, averages.bottleMlPerDay)
    }

    @Test
    fun onTheFirstDay_todayIsAllThereIs() {
        val days = stats(feedings = listOf(bottle(day3, 9, ml = 60)), from = day3, to = day3)
        val averages = averagesOf(days, today = day3)
        assertEquals(1, averages.dayCount)
        assertEquals(60, averages.bottleMlPerDay)
    }

    @Test
    fun theWindowNeverReachesBackPastTheBirth() {
        val today = LocalDate.of(2026, 3, 20)
        val born = LocalDate.of(2026, 3, 8) // twelve days old

        // A window that fits inside the baby's life is untouched.
        assertEquals(today.minusDays(6), statsWindowStart(today, 7, born))
        // One that does not stops at the birth, rather than averaging over
        // eighteen days the baby was not there for.
        assertEquals(born, statsWindowStart(today, 30, born))
        assertEquals(born, statsWindowStart(today, 90, born))
    }

    @Test
    fun aBirthDateInTheFutureStillLeavesToday() {
        val today = LocalDate.of(2026, 3, 20)
        // Typed ahead of a due date: the window must not end before it starts.
        assertEquals(today, statsWindowStart(today, 30, today.plusDays(40)))
    }

    @Test
    fun withNoBirthDate_theWindowIsTheWholePeriod() {
        val today = LocalDate.of(2026, 3, 20)
        assertEquals(today.minusDays(29), statsWindowStart(today, 30, null))
        // A one-day window is today alone, not an empty one.
        assertEquals(today, statsWindowStart(today, 1, null))
    }

    @Test
    fun aShortenedWindow_averagesOverTheDaysItActuallyCovers() {
        // The bug this guards: thirty days of window over twelve days of life
        // used to divide a newborn's ten feeds a day down to four.
        val born = day1
        val today = day3
        val from = statsWindowStart(today, 30, born)
        val feeds = listOf(day1, day2, day3).flatMap { date ->
            (0 until 10).map { bottle(date, 6 + it, ml = 60) }
        }
        val days = dailyStats(feeds, emptyList(), emptyList(), from, today, at(day3, 23, 59), zone)
        val averages = averagesOf(days, today = today)

        assertEquals(3, days.size)
        assertEquals(2, averages.dayCount)
        assertEquals(10.0, averages.feedsPerDay, 1e-9)
    }

    @Test
    fun withNothingToAverage_everythingIsZeroRatherThanUndefined() {
        val averages = averagesOf(emptyList(), today = day3)
        assertEquals(0, averages.dayCount)
        assertEquals(0.0, averages.feedsPerDay, 0.0)
        assertEquals(0L, averages.sleepMillisPerDay)
        assertTrue(averages.bottleMlPerDay == 0)
    }

    private fun bottle(date: LocalDate, hour: Int, minute: Int = 0, ml: Int?) = FeedingEntity(
        type = FeedType.BOTTLE,
        amountMl = ml,
        startTime = at(date, hour, minute),
    )

    private fun solid(date: LocalDate, hour: Int, grams: Int?) = FeedingEntity(
        type = FeedType.SOLID,
        amountGrams = grams,
        startTime = at(date, hour),
    )
}
