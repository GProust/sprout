package com.gproust.sprout.data.export

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A very small .xlsx writer: a zip of a few XML parts, and nothing else.
 *
 * Sprout takes no dependency for this (ADR-0013). Apache POI is not shippable
 * on Android, and every lighter library still brings a parser we would then be
 * carrying in a privacy-first app that has no business reading files it did not
 * write. A workbook of plain cells is a documented format and about two hundred
 * lines, so it is written here — where it is also unit-testable on the JVM,
 * which is the only place this project can test anything (ADR-0006).
 *
 * What is deliberately *not* here: formulas, styling beyond a bold header row
 * and three number formats, merged cells, charts. A spreadsheet a doctor or a
 * dietitian will pivot wants typed columns, not decoration.
 */
object Xlsx {

    const val MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** Excel counts days from 1899-12-30 — the leap-year bug is part of the format. */
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private const val STYLE_DEFAULT = 0
    private const val STYLE_HEADER = 1
    private const val STYLE_DATE = 2
    private const val STYLE_TIME = 3
    private const val STYLE_DECIMAL = 4

    /** One cell. Numbers stay numbers and dates stay dates; nothing is a string with a unit glued on. */
    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Whole(val value: Long) : Cell
        data class Decimal(val value: Double) : Cell
        data class Day(val value: LocalDate) : Cell
        data class Clock(val value: LocalTime) : Cell
        data class Flag(val value: Boolean) : Cell

        /** Nothing was recorded. Left genuinely empty, so it cannot be read as a zero. */
        data object Blank : Cell
    }

    /**
     * One sheet: a header row, then the rows.
     *
     * [widths] are in Excel's character units, one per column; a sheet that
     * gives none gets the default width. The header row is frozen and filtered
     * so a long table is still usable after a hundred rows have scrolled past.
     */
    data class Sheet(
        val name: String,
        val headers: List<String>,
        val rows: List<List<Cell>>,
        val widths: List<Int> = emptyList(),
        val autoFilter: Boolean = true,
    )

    /** The whole workbook, as the bytes of a .xlsx file. */
    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "a workbook needs at least one sheet" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbook(sheets))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { index, sheet ->
                zip.put("xl/worksheets/sheet${index + 1}.xml", worksheet(sheet))
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int) = buildString {
        append(XML_HEADER)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"$MIME_TYPE.main+xml\"/>")
        append(
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-" +
                "officedocument.spreadsheetml.styles+xml\"/>",
        )
        for (i in 1..sheetCount) {
            append(
                "<Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd." +
                    "openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>",
            )
        }
        append("</Types>")
    }

    private fun workbook(sheets: List<Sheet>) = buildString {
        append(XML_HEADER)
        append("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>")
        sheets.forEachIndexed { index, sheet ->
            val id = index + 1
            append("<sheet name=\"${escape(sheetName(sheet.name))}\" sheetId=\"$id\" r:id=\"rId$id\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int) = buildString {
        append(XML_HEADER)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..sheetCount) {
            append(
                "<Relationship Id=\"rId$i\" Type=\"$NS_REL/worksheet\" " +
                    "Target=\"worksheets/sheet$i.xml\"/>",
            )
        }
        append(
            "<Relationship Id=\"rId${sheetCount + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>",
        )
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet) = buildString {
        val columns = maxOf(sheet.headers.size, sheet.rows.maxOfOrNull { it.size } ?: 0)
        val lastRow = sheet.rows.size + 1
        append(XML_HEADER)
        append("<worksheet xmlns=\"$NS_MAIN\">")
        append(
            "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" " +
                "activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>",
        )
        if (sheet.widths.isNotEmpty()) {
            append("<cols>")
            sheet.widths.forEachIndexed { index, width ->
                append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        append("<row r=\"1\">")
        sheet.headers.forEachIndexed { index, header ->
            append(cell(column(index) + "1", Cell.Text(header), STYLE_HEADER))
        }
        append("</row>")
        sheet.rows.forEachIndexed { rowIndex, row ->
            val r = rowIndex + 2
            append("<row r=\"$r\">")
            row.forEachIndexed { colIndex, value ->
                append(cell(column(colIndex) + r, value, styleOf(value)))
            }
            append("</row>")
        }
        append("</sheetData>")
        if (sheet.autoFilter && columns > 0 && sheet.rows.isNotEmpty()) {
            append("<autoFilter ref=\"A1:${column(columns - 1)}$lastRow\"/>")
        }
        append("</worksheet>")
    }

    private fun styleOf(cell: Cell) = when (cell) {
        is Cell.Day -> STYLE_DATE
        is Cell.Clock -> STYLE_TIME
        is Cell.Decimal -> STYLE_DECIMAL
        else -> STYLE_DEFAULT
    }

    private fun cell(ref: String, value: Cell, style: Int): String {
        val s = if (style == STYLE_DEFAULT) "" else " s=\"$style\""
        return when (value) {
            is Cell.Blank -> "<c r=\"$ref\"$s/>"
            is Cell.Text ->
                if (value.value.isEmpty()) {
                    "<c r=\"$ref\"$s/>"
                } else {
                    "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                        escape(value.value) + "</t></is></c>"
                }
            is Cell.Whole -> "<c r=\"$ref\"$s><v>${value.value}</v></c>"
            is Cell.Decimal -> "<c r=\"$ref\"$s><v>${trim(value.value)}</v></c>"
            is Cell.Day -> "<c r=\"$ref\"$s><v>${serial(value.value)}</v></c>"
            is Cell.Clock -> "<c r=\"$ref\"$s><v>${trim(fraction(value.value))}</v></c>"
            is Cell.Flag -> "<c r=\"$ref\"$s t=\"b\"><v>${if (value.value) 1 else 0}</v></c>"
        }
    }

    /** Days since Excel's epoch. */
    fun serial(date: LocalDate): Long = ChronoUnit.DAYS.between(EPOCH, date)

    /** A time of day as Excel sees it: the fraction of a day that has passed. */
    fun fraction(time: LocalTime): Double = time.toSecondOfDay() / 86_400.0

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    /** A1-style column name: A, B, … Z, AA, AB, … */
    fun column(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    /**
     * Excel refuses a sheet name over 31 characters or carrying `: \ / ? * [ ]`,
     * and refuses the whole file rather than the name — so they are taken out
     * here instead of trusting every caller.
     */
    private fun sheetName(name: String): String =
        name.map { if (it in ":\\/?*[]") ' ' else it }.joinToString("").take(31).ifBlank { "Sheet" }

    /**
     * XML text, with the five entities escaped and the control characters the
     * format has no encoding for dropped. A note typed on an entry can contain
     * anything at all, and a stray 0x01 in it would produce a file that no
     * spreadsheet will open.
     */
    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch.code < 0x20 -> Unit
                else -> append(ch)
            }
        }
    }

    private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private val ROOT_RELS = XML_HEADER +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val STYLES = XML_HEADER +
        "<styleSheet xmlns=\"$NS_MAIN\">" +
        "<numFmts count=\"3\">" +
        "<numFmt numFmtId=\"164\" formatCode=\"yyyy\\-mm\\-dd\"/>" +
        "<numFmt numFmtId=\"165\" formatCode=\"hh:mm\"/>" +
        "<numFmt numFmtId=\"166\" formatCode=\"0.0\"/>" +
        "</numFmts>" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"2\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "</fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"5\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "</cellXfs>" +
        "</styleSheet>"
}
