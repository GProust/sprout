package com.gproust.sprout

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.BreastSide
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.NursingSegment
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.ui.home.nextBreast
import com.gproust.sprout.ui.home.sleepMillisSince
import com.gproust.sprout.ui.home.summariseHousehold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * The dashboard's arithmetic. Every case here is about one of the two things
 * the household view has to get right: whose row is whose, and what "still
 * running" means.
 */
class HouseholdSummaryTest {

    private val now = 10L * 24 * HOUR
    private val dayStart = now - 8 * HOUR

    private fun baby(id: Long, name: String) =
        BabyEntity(id = id, name = name, birthDate = now - 21L * 24 * HOUR)

    private fun feed(
        babyId: Long,
        at: Long,
        type: FeedType = FeedType.BREAST,
        side: BreastSide? = null,
        segments: List<NursingSegment> = emptyList(),
    ) = FeedingEntity(
        babyId = babyId,
        type = type,
        side = side,
        startTime = at,
        segments = segments,
    )

    private fun sleep(babyId: Long, from: Long, to: Long?) =
        SleepEntity(babyId = babyId, startTime = from, endTime = to)

    private fun nappy(babyId: Long, at: Long) =
        DiaperEntity(babyId = babyId, time = at, wet = true)

    /**
     * Nullable-safe equality for the "when did it last happen" fields, so the
     * comparison is unambiguously between two boxed Longs.
     */
    private fun assertTime(expected: Long?, actual: Long?) = assertEquals(expected, actual)

    private fun summarise(
        babies: List<BabyEntity>,
        feeds: List<FeedingEntity> = emptyList(),
        sleeps: List<SleepEntity> = emptyList(),
        nappies: List<DiaperEntity> = emptyList(),
        ongoing: List<SleepEntity> = emptyList(),
    ) = summariseHousehold(babies, feeds, sleeps, nappies, ongoing, dayStart, now)

    @Test
    fun rowsGoToTheBabyTheyBelongTo() {
        // The whole point of the dashboard: two babies, one list of rows, and
        // nothing may cross over.
        val lea = baby(1, "Léa")
        val noah = baby(2, "Noah")
        val result = summarise(
            babies = listOf(lea, noah),
            feeds = listOf(feed(1, now - 2 * HOUR), feed(2, now - 25 * MINUTE)),
            nappies = listOf(nappy(1, now - HOUR)),
        )

        assertTime(now - 2 * HOUR, result[0].lastFeed)
        assertTime(now - 25 * MINUTE, result[1].lastFeed)
        assertTime(now - HOUR, result[0].lastDiaper)
        assertNull("Noah has had no nappy logged", result[1].lastDiaper)
    }

    @Test
    fun babyWithNoLogsStillGetsALine() {
        val result = summarise(babies = listOf(baby(1, "Léa")))
        assertEquals(1, result.size)
        assertEquals("Léa", result[0].baby.name)
        assertNull(result[0].lastFeed)
        assertEquals(0, result[0].feedsToday)
    }

    @Test
    fun rowsForAnUnlistedBabyAreDropped() {
        // An archived or deleted baby's rows can still be inside the window;
        // they must not appear under someone else's name, or on their own.
        val result = summarise(
            babies = listOf(baby(1, "Léa")),
            feeds = listOf(feed(1, now - HOUR), feed(99, now - 5 * MINUTE)),
        )
        assertEquals(1, result.size)
        assertTime(now - HOUR, result[0].lastFeed)
    }

    @Test
    fun todayCountsStopAtTheStartOfTheDay() {
        val result = summarise(
            babies = listOf(baby(1, "Léa")),
            feeds = listOf(
                feed(1, dayStart + HOUR),
                feed(1, dayStart - HOUR),
                feed(1, now - MINUTE),
            ),
        )
        assertEquals(2, result[0].feedsToday)
    }

    @Test
    fun lastSleepIsTheLastOneThatFinished() {
        // A sleep still running is reported separately; "slept 40m ago" is only
        // ever about one that ended.
        val result = summarise(
            babies = listOf(baby(1, "Léa")),
            sleeps = listOf(
                sleep(1, now - 3 * HOUR, now - 2 * HOUR),
                sleep(1, now - 30 * MINUTE, null),
            ),
        )
        assertTime(now - 2 * HOUR, result[0].lastSleep)
    }

    @Test
    fun anOngoingSleepIsCarriedThrough() {
        val open = sleep(1, now - 30 * MINUTE, null)
        val result = summarise(
            babies = listOf(baby(1, "Léa"), baby(2, "Noah")),
            ongoing = listOf(open),
        )
        assertEquals(open, result[0].ongoingSleep)
        assertNull("Noah is awake", result[1].ongoingSleep)
    }

    @Test
    fun runningSleepCountsUpToNow() {
        // Matches DailyStats: an unfinished sleep is running, not zero-length.
        val total = sleepMillisSince(
            listOf(sleep(1, now - 2 * HOUR, null)),
            dayStart,
            now,
        )
        assertEquals(2 * HOUR, total)
    }

    @Test
    fun sleepStartedBeforeTodayIsNotCounted() {
        val total = sleepMillisSince(
            listOf(sleep(1, dayStart - HOUR, dayStart + HOUR)),
            dayStart,
            now,
        )
        assertEquals(0L, total)
    }

    @Test
    fun nextBreastIsTheOtherOneFromWhereTheLastBegan() {
        val side = nextBreast(
            listOf(
                feed(
                    babyId = 1,
                    at = now - HOUR,
                    side = BreastSide.BOTH,
                    segments = listOf(
                        NursingSegment(BreastSide.LEFT, now - HOUR, now - 50 * MINUTE),
                        NursingSegment(BreastSide.RIGHT, now - 50 * MINUTE, now - 40 * MINUTE),
                    ),
                ),
            ),
            now,
        )
        assertEquals(BreastSide.RIGHT, side)
    }

    @Test
    fun aBottleInBetweenDoesNotTakeTheSideWithIt() {
        // The bug the widget already fixed once: the last *feed* was a bottle,
        // but the question is about the last time a breast was offered.
        val side = nextBreast(
            listOf(
                feed(1, now - 3 * HOUR, side = BreastSide.LEFT),
                feed(1, now - 30 * MINUTE, type = FeedType.BOTTLE),
            ),
            now,
        )
        assertEquals(BreastSide.RIGHT, side)
    }

    @Test
    fun noSuggestionWhenNothingWasNursedForADay() {
        val side = nextBreast(listOf(feed(1, now - 30 * HOUR, side = BreastSide.LEFT)), now)
        assertNull(side)
    }

    @Test
    fun noSuggestionWhenTheLastSessionCannotSayWhichCameFirst() {
        // BOTH without segments records which breasts were used, not their
        // order — so there is no alternation to continue.
        val side = nextBreast(listOf(feed(1, now - HOUR, side = BreastSide.BOTH)), now)
        assertNull(side)
    }

    @Test
    fun summariesKeepTheOrderOfTheBabyList() {
        val result = summarise(babies = listOf(baby(7, "Léa"), baby(3, "Noah")))
        assertTrue(result.map { it.baby.name } == listOf("Léa", "Noah"))
    }
}
