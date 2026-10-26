package com.evability.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Convierte el resumen de Gemini (Markdown sencillo) en un PDF A4 para poder abrirlo y anotarlo como cualquier otro documento. */
object SummaryPdf {
    private const val W = 595
    private const val H = 842
    private const val MARGIN = 48f
    private val BLUE = 0xFF4A5C9E.toInt()
    private val INK = 0xFF1A1A1A.toInt()
    private val GRAY = 0xFF6E7280.toInt()

    private class Pager(val pdf: PdfDocument) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = MARGIN

        val contentWidth = (W - 2 * MARGIN).toInt()
        val bottom = H - MARGIN

        fun next() {
            page?.let { pdf.finishPage(it) }
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, number).create())
            canvas = page!!.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN
        }

        fun finish() {
            page?.let { pdf.finishPage(it) }
            page = null
        }

        /** Dibuja el bloque línea a línea, saltando de página cuando no cabe. */
        fun draw(layout: StaticLayout, gapBefore: Float, keepWithNext: Boolean = false) {
            if (page == null) next()
            if (y > MARGIN) y += gapBefore
            if (keepWithNext && bottom - y < 90f) next()
            var start = 0
            while (start < layout.lineCount) {
                val top = layout.getLineTop(start)
                val avail = bottom - y
                var end = start
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= avail) end++
                if (end == start) {
                    if (y > MARGIN) { next(); continue }
                    end = start + 1
                }
                val bottomPx = layout.getLineBottom(end - 1)
                canvas.save()
                canvas.translate(MARGIN, y - top)
                canvas.clipRect(0, top, contentWidth, bottomPx)
                layout.draw(canvas)
                canvas.restore()
                y += (bottomPx - top).toFloat()
                start = end
                if (start < layout.lineCount) next()
            }
        }
    }

    fun write(title: String, originalName: String, text: String, sources: List<Pair<String, String>>, file: File) {
        val pdf = PdfDocument()
        try {
            val pager = Pager(pdf)
            val date = SimpleDateFormat("d/M/yyyy", Locale("es", "ES")).format(Date())

            pager.draw(layout(span(title, 22f, bold = true, color = BLUE), pager.contentWidth), 0f)
            pager.draw(layout(span("Generado con IA el $date a partir de «$originalName»", 10f, color = GRAY), pager.contentWidth), 4f)

            text.lines().forEach { raw ->
                val line = raw.trimEnd()
                val t = line.trimStart()
                val indent = line.length - t.length
                when {
                    t.isBlank() -> Unit
                    t.startsWith("#") -> {
                        val level = t.takeWhile { it == '#' }.length
                        val size = when (level) { 1 -> 18f; 2 -> 15f; else -> 13f }
                        pager.draw(layout(span(t.trimStart('#').trim().replace("**", ""), size, bold = true, color = BLUE), pager.contentWidth), 14f, keepWithNext = true)
                    }
                    t.startsWith("- ") || t.startsWith("* ") -> {
                        val margin = 14 + (indent / 2).coerceAtMost(3) * 14
                        pager.draw(layout(inline("•  " + t.drop(2), 11.5f, hang = margin), pager.contentWidth), 3f)
                    }
                    else -> pager.draw(layout(inline(t, 11.5f), pager.contentWidth), 5f)
                }
            }

            if (sources.isNotEmpty()) {
                pager.draw(layout(span("Fuentes consultadas", 13f, bold = true, color = BLUE), pager.contentWidth), 18f, keepWithNext = true)
                sources.forEach { (name, url) ->
                    pager.draw(layout(span("•  $name\n$url", 9.5f, color = GRAY, hang = 14), pager.contentWidth), 4f)
                }
            }
            pager.finish()
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun paint(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = INK
        typeface = Typeface.SANS_SERIF
    }

    private fun layout(text: CharSequence, width: Int): StaticLayout {
        val size = (text as? Spanned)?.getSpans(0, text.length, AbsoluteSizeSpan::class.java)?.firstOrNull()?.size?.toFloat() ?: 11.5f
        return StaticLayout.Builder.obtain(text, 0, text.length, paint(size), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.18f)
            .build()
    }

    private fun span(text: String, size: Float, bold: Boolean = false, color: Int = INK, hang: Int = 0): SpannableStringBuilder =
        SpannableStringBuilder(text).apply {
            setSpan(AbsoluteSizeSpan(size.toInt(), false), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            if (hang > 0) setSpan(LeadingMarginSpan.Standard(0, hang), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

    /** Texto con **negritas**; `hang` es la sangría de las líneas siguientes (para las listas). */
    private fun inline(s: String, size: Float, hang: Int = 0): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        s.split("**").forEachIndexed { i, part ->
            val start = out.length
            out.append(part)
            if (i % 2 == 1 && part.isNotEmpty()) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        out.setSpan(AbsoluteSizeSpan(size.toInt(), false), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        out.setSpan(ForegroundColorSpan(INK), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (hang > 0) out.setSpan(LeadingMarginSpan.Standard(0, hang), 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return out
    }
}
