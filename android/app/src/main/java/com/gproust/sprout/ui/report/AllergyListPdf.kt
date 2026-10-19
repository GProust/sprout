package com.gproust.sprout.ui.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gproust.sprout.R
import com.gproust.sprout.ui.common.babyAge
import com.gproust.sprout.ui.common.formatDate
import java.io.OutputStream
import java.util.Locale

/**
 * The allergy list on its own (BDR-21): whose it is, and each allergy with what
 * the parents noted about it — the page a nursery pins up or a babysitter is
 * handed, without the rest of the record.
 *
 * Drawn the way [ReportPdf] draws, with `android.graphics` and no library
 * (ADR-0013), on the same paper and in the same ink. It says what was noted and
 * nothing more: no instructions, no symptoms to watch for, no "in case of
 * emergency". That is the doctor's to write, and a page that sounded like one
 * would be photocopied as one.
 */
class AllergyListPdf(
    private val context: Context,
    private val list: AllergyList,
) {
    private val paper = ReportPdf.Paper.forLocale(Locale.getDefault())
    private val margin = 42f
    private val contentWidth = paper.width - margin * 2

    private val ink = Color.parseColor("#151A17")
    private val muted = Color.parseColor("#6B756D")
    private val rule = Color.parseColor("#B9C1BB")

    private fun paint(size: Float, colour: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = colour
        typeface = face
    }

    private val titlePaint = paint(21f, ink, Typeface.create(Typeface.SERIF, Typeface.BOLD))
    private val namePaint = paint(12.5f, ink, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
    private val bodyPaint = paint(9.6f, ink, Typeface.SANS_SERIF)
    private val mutedPaint = paint(9f, muted, Typeface.SANS_SERIF)
    private val smallPaint = paint(7.4f, muted, Typeface.SANS_SERIF)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f
        color = rule
    }

    /** Writes the list to [out]; the caller owns the stream. */
    fun write(out: OutputStream) {
        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage(): Canvas {
            page?.let(document::finishPage)
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(paper.width, paper.height, pageNumber).create()
            return document.startPage(info).also { page = it }.canvas.also { y = margin }
        }

        var canvas = newPage()
        fun ensure(height: Float) {
            if (y + height > paper.height - margin) canvas = newPage()
        }
        fun text(value: String, paint: TextPaint, gapAfter: Float = 4f) {
            val layout = layout(value, paint)
            ensure(layout.height.toFloat())
            canvas.save()
            canvas.translate(margin, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + gapAfter
        }

        text(context.getString(R.string.allergies_title), titlePaint, gapAfter = 6f)
        text(list.babyName, namePaint, gapAfter = 2f)
        text(
            context.getString(
                R.string.allergy_list_identity,
                formatDate(context, list.birthDate),
                babyAge(context, list.birthDate, list.generatedAt),
                formatDate(context, list.generatedAt),
            ),
            mutedPaint,
            gapAfter = 14f,
        )
        canvas.drawLine(margin, y, margin + contentWidth, y, linePaint)
        y += 12f

        if (list.allergies.isEmpty()) {
            text(context.getString(R.string.allergies_none), bodyPaint, gapAfter = 12f)
        }
        list.allergies.forEach { allergy ->
            text(allergy.name, namePaint, gapAfter = 2f)
            val status = listOfNotNull(
                context.getString(if (allergy.confirmed) R.string.allergy_confirmed_short else R.string.allergy_noted_short),
                allergy.noticedAt?.let { context.getString(R.string.allergy_noticed_line, formatDate(context, it)) },
            ).joinToString(" ${context.getString(R.string.feeding_detail_separator).trim()} ")
            text(status, mutedPaint, gapAfter = if (allergy.notes == null) 12f else 3f)
            allergy.notes?.let { text(it, bodyPaint, gapAfter = 12f) }
        }

        canvas.drawLine(margin, y, margin + contentWidth, y, linePaint)
        y += 8f
        text(context.getString(R.string.allergy_list_note), smallPaint, gapAfter = 0f)

        page?.let(document::finishPage)
        document.writeTo(out)
        document.close()
    }

    private fun layout(text: String, paint: TextPaint): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, contentWidth.toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(1.6f, 1f)
        .setIncludePad(false)
        .build()
}
