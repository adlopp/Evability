package com.evability.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.StaticLayout
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

/** Pinta anotaciones sobre un Canvas nativo. Se usa tanto en pantalla como al exportar el PDF. */
object AnnPainter {

    fun drawAll(c: Canvas, list: List<Ann>, w: Float, bitmapScale: Float = 1f) {
        list.filterIsInstance<Cover>().forEach { drawCover(c, it, w) }
        val strokes = list.filterIsInstance<Stroke>()
        strokes.filter { it.highlighter }.forEach { drawStroke(c, it.points, it.color, it.width, true, w) }
        list.filterIsInstance<Sticker>().forEach { drawSticker(c, it, w, bitmapScale) }
        list.filterIsInstance<Postit>().forEach { drawPostit(c, it, w) }
        strokes.filter { !it.highlighter }.forEach { drawStroke(c, it.points, it.color, it.width, false, w) }
        list.filterIsInstance<TextNote>().forEach { drawText(c, it, w) }
    }

    fun drawStroke(c: Canvas, pts: List<Offset>, color: Int, width: Float, highlighter: Boolean, w: Float) {
        if (pts.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = if (highlighter) (color and 0x00FFFFFF) or (0x61 shl 24) else color
        if (pts.size == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(pts[0].x * w, pts[0].y * w, max(width * w / 2f, 0.5f), paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width * w
        paint.strokeCap = if (highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val path = Path()
        path.moveTo(pts[0].x * w, pts[0].y * w)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f
            val my = (pts[i].y + pts[i + 1].y) / 2f
            path.quadTo(pts[i].x * w, pts[i].y * w, mx * w, my * w)
        }
        path.lineTo(pts.last().x * w, pts.last().y * w)
        c.drawPath(path, paint)
    }

    fun drawSticker(c: Canvas, s: Sticker, w: Float, bitmapScale: Float) {
        val side = s.size * w
        val bmp = StickerBitmaps.get(s.src, (side * bitmapScale).roundToInt()) ?: return
        c.save()
        c.translate(s.x * w, s.y * w)
        c.rotate(s.rot)
        c.drawBitmap(bmp, null, RectF(-side / 2f, -side / 2f, side / 2f, side / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    /** `p` en coordenadas normalizadas. */
    fun hitSticker(s: Sticker, p: Offset): Boolean {
        val r = Math.toRadians(s.rot.toDouble())
        val dx = p.x - s.x
        val dy = p.y - s.y
        val lx = dx * cos(r) + dy * sin(r)
        val ly = -dx * sin(r) + dy * cos(r)
        val h = s.size / 2f + 0.01f
        return abs(lx) <= h && abs(ly) <= h
    }

    fun drawCover(c: Canvas, cv: Cover, w: Float) {
        c.drawRect(cv.x * w, cv.y * w, (cv.x + cv.w) * w, (cv.y + cv.h) * w, Paint().apply { color = cv.color })
    }

    /** `p` en coordenadas normalizadas. */
    fun hitCover(cv: Cover, p: Offset) = p.x >= cv.x && p.x <= cv.x + cv.w && p.y >= cv.y && p.y <= cv.y + cv.h

    /** Color de fondo alrededor del recuadro (mediana de 8 muestras justo fuera de él), para que el tapado pase desapercibido. */
    fun sampleBackground(bmp: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        val s = bmp.width.toFloat()
        val m = 5f
        val xs = listOf(x0 * s - m, (x0 + x1) / 2f * s, x1 * s + m)
        val ys = listOf(y0 * s - m, (y0 + y1) / 2f * s, y1 * s + m)
        val colors = ArrayList<Int>()
        for (xi in xs.indices) for (yi in ys.indices) {
            if (xi == 1 && yi == 1) continue
            colors.add(bmp.getPixel(xs[xi].roundToInt().coerceIn(0, bmp.width - 1), ys[yi].roundToInt().coerceIn(0, bmp.height - 1)))
        }
        fun lum(c: Int) = 0.299f * android.graphics.Color.red(c) + 0.587f * android.graphics.Color.green(c) + 0.114f * android.graphics.Color.blue(c)
        return colors.sortedBy { lum(it) }[colors.size / 2] or (0xFF shl 24)
    }

    private val POST_FILL = 0xFFFFCB9A.toInt() // naranja pastel
    private val POST_BAND = 0xFFFFDDBB.toInt()

    fun drawPostit(c: Canvas, p: Postit, w: Float) {
        val side = p.size * w
        val corner = side * 0.03f
        c.save()
        c.translate(p.x * w, p.y * w)
        c.rotate(p.rot)
        val r = RectF(-side / 2f, -side / 2f, side / 2f, side / 2f)
        c.drawRoundRect(
            RectF(r.left + side * 0.012f, r.top + side * 0.022f, r.right + side * 0.012f, r.bottom + side * 0.022f),
            corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000 },
        )
        c.drawRoundRect(r, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POST_FILL })
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + side * 0.11f)
        c.drawRoundRect(r, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = POST_BAND })
        c.restore()
        if (p.strokes.isNotEmpty()) {
            c.save()
            c.clipRect(r)
            p.strokes.forEach { drawStroke(c, it.points, it.color, it.width, it.highlighter, side) }
            c.restore()
        }
        c.restore()
    }

    /** Pasa un punto de la página (coordenadas normalizadas) al sistema local del post-it. */
    fun toLocal(s: Postit, p: Offset): Offset {
        val r = Math.toRadians(s.rot.toDouble())
        val dx = p.x - s.x
        val dy = p.y - s.y
        return Offset(((dx * cos(r) + dy * sin(r)) / s.size).toFloat(), ((-dx * sin(r) + dy * cos(r)) / s.size).toFloat())
    }

    /** `p` en coordenadas normalizadas. */
    fun hitPostit(s: Postit, p: Offset): Boolean {
        val r = Math.toRadians(s.rot.toDouble())
        val dx = p.x - s.x
        val dy = p.y - s.y
        val lx = dx * cos(r) + dy * sin(r)
        val ly = -dx * sin(r) + dy * cos(r)
        val h = s.size / 2f + 0.005f
        return abs(lx) <= h && abs(ly) <= h
    }

    private fun textPaint(t: TextNote, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = t.color
        textSize = t.size * w
    }

    fun drawText(c: Canvas, t: TextNote, w: Float) {
        val p = textPaint(t, w)
        var y = t.y * w - p.ascent()
        for (line in t.text.split("\n")) {
            c.drawText(line, t.x * w, y, p)
            y += p.fontSpacing
        }
    }

    fun textRect(t: TextNote, w: Float): RectF {
        val p = textPaint(t, w)
        val lines = t.text.split("\n")
        val width = lines.maxOf { p.measureText(it) }
        return RectF(t.x * w, t.y * w, t.x * w + width, t.y * w + p.fontSpacing * lines.size)
    }

    /** `p` en coordenadas normalizadas. */
    fun hitText(t: TextNote, p: Offset): Boolean {
        val r = textRect(t, 1000f)
        r.inset(-12f, -12f)
        return r.contains(p.x * 1000f, p.y * 1000f)
    }

    fun distToStroke(s: Stroke, p: Offset): Float = distToPoints(s.points, p)

    fun distToPoints(pts: List<Offset>, p: Offset): Float {
        if (pts.size == 1) return hypot(p.x - pts[0].x, p.y - pts[0].y)
        var best = Float.MAX_VALUE
        for (i in 0 until pts.size - 1) best = min(best, distToSegment(p, pts[i], pts[i + 1]))
        return best
    }

    private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
