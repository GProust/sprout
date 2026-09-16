package com.gproust.sprout

import com.gproust.sprout.data.MEDICINE_DAY_MS
import com.gproust.sprout.data.MedicineLevel
import com.gproust.sprout.data.TooSoonReason
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.medicineReadiness
import com.gproust.sprout.data.nextMedicineReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The traffic light, as arithmetic (BDR-15).
 *
 * The same cases as `MedicineReadinessTests.swift`, because the two platforms
 * answer this question separately and a household with one of each has to get
 * the same answer on both phones — in a feature where the answer is "may I give
 * my child another dose".
 */
class MedicineReadinessTest {

    private val now = 1_757_400_000_000L
    private val hour = 60 * 60 * 1000L

    /** Paracetamol as the editor offers it: six hours at least, eight usually. */
    private fun paracetamol(
        maxPerDay: Int? = null,
        comfort: Int? = 8 * 60,
        remind: Boolean = false,
        remindAtComfort: Boolean = false,
    ) = MedicineEntity(
        name = "Paracetamol",
        minIntervalMinutes = 6 * 60,
        comfortIntervalMinutes = comfort,
        maxPerDay = maxPerDay,
        remindWhenDue = remind,
        remindAtComfort = remindAtComfort,
        uid = "medicine-uid",
    )

    private fun dose(at: Long, of: String = "medicine-uid", deletedAt: Long? = null) =
        MedicineDoseEntity(medicineUid = of, time = at, uid = "dose-$at", deletedAt = deletedAt)

    @Test
    fun `a medicine never given is ready, not red`() {
        val readiness = medicineReadiness(paracetamol(), emptyList(), now)

        assertEquals(MedicineLevel.READY, readiness.level)
        assertNull("no wait is running, so nothing to count down", readiness.lastDoseAt)
        assertEquals(0, readiness.dosesInLastDay)
    }

    @Test
    fun `before the minimum wait it is too soon, and says how long is left`() {
        val readiness = medicineReadiness(paracetamol(), listOf(dose(now - 2 * hour)), now)

        assertEquals(MedicineLevel.TOO_SOON, readiness.level)
        assertEquals(TooSoonReason.INTERVAL, readiness.reason)
        assertEquals(now + 4 * hour, readiness.nextAllowedAt)
    }

    /** The boundary itself is past the wait, not still inside it. */
    @Test
    fun `exactly at the minimum it is allowed`() {
        val readiness = medicineReadiness(paracetamol(), listOf(dose(now - 6 * hour)), now)

        assertEquals(MedicineLevel.SOONER_THAN_IDEAL, readiness.level)
        assertNull(readiness.nextAllowedAt)
        assertEquals(now + 2 * hour, readiness.comfortableAt)
    }

    @Test
    fun `between the two it is allowed but sooner than ideal`() {
        val readiness = medicineReadiness(paracetamol(), listOf(dose(now - 7 * hour)), now)

        assertEquals(MedicineLevel.SOONER_THAN_IDEAL, readiness.level)
        assertEquals(now + 1 * hour, readiness.comfortableAt)
    }

    @Test
    fun `past the comfortable interval it is ready`() {
        val readiness = medicineReadiness(paracetamol(), listOf(dose(now - 9 * hour)), now)

        assertEquals(MedicineLevel.READY, readiness.level)
        assertNull(readiness.comfortableAt)
    }

    /**
     * With no comfortable interval there is no middle band at all: the medicine
     * goes from red to ready at the one boundary the parent gave.
     */
    @Test
    fun `a medicine with only a minimum has no middle band`() {
        val readiness = medicineReadiness(
            paracetamol(comfort = null),
            listOf(dose(now - 6 * hour)),
            now,
        )

        assertEquals(MedicineLevel.READY, readiness.level)
        assertNull(readiness.comfortableAt)
    }

    /**
     * A comfortable interval below the minimum is a typo, not a second boundary.
     * Taking the larger keeps the states in the order they assume rather than
     * producing a band that ends before it begins.
     */
    @Test
    fun `a comfortable interval shorter than the minimum is clamped up`() {
        val readiness = medicineReadiness(
            paracetamol(comfort = 2 * 60),
            listOf(dose(now - 3 * hour)),
            now,
        )

        assertEquals(MedicineLevel.TOO_SOON, readiness.level)
        assertEquals(now + 3 * hour, readiness.nextAllowedAt)
        // Clamped up to the minimum, so both boundaries land on the same moment
        // and the medicine goes straight from red to ready with no band between.
        assertEquals(readiness.nextAllowedAt, readiness.comfortableAt)
    }

    @Test
    fun `only this medicine's doses count`() {
        val readiness = medicineReadiness(
            paracetamol(),
            listOf(dose(now - 1 * hour, of = "another-medicine"), dose(now - 9 * hour)),
            now,
        )

        assertEquals(MedicineLevel.READY, readiness.level)
        assertEquals(now - 9 * hour, readiness.lastDoseAt)
    }

    /** A deleted dose is not a dose; the light has to follow the correction. */
    @Test
    fun `a soft-deleted dose is ignored`() {
        val readiness = medicineReadiness(
            paracetamol(),
            listOf(dose(now - 1 * hour, deletedAt = now), dose(now - 9 * hour)),
            now,
        )

        assertEquals(MedicineLevel.READY, readiness.level)
        assertEquals(now - 9 * hour, readiness.lastDoseAt)
    }

    @Test
    fun `the order of the doses does not matter`() {
        val ascending = listOf(dose(now - 9 * hour), dose(now - 2 * hour))
        val descending = ascending.reversed()

        assertEquals(
            medicineReadiness(paracetamol(), ascending, now),
            medicineReadiness(paracetamol(), descending, now),
        )
    }

    // --- The daily maximum ------------------------------------------------

    /**
     * The interval has elapsed but the day's allowance is spent, so it is still
     * red — for a reason the screen has to be able to say differently.
     */
    @Test
    fun `a spent daily maximum holds it red past the interval`() {
        val doses = listOf(
            dose(now - 23 * hour),
            dose(now - 17 * hour),
            dose(now - 13 * hour),
            dose(now - 7 * hour),
        )

        val readiness = medicineReadiness(paracetamol(maxPerDay = 4), doses, now)

        assertEquals(MedicineLevel.TOO_SOON, readiness.level)
        assertEquals(TooSoonReason.DAILY_MAXIMUM, readiness.reason)
        // Twenty-four hours after the fourth-most-recent dose, which is the one
        // that has to age out of the window.
        assertEquals(now - 23 * hour + MEDICINE_DAY_MS, readiness.nextAllowedAt)
        assertEquals(4, readiness.dosesInLastDay)
    }

    /**
     * Rolling, not calendar: a maximum counted per calendar day would allow four
     * at 23:00 and four more at 00:30.
     */
    @Test
    fun `a dose older than the window does not count against the maximum`() {
        val doses = listOf(
            dose(now - 25 * hour),
            dose(now - 17 * hour),
            dose(now - 13 * hour),
            dose(now - 9 * hour),
        )

        val readiness = medicineReadiness(paracetamol(maxPerDay = 4), doses, now)

        assertEquals(MedicineLevel.READY, readiness.level)
        assertEquals(3, readiness.dosesInLastDay)
    }

    /**
     * While the interval is still running that is the reason, even if the day's
     * allowance is also spent: it is the one the parent is waiting on first.
     */
    @Test
    fun `the interval is named while it is the thing still running`() {
        val doses = listOf(
            dose(now - 23 * hour),
            dose(now - 17 * hour),
            dose(now - 11 * hour),
            dose(now - 2 * hour),
        )

        val readiness = medicineReadiness(paracetamol(maxPerDay = 4), doses, now)

        assertEquals(TooSoonReason.INTERVAL, readiness.reason)
        // And the later of the two moments, which here is the interval's own:
        // the allowance frees up an hour from now, the six-hour wait in four.
        assertEquals(now + 4 * hour, readiness.nextAllowedAt)
    }

    @Test
    fun `a daily maximum of zero is no maximum at all`() {
        val readiness = medicineReadiness(
            paracetamol(maxPerDay = 0),
            listOf(dose(now - 9 * hour)),
            now,
        )

        assertEquals(MedicineLevel.READY, readiness.level)
    }

    // --- The reminder -----------------------------------------------------

    @Test
    fun `no reminder when the switch is off`() {
        assertNull(nextMedicineReminder(paracetamol(), listOf(dose(now - 2 * hour)), now))
    }

    @Test
    fun `no reminder for a medicine never given`() {
        assertNull(nextMedicineReminder(paracetamol(remind = true), emptyList(), now))
    }

    @Test
    fun `by default the reminder is at the minimum wait`() {
        assertEquals(
            now + 4 * hour,
            nextMedicineReminder(paracetamol(remind = true), listOf(dose(now - 2 * hour)), now),
        )
    }

    @Test
    fun `a parent can ask to be reminded at the comfortable interval instead`() {
        assertEquals(
            now + 6 * hour,
            nextMedicineReminder(
                paracetamol(remind = true, remindAtComfort = true),
                listOf(dose(now - 2 * hour)),
                now,
            ),
        )
    }

    /**
     * With no comfortable interval the switch is only about *whether* to remind:
     * there is one boundary, so it falls back to it.
     */
    @Test
    fun `asking for the comfortable interval falls back to the minimum when there is none`() {
        assertEquals(
            now + 4 * hour,
            nextMedicineReminder(
                paracetamol(comfort = null, remind = true, remindAtComfort = true),
                listOf(dose(now - 2 * hour)),
                now,
            ),
        )
    }

    /** A moment that has already passed is not a reminder. */
    @Test
    fun `no reminder once the medicine is already available`() {
        assertNull(
            nextMedicineReminder(paracetamol(remind = true), listOf(dose(now - 9 * hour)), now),
        )
    }

    @Test
    fun `the daily maximum can push the reminder past both intervals`() {
        // Both waits elapsed an hour or more ago; only the day's allowance is
        // still holding it, and it frees up an hour from now.
        val doses = listOf(
            dose(now - 23 * hour),
            dose(now - 20 * hour),
            dose(now - 16 * hour),
            dose(now - 10 * hour),
        )

        assertEquals(
            now - 23 * hour + MEDICINE_DAY_MS,
            nextMedicineReminder(paracetamol(maxPerDay = 4, remind = true), doses, now),
        )
    }

    @Test
    fun `an inactive medicine is not reminded about`() {
        assertNull(
            nextMedicineReminder(
                paracetamol(remind = true).copy(active = false),
                listOf(dose(now - 2 * hour)),
                now,
            ),
        )
    }
}
