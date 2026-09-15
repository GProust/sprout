package com.gproust.sprout

import com.gproust.sprout.data.export.Xlsx
import com.gproust.sprout.data.export.Xlsx.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipInputStream

/**
 * The workbook writer, checked by unzipping what it produced.
 *
 * A spreadsheet nobody can open is worse than no spreadsheet, and the failure
 * mode is silent — the file downloads, and then Excel refuses it. So the parts
 * a reader needs are asserted by name, and the cells by the XML they become.
 */
class XlsxTest {

    private fun parts(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    private val sheet = Xlsx.Sheet(
        name = "Day by day",
        headers = listOf("date", "feeds", "ml", "wet", "note"),
        rows = listOf(
            listOf(
                Cell.Day(LocalDate.of(2026, 9, 8)),
                Cell.Whole(8),
                Cell.Blank,
                Cell.Flag(true),
                Cell.Text("fine & well <today>"),
            ),
            listOf(
                Cell.Day(LocalDate.of(2026, 9, 9)),
                Cell.Whole(7),
                Cell.Decimal(212.5),
                Cell.Flag(false),
                Cell.Clock(LocalTime.of(6, 0)),
            ),
        ),
        widths = listOf(12, 8, 8, 8, 30),
    )

    @Test
    fun everyPartAReaderLooksForIsThere() {
        val parts = parts(Xlsx.write(listOf(sheet)))
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("_rels/.rels"))
        assertTrue(parts.containsKey("xl/workbook.xml"))
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(parts.containsKey("xl/styles.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun eachSheetIsNamedAndRelated() {
        val parts = parts(Xlsx.write(listOf(sheet, sheet.copy(name = "Growth"))))
        val workbook = parts.getValue("xl/workbook.xml")
        assertTrue(workbook.contains("name=\"Day by day\""))
        assertTrue(workbook.contains("name=\"Growth\""))
        val rels = parts.getValue("xl/_rels/workbook.xml.rels")
        assertTrue(rels.contains("worksheets/sheet1.xml"))
        assertTrue(rels.contains("worksheets/sheet2.xml"))
        assertTrue(rels.contains("styles.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"))
    }

    @Test
    fun aDateIsANumberWithADateFormat_notAString() {
        val body = parts(Xlsx.write(listOf(sheet))).getValue("xl/worksheets/sheet1.xml")
        // 2026-09-08 is 46273 days after Excel's 1899-12-30 epoch.
        assertEquals(46273L, Xlsx.serial(LocalDate.of(2026, 9, 8)))
        assertTrue(body.contains("<c r=\"A2\" s=\"2\"><v>46273</v></c>"))
        assertFalse(body.contains("2026-09-08"))
    }

    @Test
    fun aTimeIsTheFractionOfADayItHasReached() {
        assertEquals(0.25, Xlsx.fraction(LocalTime.of(6, 0)), 1e-9)
        val body = parts(Xlsx.write(listOf(sheet))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(body.contains("<c r=\"E3\" s=\"3\"><v>0.25</v></c>"))
    }

    @Test
    fun nothingRecordedStaysEmpty_soItCannotBeReadAsZero() {
        val body = parts(Xlsx.write(listOf(sheet))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(body.contains("<c r=\"C2\"/>"))
        assertFalse(body.contains("<c r=\"C2\"><v>0</v></c>"))
    }

    @Test
    fun textIsEscaped_soANoteCannotBreakTheFile() {
        val body = parts(Xlsx.write(listOf(sheet))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(body.contains("fine &amp; well &lt;today&gt;"))
    }

    @Test
    fun controlCharactersAreDropped_theFormatCannotCarryThem() {
        val nasty = Xlsx.Sheet(
            name = "Notes",
            headers = listOf("note"),
            rows = listOf(listOf(Cell.Text("before\u0001after"))),
        )
        val body = parts(Xlsx.write(listOf(nasty))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(body.contains("beforeafter"))
        assertFalse(body.contains("\u0001"))
    }

    @Test
    fun aSheetNameExcelWouldRefuseIsMadeAcceptable() {
        val parts = parts(
            Xlsx.write(
                listOf(sheet.copy(name = "Feeding/Sleep [2026]: a very long sheet name indeed")),
            ),
        )
        val name = Regex("name=\"([^\"]+)\"").find(parts.getValue("xl/workbook.xml"))!!.groupValues[1]
        assertTrue(name.length <= 31)
        assertFalse(name.any { it in ":\\/?*[]" })
    }

    @Test
    fun theHeaderRowIsBoldAndFrozen_andTheRowsAreFiltered() {
        val body = parts(Xlsx.write(listOf(sheet))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(body.contains("s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">date"))
        assertTrue(body.contains("state=\"frozen\""))
        assertTrue(body.contains("<autoFilter ref=\"A1:E3\"/>"))
    }

    @Test
    fun columnsCarryOnPastZ() {
        assertEquals("A", Xlsx.column(0))
        assertEquals("Z", Xlsx.column(25))
        assertEquals("AA", Xlsx.column(26))
        assertEquals("AB", Xlsx.column(27))
        assertEquals("BA", Xlsx.column(52))
    }
}
