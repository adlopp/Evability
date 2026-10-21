package com.evability.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import kotlin.math.roundToInt

enum class TemplateKind(val title: String, val subtitle: String, val docName: String) {
    MONTHLY("Mensual", "Calendario del mes con objetivo, prioridades y tareas", "Plan mensual"),
    WEEKLY("Semanal", "Agenda de la semana, prioridades, tareas y hábitos", "Plan semanal"),
    BLANK("En blanco", "Una hoja vacía para escribir libremente", "Documento en blanco"),
}

/** Plantillas dibujadas como páginas A4 (595 × 842 pt) para poder abrirlas como cualquier otro PDF. */
object Templates {
    const val W = 595
    const val H = 842

    private val BLUE = 0xFF4A5C9E.toInt()
    private val GRAY = 0xFFC4C7D0.toInt()
    private val INK = 0xFF1A1A1A.toInt()

    private const val LEFT = 36f
    private const val RIGHT = 560f
    private const val COL2 = 310f      // inicio de la columna derecha
    private const val LEFT_END = 286f  // fin de la columna izquierda

    fun draw(c: Canvas, kind: TemplateKind) {
        when (kind) {
            TemplateKind.MONTHLY -> monthly(c)
            TemplateKind.WEEKLY -> weekly(c)
            TemplateKind.BLANK -> Unit
        }
    }

    fun writePdf(kind: TemplateKind, file: File) {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
            page.canvas.drawColor(Color.WHITE)
            draw(page.canvas, kind)
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    fun preview(kind: TemplateKind, widthPx: Int): Bitmap {
        val h = (widthPx * H / W.toFloat()).roundToInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val s = widthPx / W.toFloat()
        c.scale(s, s)
        draw(c, kind)
        return bmp
    }

    // ───────── piezas comunes ─────────

    private fun line(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = Paint.Style.STROKE
    }

    private fun text(size: Float, bold: Boolean = true, serif: Boolean = false, spacing: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = size
            letterSpacing = spacing
            typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun hLine(c: Canvas, x1: Float, x2: Float, y: Float, strong: Boolean) =
        c.drawLine(x1, y, x2, y, if (strong) line(BLUE, 1f) else line(GRAY, 0.6f))

    private fun vLine(c: Canvas, x: Float, y1: Float, y2: Float) = c.drawLine(x, y1, x, y2, line(GRAY, 0.6f))

    private fun label(c: Canvas, s: String, x: Float, y: Float) = c.drawText(s, x, y, text(6.4f, spacing = 0.14f))

    private fun header(c: Canvas, title: String) {
        c.drawText(title, LEFT, 49.5f, text(21f, serif = true))
        c.drawText("Month:", 420.5f, 46f, text(6f, bold = false))
        hLine(c, 419f, RIGHT, 53.3f, strong = true)
    }

    /** Lista numerada (01, 02…) con separador de columna, como "Top priorities". */
    private fun numbered(c: Canvas, x1: Float, x2: Float, top: Float, rows: Int, sepX: Float) {
        val h = 24f
        val num = text(6f, spacing = 0.1f)
        for (k in 0 until rows) {
            c.drawText("%02d".format(k + 1), x1 + 8f, top + k * h + 16f, num)
            hLine(c, x1, x2, top + (k + 1) * h, strong = k == rows - 1)
        }
        vLine(c, sepX, top, top + rows * h)
    }

    /** Líneas de tareas con la columna estrecha de la derecha. */
    private fun taskLines(c: Canvas, x1: Float, x2: Float, top: Float, bottom: Float, rows: Int, divX: Float) {
        val h = (bottom - top) / rows
        for (k in 1..rows) hLine(c, x1, x2, top + k * h, strong = k == rows)
        vLine(c, divX, top, bottom)
    }

    // ───────── Monthly Planner ─────────

    private fun monthly(c: Canvas) {
        header(c, "Monthly Planner")

        val gridTop = 86f
        val gridBottom = 517f
        val colW = (RIGHT - LEFT) / 7f
        val rowH = (gridBottom - gridTop) / 6f
        val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        val dayPaint = text(6.4f, spacing = 0.14f).apply { textAlign = Paint.Align.CENTER }
        days.forEachIndexed { i, d -> c.drawText(d, LEFT + colW * (i + 0.5f), 80f, dayPaint) }
        for (i in 1..6) vLine(c, LEFT + i * colW, gridTop, gridBottom)
        for (j in 1..5) hLine(c, LEFT, RIGHT, gridTop + j * rowH, strong = false)
        c.drawRect(LEFT, gridTop, RIGHT, gridBottom, line(BLUE, 1f))

        label(c, "GOAL / INSPIRATION", LEFT, 539.5f)
        hLine(c, LEFT, LEFT_END, 549.5f, strong = true)
        hLine(c, LEFT, LEFT_END, 609.5f, strong = true)

        label(c, "TOP PRIORITIES", LEFT, 628.6f)
        hLine(c, LEFT, LEFT_END, 638.5f, strong = true)
        numbered(c, LEFT, LEFT_END, 638.5f, 4, sepX = 64f)

        label(c, "TASKS", COL2, 539.5f)
        hLine(c, COL2, RIGHT, 549.5f, strong = true)
        taskLines(c, COL2, RIGHT, 549.5f, 734.5f, 8, divX = 536f)
    }

    // ───────── Weekly Planner ─────────

    private fun weekly(c: Canvas) {
        header(c, "Weekly Planner")

        label(c, "SCHEDULE", LEFT, 78.4f)
        val top = 89.2f
        val bottom = 733.5f
        val rowH = (bottom - top) / 7f
        for (k in 1..6) hLine(c, LEFT, LEFT_END, top + k * rowH, strong = false)
        vLine(c, 74f, top, bottom)
        val dayPaint = text(6.4f).apply { textAlign = Paint.Align.CENTER }
        "MTWTFSS".forEachIndexed { k, ch ->
            val y = top + k * rowH
            hLine(c, 44f, 68f, y + 47.7f, strong = false)
            c.drawText(ch.toString(), 56f, y + 64f, dayPaint)
        }
        c.drawRect(LEFT, top, LEFT_END, bottom, line(BLUE, 1f))

        label(c, "GOAL / INSPIRATION", COL2, 78.4f)
        hLine(c, COL2, RIGHT, 89.2f, strong = true)
        hLine(c, COL2, RIGHT, 122.2f, strong = true)

        label(c, "TOP PRIORITIES", COL2, 143.8f)
        hLine(c, COL2, RIGHT, 153f, strong = true)
        numbered(c, COL2, RIGHT, 153f, 4, sepX = 336f)

        label(c, "OTHER TASKS", COL2, 275.2f)
        hLine(c, COL2, RIGHT, 284.5f, strong = true)
        taskLines(c, COL2, RIGHT, 284.5f, 498f, 9, divX = 536.7f)

        label(c, "HABITS", COL2, 521.3f)
        val xs = listOf(454.4f, 471.3f, 487.5f, 504.2f, 520.6f, 537.7f, 553.7f)
        val letter = text(6.4f).apply { textAlign = Paint.Align.CENTER }
        "MTWTFSS".forEachIndexed { i, ch -> c.drawText(ch.toString(), xs[i], 521.3f, letter) }
        hLine(c, COL2, RIGHT, 532.8f, strong = true)
        val box = line(GRAY, 0.6f)
        for (k in 0 until 5) {
            val rowTop = 532.8f + k * 23.8f
            val yc = rowTop + 11.9f
            xs.forEach { x -> c.drawRect(x - 4.5f, yc - 4.5f, x + 4.5f, yc + 4.5f, box) }
            hLine(c, COL2, RIGHT, rowTop + 23.8f, strong = k == 4)
        }
        hLine(c, COL2, RIGHT, 734f, strong = true)
    }
}
