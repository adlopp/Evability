package com.evability.notes

import android.os.ext.SdkExtensions
import android.os.Build
import android.graphics.pdf.models.selection.SelectionBoundary
import android.graphics.Point
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Texto seleccionado en una página; `rects` en coordenadas normalizadas respecto al ancho de la página. */
class TextSelection(val page: Int, val text: String, val rects: List<RectF>)

/** La selección de texto de PdfRenderer existe desde Android 15 (o con la extensión 13 del sistema en Android 12+). */
fun textSelectionSupported(): Boolean =
    Build.VERSION.SDK_INT >= 35 || (Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

/** Envoltorio de PdfRenderer. El renderer solo admite una página abierta a la vez, de ahí el candado. */
class PdfDoc(private val file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Any()

    @Volatile
    var closed = false
        private set

    val pageCount: Int = renderer.pageCount

    fun pageSize(i: Int): Pair<Int, Int> = synchronized(lock) {
        check(!closed) { "closed" }
        renderer.openPage(i).use { it.width to it.height }
    }

    fun render(i: Int, widthPx: Int): Bitmap = synchronized(lock) {
        check(!closed) { "closed" }
        renderer.openPage(i).use { p ->
            val h = (widthPx.toFloat() * p.height / p.width).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    // La selección de texto usa su PROPIO lector (con su propio candado): así no espera a que acabe de dibujarse una página,
    // y mientras se arrastra el dedo se reutiliza la página abierta en vez de analizarla en cada movimiento.
    private val selLock = Any()
    private var selPfd: ParcelFileDescriptor? = null
    private var selRenderer: PdfRenderer? = null
    private var selPage: PdfRenderer.Page? = null
    private var selIndex = -1

    /**
     * Selecciona el texto entre dos puntos (coordenadas normalizadas respecto al ancho de la página). Con el mismo punto
     * en los dos extremos selecciona la palabra. Devuelve null si no hay texto ahí o el sistema no lo soporta.
     */
    fun selectText(i: Int, ax: Float, ay: Float, bx: Float, by: Float): TextSelection? = synchronized(selLock) {
        if (closed || !textSelectionSupported()) return@synchronized null
        val renderer = selRenderer ?: run {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            selPfd = fd
            PdfRenderer(fd).also { selRenderer = it }
        }
        if (selIndex != i) {
            selPage?.close()
            selPage = null
            selPage = renderer.openPage(i)
            selIndex = i
        }
        val p = selPage!!
        val w = p.width.toFloat()
        fun at(x: Float, y: Float) = SelectionBoundary(Point((x * w).roundToInt(), (y * w).roundToInt()))
        val sel = p.selectContent(at(ax, ay), at(bx, by)) ?: p.selectContent(at(bx, by), at(ax, ay)) ?: return@synchronized null
        val contents = sel.selectedTextContents
        val text = contents.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) return@synchronized null
        TextSelection(i, text, contents.flatMap { it.bounds }.map { RectF(it.left / w, it.top / w, it.right / w, it.bottom / w) })
    }

    /** Coincidencias de `query` en una página; cada una con sus rectángulos (normalizados respecto al ancho de la página). */
    fun search(i: Int, query: String): List<List<RectF>> = synchronized(selLock) {
        if (closed || !textSelectionSupported()) return@synchronized emptyList()
        val renderer = selRenderer ?: run {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            selPfd = fd
            PdfRenderer(fd).also { selRenderer = it }
        }
        if (selIndex != i) {
            selPage?.close()
            selPage = null
            selPage = renderer.openPage(i)
            selIndex = i
        }
        val p = selPage!!
        val w = p.width.toFloat()
        p.searchText(query).map { m -> m.bounds.map { RectF(it.left / w, it.top / w, it.right / w, it.bottom / w) } }
    }

    fun writeThumbnail(target: File) {
        val bmp = render(0, 420)
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.close()
            pfd.close()
        }
        synchronized(selLock) {
            selPage?.close()
            selRenderer?.close()
            selPfd?.close()
            selPage = null
            selRenderer = null
            selPfd = null
        }
    }
}

/** Genera un PDF nuevo con las páginas originales y las anotaciones integradas (las páginas quedan rasterizadas). */
object Exporter {
    fun export(doc: PdfDoc, extraPages: Int, anns: List<Ann>, out: OutputStream, extraKinds: List<String> = emptyList(), hidden: Collection<Int> = emptyList(), onProgress: (Int) -> Unit = {}) {
        val pdf = PdfDocument()
        try {
            var number = 0
            for (i in 0 until doc.pageCount + extraPages) {
                if (i in hidden) continue
                number++
                val isBlank = i >= doc.pageCount
                val (pw, ph) = doc.pageSize(if (isBlank) 0 else i)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, number).create())
                if (isBlank) {
                    page.canvas.drawColor(Color.WHITE)
                    TemplateKind.entries.firstOrNull { it.name == extraKinds.getOrNull(i - doc.pageCount) }?.let { kind ->
                        page.canvas.save()
                        page.canvas.scale(pw / Templates.W.toFloat(), pw / Templates.W.toFloat())
                        Templates.draw(page.canvas, kind)
                        page.canvas.restore()
                    }
                } else {
                    val bmp = doc.render(i, min(2400, pw * 3))
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    bmp.recycle()
                }
                AnnPainter.drawAll(page.canvas, anns.filter { it.page == i }, pw.toFloat(), bitmapScale = 3f)
                pdf.finishPage(page)
                onProgress(number)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
