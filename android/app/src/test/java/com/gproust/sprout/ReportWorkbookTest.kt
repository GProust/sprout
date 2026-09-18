package com.gproust.sprout

import com.gproust.sprout.data.local.BabyEntity
import com.gproust.sprout.data.local.DiaperEntity
import com.gproust.sprout.data.local.FeedType
import com.gproust.sprout.data.local.FeedingEntity
import com.gproust.sprout.data.local.GrowthEntity
import com.gproust.sprout.data.local.MedicineDoseEntity
import com.gproust.sprout.data.local.MedicineEntity
import com.gproust.sprout.data.local.SleepEntity
import com.gproust.sprout.data.local.SleepPlace
import com.gproust.sprout.data.local.SleepPosition
import com.gproust.sprout.data.local.TreatmentEntity
import com.gproust.sprout.data.export.Xlsx
import com.gproust.sprout.ui.report.ReportOptions
import com.gproust.sprout.ui.report.ReportPeriod
import com.gproust.sprout.ui.report.ReportWorkbook
import com.gproust.sprout.ui.report.buildReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The workbook the export produces: one sheet per kind of entry, and the same
 * figures the document prints.
 *
 * The column names are English on purpose and are checked here as such — they
 * are the schema of a data file, not prose, so a pivot table written against
 * `bottle_ml` keeps working after the phone changes language.
 */
class ReportWorkbookTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val born = LocalDate.of(2026, 6, 2)
    private val today = LocalDate.of(2026, 9, 8)
    private val now = today.atTime(11, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private val baby = BabyEntity(
        id = 1,
        name = "Louise",
        birthDate = born.atStartOfDay(zone).toInstant().toEpochMilli(),
    )

    private fun sheets(options: ReportOptions = ReportOptions(period = ReportPeriod.WEEK)): List<Xlsx.Sheet> {
        val report = buildReport(
            baby = baby,
            feedings = listOf(
                FeedingEntity(
                    babyId = 1,
                    type = FeedType.BOTTLE,
                    amountMl = 120,
                    startTime = at(today, 9),
                    notes = "took it well",
                ),
            ),
            sleeps = listOf(
                SleepEntity(
                    babyId = 1,
                    startTime = at(today.minusDays(1), 20),
                    endTime = at(today, 6),
                    position = SleepPosition.BACK,
                    place = SleepPlace.BEDSIDE_COT,
                ),
            ),
            diapers = listOf(DiaperEntity(babyId = 1, time = at(today, 8), wet = true)),
            growth = listOf(GrowthEntity(babyId = 1, time = at(today, 9), weightGrams = 6100)),
            treatments = listOf(
                TreatmentEntity(babyId = 1, name = "Vitamin D", startDate = at(born, 9), endDate = null),
            ),
            medicines = listOf(
                MedicineEntity(
                    babyId = 1,
                    name = "Teething gel",
                    minIntervalMinutes = 0,
                    maxPerDay = 6,
                    doseAmount = 0.25,
                    doseUnit = "cm",
                    maxAmountPerDay = 1.5,
                    uid = "gel",
                ),
            ),
            medicineDoses = listOf(
                MedicineDoseEntity(babyId = 1, medicineUid = "gel", time = at(today, 10), amount = 0.25),
            ),
            options = options,
            now = now,
            zone = zone,
        )
        return ReportWorkbook.build(report, zone)
    }

    @Test
    fun thereIsOneSheetPerKindOfEntry() {
        assertEquals(
            listOf(
                "Summary", "Day by day", "Feeding", "Sleep", "Nappies", "Growth",
                "Treatments", "Medicines",
            ),
            sheets().map { it.name },
        )
    }

    @Test
    fun theTreatmentsSheetGoesWhenTreatmentsDo() {
        val names = sheets(
            ReportOptions(period = ReportPeriod.WEEK, includeTreatments = false),
        ).map { it.name }
        assertFalse(names.contains("Treatments"))
    }

    @Test
    fun theDayByDaySheetHasARowPerDayOfTheRange() {
        val daily = sheets().first { it.name == "Day by day" }
        assertEquals(7, daily.rows.size)
        assertTrue(daily.headers.contains("bottle_ml"))
        assertTrue(daily.headers.contains("sleep_minutes"))
    }

    @Test
    fun theSleepSheetCarriesWhereAndHowTheyWereLying() {
        val sleep = sheets().first { it.name == "Sleep" }

        // Keys rather than translated labels, like `stool_colour`: a pivot
        // table written against them has to survive a change of language.
        assertTrue(sleep.headers.containsAll(listOf("position", "place", "place_name")))
        val row = sleep.rows.single()
        assertEquals(Xlsx.Cell.Text("back"), row[sleep.headers.indexOf("position")])
        assertEquals(Xlsx.Cell.Text("bedside_cot"), row[sleep.headers.indexOf("place")])
        // Nothing was named, because the place was one of the offered ones.
        assertEquals(Xlsx.Cell.Blank, row[sleep.headers.indexOf("place_name")])
    }

    @Test
    fun notesAreAbsentAsAColumn_notPresentAndEmpty() {
        val feeding = sheets().first { it.name == "Feeding" }
        assertFalse(feeding.headers.contains("notes"))

        val withNotes = sheets(ReportOptions(period = ReportPeriod.WEEK, includeNotes = true))
            .first { it.name == "Feeding" }
        assertTrue(withNotes.headers.contains("notes"))
        assertEquals(withNotes.headers.size, withNotes.rows.first().size)
    }

    @Test
    fun aSplitNightKeepsBothHalvesOnTheSleepSheet() {
        val sleep = sheets().first { it.name == "Sleep" }
        val row = sleep.rows.single()
        val before = row[sleep.headers.indexOf("minutes_before_midnight")]
        val after = row[sleep.headers.indexOf("minutes_after_midnight")]
        assertEquals(Xlsx.Cell.Whole(4 * 60), before)
        assertEquals(Xlsx.Cell.Whole(6 * 60), after)
    }

    /**
     * The sheet a pivot table is written against: raw dose rows, the parent's
     * own ceilings beside them, and an amount that is blank rather than zero
     * where none was recorded (BDR-18).
     */
    @Test
    fun theMedicineSheetCarriesTheDoseAndTheLimitsItWasGivenAgainst() {
        val sheet = sheets().first { it.name == "Medicines" }
        val row = sheet.rows.single()

        assertEquals(Xlsx.Cell.Text("Teething gel"), row[sheet.headers.indexOf("medicine")])
        assertEquals(Xlsx.Cell.Decimal(0.25), row[sheet.headers.indexOf("amount")])
        assertEquals(Xlsx.Cell.Text("cm"), row[sheet.headers.indexOf("unit")])
        assertEquals(Xlsx.Cell.Whole(6L), row[sheet.headers.indexOf("max_per_day")])
        assertEquals(Xlsx.Cell.Decimal(1.5), row[sheet.headers.indexOf("max_amount_per_day")])
        // Zero and not absent: the medicine's leaflet gave no gap at all.
        assertEquals(Xlsx.Cell.Whole(0L), row[sheet.headers.indexOf("min_interval_minutes")])
    }

    /** No doses in the period, no sheet — the book reports what happened. */
    @Test
    fun theMedicineSheetGoesWhenNoDoseFallsInTheRange() {
        val names = ReportWorkbook.build(
            buildReport(
                baby = baby,
                feedings = emptyList(),
                sleeps = emptyList(),
                diapers = emptyList(),
                growth = emptyList(),
                treatments = emptyList(),
                medicines = emptyList(),
                medicineDoses = emptyList(),
                options = ReportOptions(period = ReportPeriod.WEEK),
                now = now,
                zone = zone,
            ),
            zone,
        ).map { it.name }

        assertTrue("Medicines" !in names)
    }

    @Test
    fun theWholeBookIsWritable() {
        val bytes = ReportWorkbook.bytes(
            buildReport(
                baby = baby,
                feedings = emptyList(),
                sleeps = emptyList(),
                diapers = emptyList(),
                growth = emptyList(),
                treatments = emptyList(),
                medicines = emptyList(),
                medicineDoses = emptyList(),
                options = ReportOptions(period = ReportPeriod.SINCE_BIRTH),
                now = now,
                zone = zone,
            ),
            zone,
        )
        // "PK" — an empty period still produces a workbook, with empty sheets.
        assertEquals(0x50, bytes[0].toInt())
        assertEquals(0x4B, bytes[1].toInt())
    }
}
