package com.gproust.sprout.ui.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gproust.sprout.R
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatDate
import com.gproust.sprout.ui.diaper.label
import com.gproust.sprout.ui.stats.DayStats
import com.gproust.sprout.ui.stats.GrowthMeasure
import com.gproust.sprout.ui.stats.WhoPercentiles
import com.gproust.sprout.ui.stats.WhoPlacement
import com.gproust.sprout.ui.stats.WhoSex
import com.gproust.sprout.ui.stats.whoBand
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The report, drawn onto pages.
 *
 * Everything is drawn with `android.graphics` onto the canvas `PdfDocument`
 * hands out — no library, no new permission, nothing that parses a file
 * (ADR-0013). The layout is a list of [Block]s of known height, laid onto pages
 * before anything is drawn, which is what lets every page say "3 / 5" instead
 * of counting pages as it goes.
 *
 * The document interprets nothing (BDR-0012). There is no threshold here, no
 * colour that means "low", and no sentence that reads as an assessment: every
 * figure is a count, a total, or a reading of the WHO tables the app ships.
 */
class ReportPdf(
    private val context: Context,
    private val report: ReportContent,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** A page and its margins, in PostScript points — the unit PdfDocument works in. */
    data class Paper(val width: Int, val height: Int) {
        companion object {
            val A4 = Paper(595, 842)
            val LETTER = Paper(612, 792)

            /**
             * Letter where letter is the paper, A4 everywhere else.
             *
             * Not a setting: nobody wants to think about paper sizes, and a
             * printed page that is silently scaled and clipped is worse than
             * either choice made for them.
             */
            fun forLocale(locale: Locale): Paper =
                if (locale.country.uppercase() in LETTER_COUNTRIES) LETTER else A4

            private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "PR")
        }
    }

    private val paper = Paper.forLocale(Locale.getDefault())
    private val margin = 42f
    private val contentWidth = paper.width - margin * 2
    private val contentBottom = paper.height - margin - FOOTER_HEIGHT

    // --- ink -------------------------------------------------------------

    private val ink = Color.parseColor("#151A17")
    private val muted = Color.parseColor("#6B756D")
    private val grid = Color.parseColor("#E2E7E3")
    private val rule = Color.parseColor("#B9C1BB")
    private val warm = Color.parseColor("#C97722")
    private val cool = Color.parseColor("#3A7FC4")
    private val clay = Color.parseColor("#B26A2B")
    private val bar = Color.parseColor("#4A6B59")

    private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sans: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private fun paint(
        size: Float,
        colour: Int = ink,
        face: Typeface = sans,
        align: Paint.Align = Paint.Align.LEFT,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        typeface = face
        textAlign = align
    }

    private val titlePaint = paint(21f, ink, serifBold)
    private val headingPaint = paint(13f, ink, serifBold)
    private val bodyPaint = paint(8.6f)
    private val boldPaint = paint(8.6f, ink, sansBold)
    private val mutedPaint = paint(8.6f, muted)
    private val smallPaint = paint(7.4f, muted)
    private val eyebrowPaint = paint(7.2f, muted, sansBold)
    private val rightPaint = paint(8.6f, ink, sans, Paint.Align.RIGHT)
    private val rightBoldPaint = paint(8.6f, ink, sansBold, Paint.Align.RIGHT)
    private val tinyPaint = paint(6.6f, muted)
    private val tinyCentre = paint(6.6f, muted, sans, Paint.Align.CENTER)
    private val tinyRight = paint(6.6f, muted, sans, Paint.Align.RIGHT)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
        color = rule
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun string(id: Int): String = context.getString(id)
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    // --- the document ----------------------------------------------------

    /** Writes the report to [out]; the caller owns the stream. */
    fun write(out: OutputStream) {
        val document = PdfDocument()
        val pages = paginate(blocks())
        pages.forEachIndexed { index, page ->
            val info = PdfDocument.PageInfo.Builder(paper.width, paper.height, index + 1).create()
            val pdfPage = document.startPage(info)
            page.forEach { placed -> placed.block.draw(pdfPage.canvas, placed.y) }
            drawFooter(pdfPage.canvas, index + 1, pages.size)
            document.finishPage(pdfPage)
        }
        document.writeTo(out)
        document.close()
    }

    /**
     * One drawable unit of the document, whose height is known before anything
     * is drawn.
     *
     * [repeatHeader] is what a table row carries so that, if the page breaks
     * above it, the column headings are drawn again at the top of the next page
     * rather than leaving a slab of unlabelled figures.
     */
    private class Block(
        val height: Float,
        val newPage: Boolean = false,
        val repeatHeader: Block? = null,
        val draw: (Canvas, Float) -> Unit,
    )

    private class Placed(val block: Block, val y: Float)

    private fun paginate(blocks: List<Block>): List<List<Placed>> {
        val pages = mutableListOf<MutableList<Placed>>()
        var current = mutableListOf<Placed>()
        var y = margin
        for (block in blocks) {
            val breaks = block.newPage && current.isNotEmpty()
            if (breaks || (current.isNotEmpty() && y + block.height > contentBottom)) {
                pages += current
                current = mutableListOf()
                y = margin
                block.repeatHeader?.let { header ->
                    current += Placed(header, y)
                    y += header.height
                }
            }
            current += Placed(block, y)
            y += block.height
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    private fun drawFooter(canvas: Canvas, page: Int, total: Int) {
        val y = paper.height - margin + 4f
        linePaint.color = grid
        canvas.drawLine(margin, y - 10f, paper.width - margin, y - 10f, linePaint)
        linePaint.color = rule
        canvas.drawText(
            string(
                R.string.report_footer,
                report.babyName,
                formatDate(context, report.range.from.millis()),
                formatDate(context, report.range.to.millis()),
            ),
            margin,
            y,
            tinyPaint,
        )
        canvas.drawText("$page / $total", paper.width - margin, y, tinyRight)
    }

    // --- what the document is made of ------------------------------------

    private fun blocks(): List<Block> = buildList {
        add(header())
        if (!report.hasEntries) {
            add(paragraph(string(R.string.report_nothing_logged), mutedPaint, topGap = 14f))
        } else {
            add(glanceTable())
            add(feedingSection())
            add(sleepSection())
            add(nappySection())
        }
        addAll(growthSection())
        addAll(treatmentSection())
        addAll(dailySection())
        add(paragraph(string(R.string.report_about), smallPaint, topGap = 14f))
    }

    /** Whose record this is, what it covers, and where the figures came from. */
    private fun header(): Block {
        val identity = buildList {
            add(string(R.string.report_field_dob) to formatDate(context, report.birthDate))
            add(
                string(R.string.report_field_age) to
                    babyAge(context, report.birthDate, report.range.to.millis()),
            )
            add(
                string(R.string.report_field_period) to string(
                    R.string.stats_window_range,
                    formatDate(context, report.range.from.millis()),
                    formatDate(context, report.range.to.millis()),
                ),
            )
            add(string(R.string.report_field_days) to report.range.dayCount.toString())
            add(
                string(R.string.report_field_generated) to
                    formatDate(context, report.generatedAt),
            )
            add(
                string(R.string.report_field_days_logged) to string(
                    R.string.report_days_of,
                    report.daysWithEntries,
                    report.range.dayCount,
                ),
            )
        }
        val provenance = layout(string(R.string.report_provenance), italic(smallPaint), contentWidth - 12f)
        val rows = ceil(identity.size / 2f).toInt()
        val height = 14f + 26f + 14f + rows * 14f + 10f + provenance.height + 10f

        return Block(height) { canvas, top ->
            canvas.drawText(string(R.string.report_doc_title).uppercase(), margin, top + 8f, eyebrowPaint)
            canvas.drawText(
                string(R.string.app_name) + " " + versionName(),
                paper.width - margin,
                top + 8f,
                tinyRight,
            )
            canvas.drawLine(margin, top + 12f, paper.width - margin, top + 12f, strokePaint(ink, 1f))
            canvas.drawText(report.babyName, margin, top + 34f, titlePaint)
            canvas.drawText(string(R.string.report_doc_subtitle), margin, top + 47f, smallPaint)

            var y = top + 62f
            val columnWidth = contentWidth / 2f
            identity.forEachIndexed { index, (label, value) ->
                val column = index % 2
                val x = margin + column * columnWidth
                if (column == 0 && index > 0) y += 14f
                canvas.drawText(label, x, y, mutedPaint)
                canvas.drawText(value, x + columnWidth - 12f, y, rightBoldPaint)
                canvas.drawLine(x, y + 3f, x + columnWidth - 12f, y + 3f, strokePaint(grid, 0.6f))
            }

            y += 16f
            canvas.drawLine(margin, y - 6f, margin, y + provenance.height - 2f, strokePaint(rule, 1.4f))
            drawLayout(canvas, provenance, margin + 8f, y - 8f)
        }
    }

    /** The summary a paediatric appointment opens with, per day and over the period. */
    private fun glanceTable(): Block {
        data class Row(val label: String, val perDay: String, val total: String, val strong: Boolean)

        val averages = report.averages
        val rows = buildList {
            add(
                Row(
                    string(R.string.stats_feeds_per_day),
                    decimal(averages.feedsPerDay),
                    report.feedTotal.toString(),
                    true,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_breast_per_day),
                    decimal(averages.breastfeedsPerDay),
                    report.breastTotal.toString(),
                    false,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_bottle_per_day),
                    decimal(averages.bottlesPerDay),
                    report.bottleCount.toString(),
                    false,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_solid_per_day),
                    decimal(averages.solidsPerDay),
                    report.solidCount.toString(),
                    false,
                ),
            )
            add(
                Row(
                    string(R.string.report_time_at_breast),
                    hours(averages.breastMillisPerDay),
                    hours(report.breastMillisTotal),
                    true,
                ),
            )
            add(
                Row(
                    string(R.string.report_bottle_volume),
                    string(R.string.feeding_amount_ml, averages.bottleMlPerDay),
                    string(R.string.feeding_amount_ml, report.bottleMlTotal),
                    true,
                ),
            )
            if (report.solidCount > 0) {
                add(
                    Row(
                        string(R.string.report_solid_weight),
                        string(R.string.feeding_amount_g, averages.solidGramsPerDay),
                        string(R.string.feeding_amount_g, report.solidGramsTotal),
                        true,
                    ),
                )
            }
            add(
                Row(
                    string(R.string.stats_sleep_per_day),
                    hours(averages.sleepMillisPerDay),
                    hours(report.sleepMillisTotal),
                    true,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_sleeps_per_day),
                    decimal(averages.sleepsPerDay),
                    report.sleepCountTotal.toString(),
                    false,
                ),
            )
            add(
                Row(
                    string(R.string.stats_diapers_per_day),
                    decimal(averages.diapersPerDay),
                    report.diaperTotal.toString(),
                    true,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_wet_per_day),
                    decimal(averages.wetPerDay),
                    report.wetTotal.toString(),
                    false,
                ),
            )
            add(
                Row(
                    "   " + string(R.string.stats_dirty_per_day),
                    decimal(averages.dirtyPerDay),
                    report.dirtyTotal.toString(),
                    false,
                ),
            )
        }

        val note = layout(averagesNote(), smallPaint, contentWidth)
        val height = 30f + rows.size * 13f + 8f + note.height + 12f

        return Block(height) { canvas, top ->
            drawHeading(canvas, top, string(R.string.report_at_a_glance), string(R.string.report_per_day))
            var y = top + 32f
            val perDayX = margin + contentWidth * 0.72f
            val totalX = margin + contentWidth
            canvas.drawText(string(R.string.report_per_day).uppercase(), perDayX, y - 6f, rightAligned(eyebrowPaint))
            canvas.drawText(
                string(R.string.report_over_days, report.range.dayCount).uppercase(),
                totalX,
                y - 6f,
                rightAligned(eyebrowPaint),
            )
            rows.forEach { row ->
                val labelPaint = if (row.strong) boldPaint else mutedPaint
                val valuePaint = if (row.strong) rightBoldPaint else rightPaint
                canvas.drawText(row.label, margin, y + 6f, labelPaint)
                canvas.drawText(row.perDay, perDayX, y + 6f, valuePaint)
                canvas.drawText(row.total, totalX, y + 6f, rightPaint)
                canvas.drawLine(margin, y + 9f, totalX, y + 9f, strokePaint(grid, 0.5f))
                y += 13f
            }
            drawLayout(canvas, note, margin, y + 4f)
        }
    }

    /**
     * Which days the averages are over, and how many bottles carried a volume.
     *
     * Both sentences exist so a figure cannot be read as more than it is: an
     * average that quietly included a half-lived today, or a millilitre figure
     * that quietly averaged in the bottles nobody measured, would each be a
     * smaller number presented as the same kind of fact.
     */
    private fun averagesNote(): String = buildString {
        append(
            string(
                R.string.report_note_averages,
                report.averages.dayCount,
                formatDate(context, report.range.to.millis()),
            ),
        )
        if (report.bottleCount > report.bottlesWithVolume) {
            append(" ")
            append(
                string(
                    R.string.report_note_bottles,
                    report.bottlesWithVolume,
                    report.bottleCount,
                ),
            )
        }
        if (report.solidCount > report.solidsWithGrams) {
            append(" ")
            append(
                string(R.string.report_note_solids, report.solidsWithGrams, report.solidCount),
            )
        }
    }

    // --- the charts ------------------------------------------------------

    private fun feedingSection(): Block {
        val days = report.days
        val breast = days.map { it.breastCount.toFloat() }
        val bottle = days.map { it.bottleCount.toFloat() }
        val solid = days.map { it.solidCount.toFloat() }
        val ml = days.map { it.bottleMl.toFloat() }
        val breastHours = days.map { it.breastMillis / 3_600_000f }

        val drawsMl = ml.any { it > 0f }
        val drawsBreast = breastHours.any { it > 0f }
        val chartHeight = 92f
        val height = 30f + chartHeight + 14f +
            (if (drawsMl) chartHeight + 22f else 0f) +
            (if (drawsBreast) chartHeight + 22f else 0f) + 10f

        return Block(height) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(R.string.stats_feeding_title),
                string(R.string.report_per_day_average, decimal(report.averages.feedsPerDay)),
            )
            var y = top + 30f
            drawBars(
                canvas, y, chartHeight,
                listOf(
                    Series(breast, cool),
                    Series(bottle, warm),
                    Series(solid, cool, hatchWith = warm),
                ),
            )
            y += chartHeight + 4f
            drawKeys(
                canvas, y,
                listOf(
                    Key(string(R.string.stats_breast_per_day), cool),
                    Key(string(R.string.stats_bottle_per_day), warm),
                    Key(string(R.string.stats_solid_per_day), cool, warm),
                ),
            )
            y += 12f

            if (drawsMl) {
                canvas.drawText(string(R.string.report_chart_bottle_ml), margin, y + 8f, smallPaint)
                y += 12f
                drawBars(canvas, y, chartHeight, listOf(Series(ml, warm)))
                y += chartHeight + 10f
            }
            if (drawsBreast) {
                canvas.drawText(string(R.string.report_chart_breast_hours), margin, y + 8f, smallPaint)
                y += 12f
                drawBars(canvas, y, chartHeight, listOf(Series(breastHours, cool)))
            }
        }
    }

    private fun sleepSection(): Block {
        val hours = report.days.map { it.sleepMillis / 3_600_000f }
        val chartHeight = 92f
        return Block(30f + 14f + chartHeight + 12f) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(R.string.stats_sleep_title),
                string(R.string.report_per_day_average, hours(report.averages.sleepMillisPerDay)),
            )
            canvas.drawText(
                string(
                    R.string.report_sleep_line,
                    decimal(report.averages.sleepsPerDay),
                    hours(report.longestSleepMillis),
                ),
                margin,
                top + 38f,
                smallPaint,
            )
            drawBars(canvas, top + 44f, chartHeight, listOf(Series(hours, cool)))
        }
    }

    private fun nappySection(): Block {
        val wetOnly = report.days.map { it.wetOnlyCount.toFloat() }
        val both = report.days.map { it.bothCount.toFloat() }
        val dirtyOnly = report.days.map { it.dirtyOnlyCount.toFloat() }
        val chartHeight = 92f
        val colours = report.stoolColours.take(4)
        val hasColours = colours.isNotEmpty()

        return Block(30f + chartHeight + 26f + (if (hasColours) 12f else 0f)) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(R.string.stats_diaper_title),
                string(R.string.report_per_day_average, decimal(report.averages.diapersPerDay)),
            )
            drawBars(
                canvas, top + 30f, chartHeight,
                listOf(
                    Series(wetOnly, cool),
                    Series(both, cool, hatchWith = clay),
                    Series(dirtyOnly, clay),
                ),
            )
            var y = top + 30f + chartHeight + 4f
            drawKeys(
                canvas, y,
                listOf(
                    Key(string(R.string.stats_wet_per_day), cool),
                    Key(string(R.string.stats_diaper_both), cool, clay),
                    Key(string(R.string.stats_dirty_per_day), clay),
                ),
            )
            y += 14f
            if (hasColours) {
                val text = colours.joinToString(", ") { (colour, count) ->
                    "${colour.label(context)}: $count"
                }
                canvas.drawText(string(R.string.report_stool_colours, text), margin, y + 6f, smallPaint)
            }
        }
    }

    // --- growth ----------------------------------------------------------

    private fun growthSection(): List<Block> {
        val readings = report.growth
        if (readings.isEmpty()) return emptyList()

        val reference = report.options.reference
        val weightHeight = 128f
        val smallHeight = 104f
        val hasLength = readings.any { it.entry.heightMm != null }
        val hasHead = readings.any { it.entry.headMm != null }

        val chartsBlock = Block(30f + weightHeight + 12f + (if (hasLength || hasHead) smallHeight + 14f else 0f)) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(R.string.stats_growth_title),
                referenceLabel(reference).uppercase(),
            )
            canvas.drawText(
                string(R.string.report_chart_weight),
                margin,
                top + 36f,
                smallPaint,
            )
            drawGrowthChart(
                canvas, margin, top + 40f, contentWidth, weightHeight - 16f,
                GrowthMeasure.WEIGHT, reference,
            )
            if (hasLength || hasHead) {
                val half = (contentWidth - 16f) / 2f
                var y = top + 30f + weightHeight + 6f
                if (hasLength) {
                    canvas.drawText(string(R.string.report_chart_length), margin, y, smallPaint)
                    drawGrowthChart(
                        canvas, margin, y + 4f, half, smallHeight - 18f,
                        GrowthMeasure.LENGTH, reference,
                    )
                }
                if (hasHead) {
                    val x = margin + half + 16f
                    canvas.drawText(string(R.string.report_chart_head), x, y, smallPaint)
                    drawGrowthChart(
                        canvas, x, y + 4f, half, smallHeight - 18f,
                        GrowthMeasure.HEAD, reference,
                    )
                }
            }
        }

        val headers = listOf(
            string(R.string.report_col_date),
            string(R.string.report_col_age),
            string(R.string.stats_measure_weight),
            string(R.string.report_col_centile),
            string(R.string.stats_measure_length),
            string(R.string.report_col_centile),
            string(R.string.stats_measure_head),
            string(R.string.report_col_centile),
        )
        val rows = readings.map { reading ->
            listOf(
                formatDate(context, reading.entry.time),
                babyAge(context, report.birthDate, reading.entry.time),
                reading.entry.weightGrams?.let { grams(it) } ?: DASH,
                centile(reading.weight),
                reading.entry.heightMm?.let { centimetres(it) } ?: DASH,
                centile(reading.length),
                reading.entry.headMm?.let { centimetres(it) } ?: DASH,
                centile(reading.head),
            )
        }
        val weights = listOf(1.3f, 1.2f, 1f, 1.2f, 1f, 1.2f, 1f, 1.2f)
        val alignRight = listOf(false, false, true, true, true, true, true, true)

        val note = string(
            if (reference == null) R.string.report_centile_note_both else R.string.report_centile_note_one,
            referenceLabel(reference),
        )

        return listOf(chartsBlock) +
            tableBlocks(headers, rows, weights, alignRight, repeatOnBreak = true) +
            paragraph(note, smallPaint, topGap = 6f)
    }

    private fun centile(placement: WhoPlacement?): String {
        if (placement == null) return DASH
        val low = placement.lowPercentile.roundToInt().coerceIn(1, 99)
        val high = placement.highPercentile.roundToInt().coerceIn(1, 99)
        return if (low == high) {
            string(R.string.report_centile_one, low)
        } else {
            string(R.string.report_centile_span, low, high)
        }
    }

    // --- treatments ------------------------------------------------------

    private fun treatmentSection(): List<Block> {
        val courses = report.treatments
        if (courses.isEmpty()) return emptyList()

        val rowHeight = 22f
        val timeline = Block(30f + 16f + courses.size * rowHeight + 18f) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(R.string.screen_treatments),
                string(R.string.report_over_the_period).uppercase(),
            )
            drawTimeline(canvas, top + 34f, courses, rowHeight)
            val y = top + 34f + 14f + courses.size * rowHeight + 2f
            drawKeys(
                canvas, y,
                listOf(
                    Key(string(R.string.report_key_running), bar),
                    Key(string(R.string.report_key_dose), bar, thin = true),
                ),
            )
        }

        val headers = listOf(
            string(R.string.report_col_name),
            string(R.string.report_col_dose),
            string(R.string.report_col_schedule),
            string(R.string.treatment_start),
            string(R.string.treatment_end),
        )
        val rows = courses.map { course ->
            val treatment = course.treatment
            listOf(
                treatment.name,
                treatment.dose.orEmpty().ifEmpty { DASH },
                scheduleOf(course),
                formatDate(context, treatment.startDate),
                treatment.endDate?.let { formatDate(context, it) }
                    ?: string(R.string.report_ongoing),
            )
        }
        return listOf(timeline) +
            tableBlocks(headers, rows, listOf(1.3f, 1.2f, 1.8f, 1f, 1f), List(5) { false }, true) +
            paragraph(string(R.string.report_treatment_note), smallPaint, topGap = 6f)
    }

    private fun scheduleOf(course: TreatmentCourse): String {
        val times = course.treatment.timesOfDay.sorted().joinToString(", ") {
            "%02d:%02d".format(it / 60, it % 60)
        }
        val every = if (course.treatment.intervalDays <= 1) {
            string(R.string.treatment_freq_daily)
        } else {
            string(R.string.treatment_freq_every_n, course.treatment.intervalDays)
        }
        return if (times.isEmpty()) every else string(R.string.treatment_schedule_summary, every, times)
    }

    /**
     * The courses as bars over the report's own days.
     *
     * Two things the bar must not say: that a course began when the range did,
     * or that a weekly course was given daily. So a course that started earlier
     * carries a chevron at the clamped end, one still running carries the other,
     * and anything given less often than daily is drawn as a faint span with a
     * mark on each scheduled dose.
     */
    private fun drawTimeline(canvas: Canvas, top: Float, courses: List<TreatmentCourse>, rowHeight: Float) {
        val labelWidth = contentWidth * 0.22f
        val plotLeft = margin + labelWidth
        val plotWidth = contentWidth - labelWidth
        val days = report.range.dayCount
        fun x(index: Float) = plotLeft + plotWidth * (index / days).coerceIn(0f, 1f)

        val axisY = top + 8f
        val ticks = tickIndices(days)
        ticks.forEach { index ->
            val tx = x(index.toFloat())
            canvas.drawLine(tx, axisY, tx, axisY + 6f + courses.size * rowHeight, strokePaint(grid, 0.6f))
            if (index <= days - 4) {
                canvas.drawText(dayLabel(index), tx, axisY - 4f, tinyCentre)
            }
        }
        canvas.drawText(dayLabel(days - 1), margin + contentWidth, axisY - 4f, tinyRight)
        canvas.drawLine(plotLeft, axisY, margin + contentWidth, axisY, strokePaint(ink, 0.8f))

        courses.forEachIndexed { index, course ->
            val centre = axisY + 8f + index * rowHeight + rowHeight / 2f
            canvas.drawText(course.treatment.name, margin, centre - 1f, boldPaint)
            course.treatment.dose?.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it, margin, centre + 8f, tinyPaint)
            }

            val barHeight = 8f
            val x0 = x(course.startIndex.toFloat())
            val x1 = x(course.endIndex.toFloat())
            val rect = RectF(x0, centre - barHeight / 2f, x1, centre + barHeight / 2f)
            if (course.doseDays.isEmpty()) {
                fillPaint.color = bar
                canvas.drawRect(rect, fillPaint)
            } else {
                fillPaint.color = withAlpha(bar, 60)
                canvas.drawRect(rect, fillPaint)
                fillPaint.color = bar
                course.doseDays.forEach { day ->
                    val dx = x(day + 0.5f)
                    canvas.drawRect(dx - 1.2f, rect.top, dx + 1.2f, rect.bottom, fillPaint)
                }
            }
            fillPaint.color = bar
            if (course.startsBefore) canvas.drawPath(chevron(x0 - 2f, centre, -6f), fillPaint)
            if (course.runsPast) canvas.drawPath(chevron(x1 + 2f, centre, 6f), fillPaint)
        }
    }

    private fun chevron(x: Float, y: Float, reach: Float) = Path().apply {
        moveTo(x, y - 4f)
        lineTo(x + reach, y)
        lineTo(x, y + 4f)
        close()
    }

    // --- the day-by-day appendix -----------------------------------------

    /**
     * One row per day — or per week, once the range is long enough that the
     * appendix would outgrow the report it belongs to. Ninety-two days is the
     * line: a quarter still reads as days, a year does not.
     */
    private fun dailySection(): List<Block> {
        if (!report.options.includeDailyTable || report.days.isEmpty()) return emptyList()
        val weekly = report.days.size > MAX_DAILY_ROWS

        val headers = listOf(
            string(if (weekly) R.string.report_col_week else R.string.report_col_day),
            string(R.string.stats_feeds_per_day),
            string(R.string.stats_breast_per_day),
            string(R.string.report_col_at_breast),
            string(R.string.stats_bottle_per_day),
            string(R.string.report_col_ml),
            string(R.string.stats_sleep_title),
            string(R.string.report_col_settled),
            string(R.string.stats_diapers_per_day),
            string(R.string.stats_wet_per_day),
            string(R.string.stats_dirty_per_day),
        )
        val groups = if (weekly) report.days.chunked(7) else report.days.map { listOf(it) }
        val rows = groups.map { group -> dayRow(group, weekly) }
        val weights = listOf(1.5f) + List(10) { 1f }
        val alignRight = listOf(false) + List(10) { true }

        val heading = Block(26f, newPage = true) { canvas, top ->
            drawHeading(
                canvas,
                top,
                string(if (weekly) R.string.report_week_by_week else R.string.report_day_by_day),
                string(R.string.report_days_count, report.range.dayCount).uppercase(),
            )
        }
        val note = if (weekly) {
            listOf(paragraph(string(R.string.report_weekly_note), smallPaint, topGap = 6f))
        } else {
            emptyList()
        }
        return listOf(heading) + tableBlocks(headers, rows, weights, alignRight, true) + note
    }

    private fun dayRow(group: List<DayStats>, weekly: Boolean): List<String> {
        val label = if (weekly && group.size > 1) {
            string(
                R.string.stats_window_range,
                formatDate(context, group.first().date.millis()),
                formatDate(context, group.last().date.millis()),
            )
        } else {
            formatDate(context, group.first().date.millis())
        }
        return listOf(
            label,
            group.sumOf { it.feedCount }.toString(),
            group.sumOf { it.breastCount }.toString(),
            hours(group.sumOf { it.breastMillis }),
            group.sumOf { it.bottleCount }.toString(),
            group.sumOf { it.bottleMl }.toString(),
            hours(group.sumOf { it.sleepMillis }),
            group.sumOf { it.sleepCount }.toString(),
            group.sumOf { it.diaperCount }.toString(),
            group.sumOf { it.wetCount }.toString(),
            group.sumOf { it.dirtyCount }.toString(),
        )
    }

    // --- drawing helpers --------------------------------------------------

    private class Series(val values: List<Float>, val colour: Int, val hatchWith: Int? = null)

    private class Key(val label: String, val colour: Int, val hatchWith: Int? = null, val thin: Boolean = false)

    /**
     * Stacked bars, one column per day, with a zero line and four gridlines.
     *
     * The scale is drawn from the tallest stack and rounded up to something a
     * person reads without effort, and every gridline is labelled with the
     * value it stands for — a chart whose axis says nothing is a picture.
     */
    private fun drawBars(canvas: Canvas, top: Float, height: Float, series: List<Series>) {
        val axisWidth = 22f
        val labelHeight = 10f
        val plotLeft = margin + axisWidth
        val plotWidth = contentWidth - axisWidth
        val plotHeight = height - labelHeight
        val count = series.firstOrNull()?.values?.size ?: return
        if (count == 0) return

        val tallest = (0 until count).maxOf { i -> series.sumOf { it.values[i].toDouble() } }.toFloat()
        val max = niceCeiling(tallest)
        val bottom = top + plotHeight

        for (tick in 0..4) {
            val value = max * tick / 4f
            val y = bottom - plotHeight * tick / 4f
            canvas.drawLine(plotLeft, y, margin + contentWidth, y, strokePaint(if (tick == 0) ink else grid, if (tick == 0) 0.8f else 0.5f))
            canvas.drawText(axisLabel(value), plotLeft - 4f, y + 2.4f, tinyRight)
        }

        val step = plotWidth / count
        val width = (step * 0.68f).coerceAtLeast(0.6f)
        for (i in 0 until count) {
            var base = bottom
            val x = plotLeft + step * i + (step - width) / 2f
            series.forEach { s ->
                val value = s.values[i]
                if (value <= 0f) return@forEach
                val barHeight = plotHeight * (value / max)
                val rect = RectF(x, base - barHeight, x + width, base)
                base -= barHeight
                fillPaint.color = s.colour
                canvas.drawRect(rect, fillPaint)
                s.hatchWith?.let { hatch(canvas, rect, it) }
            }
        }

        tickIndices(count).forEach { index ->
            if (index > count - 4) return@forEach
            canvas.drawText(dayLabel(index), plotLeft + step * (index + 0.5f), bottom + 8f, tinyCentre)
        }
        canvas.drawText(dayLabel(count - 1), margin + contentWidth, bottom + 8f, tinyRight)
    }

    /** Diagonal strokes inside [rect]: the third key, without a third hue. */
    private fun hatch(canvas: Canvas, rect: RectF, colour: Int) {
        canvas.save()
        canvas.clipRect(rect)
        val stroke = strokePaint(colour, 1.6f)
        var x = rect.left - rect.height()
        while (x < rect.right + rect.height()) {
            canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, stroke)
            x += 4.5f
        }
        canvas.restore()
    }

    private fun drawKeys(canvas: Canvas, top: Float, keys: List<Key>) {
        var x = margin
        keys.forEach { key ->
            val size = if (key.thin) 2.4f else 6f
            val rect = RectF(x, top + 1f, x + size, top + 7f)
            fillPaint.color = key.colour
            canvas.drawRect(rect, fillPaint)
            key.hatchWith?.let { hatch(canvas, rect, it) }
            canvas.drawText(key.label, x + size + 4f, top + 7f, tinyPaint)
            x += size + 8f + tinyPaint.measureText(key.label) + 14f
        }
    }

    /** The WHO band behind the baby's own line, drawn as the app draws it. */
    private fun drawGrowthChart(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        measure: GrowthMeasure,
        reference: WhoSex?,
    ) {
        val points = report.growth.mapNotNull { reading ->
            val value = when (measure) {
                GrowthMeasure.WEIGHT -> reading.entry.weightGrams?.let { it / 1000.0 }
                GrowthMeasure.LENGTH -> reading.entry.heightMm?.let { it / 10.0 }
                GrowthMeasure.HEAD -> reading.entry.headMm?.let { it / 10.0 }
            } ?: return@mapNotNull null
            reading.ageMonths to value
        }
        val maxAge = (points.maxOfOrNull { it.first } ?: 1.0).coerceAtLeast(1.0) * 1.15
        val band = whoBand(measure, 0.0, maxAge, steps = 24)
        if (band.isEmpty()) return

        val sexes = reference?.let { listOf(it) } ?: listOf(WhoSex.GIRLS, WhoSex.BOYS)
        val values = band.flatMap { p -> sexes.flatMap { listOf(p.of(it).p3, p.of(it).p97) } } +
            points.map { it.second }
        val min = values.min()
        val span = (values.max() - min).coerceAtLeast(0.001)
        val axisWidth = 20f
        val labelHeight = 10f
        val plotLeft = left + axisWidth
        val plotWidth = width - axisWidth
        val plotHeight = height - labelHeight
        val pad = plotHeight * 0.06f

        fun x(ageMonths: Double) = plotLeft + plotWidth * (ageMonths / maxAge).toFloat()
        fun y(value: Double) =
            top + pad + (1f - ((value - min) / span).toFloat()) * (plotHeight - pad * 2)

        fun area(lower: (WhoPercentiles) -> Double, upper: (WhoPercentiles) -> Double, sex: WhoSex) =
            Path().apply {
                band.forEachIndexed { i, p ->
                    if (i == 0) moveTo(x(p.ageMonths), y(upper(p.of(sex)))) else lineTo(x(p.ageMonths), y(upper(p.of(sex))))
                }
                band.reversed().forEach { p -> lineTo(x(p.ageMonths), y(lower(p.of(sex)))) }
                close()
            }

        sexes.forEach { sex ->
            val hatched = sexes.size > 1 && sex == WhoSex.BOYS
            listOf(
                area({ it.p3 }, { it.p50 }, sex) to warm,
                area({ it.p50 }, { it.p97 }, sex) to cool,
            ).forEach { (path, colour) ->
                if (hatched) {
                    canvas.save()
                    canvas.clipPath(path)
                    val bounds = RectF()
                    path.computeBounds(bounds, true)
                    hatch(canvas, bounds, withAlpha(colour, 90))
                    canvas.restore()
                } else {
                    fillPaint.color = withAlpha(colour, 48)
                    canvas.drawPath(path, fillPaint)
                }
            }
            val median = Path().apply {
                band.forEachIndexed { i, p ->
                    val py = y(p.of(sex).p50)
                    if (i == 0) moveTo(x(p.ageMonths), py) else lineTo(x(p.ageMonths), py)
                }
            }
            val dash = strokePaint(withAlpha(muted, 160), 0.8f).apply {
                pathEffect = DashPathEffect(if (hatched) floatArrayOf(5f, 3f) else floatArrayOf(2.5f, 2.5f), 0f)
            }
            canvas.drawPath(median, dash)
        }

        // The axis, labelled with values the chart actually reaches.
        for (tick in 0..3) {
            val value = min + span * tick / 3.0
            val ty = y(value)
            canvas.drawLine(plotLeft, ty, left + width, ty, strokePaint(grid, 0.5f))
            canvas.drawText(axisLabel(value.toFloat()), plotLeft - 3f, ty + 2.4f, tinyRight)
        }
        val baseline = top + plotHeight
        canvas.drawLine(plotLeft, baseline, left + width, baseline, strokePaint(ink, 0.8f))
        val months = ceil(maxAge).toInt().coerceAtLeast(1)
        val stepMonths = if (months <= 6) 1 else months / 6 + 1
        var month = 0
        while (month <= months) {
            if (x(month.toDouble()) <= left + width) {
                canvas.drawText(
                    string(R.string.stats_age_months, month),
                    x(month.toDouble()),
                    baseline + 8f,
                    tinyCentre,
                )
            }
            month += stepMonths
        }

        val own = points.map { x(it.first) to y(it.second) }
        if (own.size >= 2) {
            val path = Path().apply {
                own.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(px, py) else lineTo(px, py) }
            }
            canvas.drawPath(path, strokePaint(ink, 1.3f))
        }
        fillPaint.color = ink
        own.forEach { (px, py) -> canvas.drawCircle(px, py, 2.2f, fillPaint) }
    }

    // --- tables ----------------------------------------------------------

    /**
     * A table as one block per row, so a long one flows onto as many pages as
     * it needs, with its header drawn again at the top of each.
     */
    private fun tableBlocks(
        headers: List<String>,
        rows: List<List<String>>,
        weights: List<Float>,
        alignRight: List<Boolean>,
        repeatOnBreak: Boolean,
    ): List<Block> {
        val total = weights.sum()
        val columns = weights.map { contentWidth * it / total }
        val rowHeight = 12f
        val headerHeight = 14f

        fun drawRow(canvas: Canvas, top: Float, cells: List<String>, header: Boolean) {
            var x = margin
            cells.forEachIndexed { index, text ->
                val width = columns.getOrElse(index) { 0f }
                val paintFor = when {
                    header -> eyebrowPaint
                    index == 0 -> bodyPaint
                    else -> rightPaint
                }
                val right = alignRight.getOrElse(index) { false }
                val trimmed = ellipsise(text, paintFor, width - 4f)
                if (right) {
                    canvas.drawText(trimmed, x + width - 4f, top + 8f, rightAligned(paintFor))
                } else {
                    canvas.drawText(trimmed, x, top + 8f, paintFor)
                }
                x += width
            }
            canvas.drawLine(
                margin,
                top + 11f,
                margin + contentWidth,
                top + 11f,
                strokePaint(if (header) rule else grid, if (header) 0.8f else 0.4f),
            )
        }

        val headings = headers.map { it.uppercase() }
        val headerOnly = Block(headerHeight) { canvas, top ->
            drawRow(canvas, top, headings, header = true)
        }
        // The heading and the first row are one block, so a break can never
        // leave column headings stranded at the foot of a page with nothing
        // under them.
        val first = rows.firstOrNull()
        val opening = Block(headerHeight + if (first == null) 0f else rowHeight) { canvas, top ->
            drawRow(canvas, top, headings, header = true)
            first?.let { drawRow(canvas, top + headerHeight, it, header = false) }
        }
        val body = rows.drop(1).map { cells ->
            Block(rowHeight, repeatHeader = if (repeatOnBreak) headerOnly else null) { canvas, top ->
                drawRow(canvas, top, cells, header = false)
            }
        }
        return listOf(opening) + body
    }

    private fun paragraph(text: String, paint: Paint, topGap: Float = 0f): Block {
        val lines = layout(text, paint, contentWidth)
        return Block(lines.height + topGap + 4f) { canvas, top ->
            drawLayout(canvas, lines, margin, top + topGap)
        }
    }

    private fun drawHeading(canvas: Canvas, top: Float, title: String, trailing: String?) {
        canvas.drawText(title, margin, top + 14f, headingPaint)
        trailing?.let { canvas.drawText(it, margin + contentWidth, top + 13f, rightAligned(eyebrowPaint)) }
        canvas.drawLine(margin, top + 19f, margin + contentWidth, top + 19f, strokePaint(ink, 0.7f))
    }

    // --- text and numbers -------------------------------------------------

    private fun layout(text: String, paint: Paint, width: Float): StaticLayout {
        val textPaint = TextPaint(paint)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(1.6f, 1f)
            .setIncludePad(false)
            .build()
    }

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun italic(source: Paint) = Paint(source).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }

    private fun rightAligned(source: Paint) = Paint(source).apply { textAlign = Paint.Align.RIGHT }

    private fun strokePaint(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = colour
    }

    private fun withAlpha(colour: Int, alpha: Int) = Color.argb(
        alpha,
        Color.red(colour),
        Color.green(colour),
        Color.blue(colour),
    )

    private fun ellipsise(text: String, paint: Paint, width: Float): String {
        if (width <= 0f || paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }

    private fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

    private fun hours(millis: Long): String {
        val minutes = millis / 60_000L
        return string(R.string.report_hours_minutes, minutes / 60, minutes % 60)
    }

    private fun grams(value: Int) =
        String.format(Locale.getDefault(), "%.2f", value / 1000.0) + " kg"

    private fun centimetres(millimetres: Int) =
        String.format(Locale.getDefault(), "%.1f", millimetres / 10.0) + " cm"

    private fun axisLabel(value: Float): String = when {
        value >= 100f -> value.roundToInt().toString()
        value == value.toInt().toFloat() -> value.toInt().toString()
        else -> String.format(Locale.getDefault(), "%.1f", value)
    }

    private fun niceCeiling(value: Float): Float {
        if (value <= 0f) return 1f
        val steps = listOf(1f, 2f, 2.5f, 4f, 5f, 8f, 10f)
        var scale = 1f
        while (scale * 10f <= value) scale *= 10f
        val target = value / scale
        val step = steps.firstOrNull { it >= target } ?: 10f
        return step * scale
    }

    /** Day indices to label on a chart's axis: roughly six, evenly spread. */
    private fun tickIndices(count: Int): List<Int> {
        if (count <= 1) return listOf(0)
        val step = (count / 6f).toInt().coerceAtLeast(1)
        return (0 until count step step).toList()
    }

    private fun dayLabel(index: Int): String =
        formatDate(context, report.range.from.plusDays(index.toLong()).millis())

    private fun referenceLabel(reference: WhoSex?): String = string(
        when (reference) {
            null -> R.string.stats_reference_both
            WhoSex.GIRLS -> R.string.stats_reference_girls_in_sentence
            WhoSex.BOYS -> R.string.stats_reference_boys_in_sentence
        },
    )

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun LocalDate.millis(): Long =
        atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        const val FOOTER_HEIGHT = 18f
        const val DASH = "—"

        /** Above this many days the appendix switches from days to weeks. */
        const val MAX_DAILY_ROWS = 92
    }
}
