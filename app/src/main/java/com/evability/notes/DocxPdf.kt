package com.evability.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte un .docx en PDF sin librerías externas: un .docx es un zip con XML, así que se leen los párrafos, títulos,
 * negritas/cursivas/subrayado, tamaños, colores, listas, tablas e imágenes y se dibujan en páginas.
 * Es una conversión aproximada: no reproduce encabezados, notas al pie, cuadros de texto ni la maquetación exacta de Word.
 */
object DocxPdf {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    // ───────── modelo ─────────

    /** Propiedades de texto; null = heredar del estilo. */
    private class Props {
        var bold: Boolean? = null
        var italic: Boolean? = null
        var underline: Boolean? = null
        var size: Float? = null
        var color: Int? = null
        var family: String? = null

        fun copy() = Props().also { it.applyFrom(this) }

        fun applyFrom(o: Props) {
            o.bold?.let { bold = it }
            o.italic?.let { italic = it }
            o.underline?.let { underline = it }
            o.size?.let { size = it }
            o.color?.let { color = it }
            o.family?.let { family = it }
        }
    }

    private class Style(val id: String) {
        var name = ""
        var basedOn: String? = null
        val run = Props()
        var jc: String? = null
        var indent: Float? = null
    }

    private class Styles {
        val byId = HashMap<String, Style>()
        val defaults = Props()
        var defaultPara: String? = null

        fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>()
            var cur = id?.let { byId[it] }
            var n = 0
            while (cur != null && n++ < 12) {
                out.add(0, cur)
                cur = cur.basedOn?.let { byId[it] }
            }
            return out
        }

        fun headingLevel(id: String?): Int {
            for (s in chain(id).asReversed()) {
                val n = s.name.lowercase()
                if (n.startsWith("heading ")) return n.removePrefix("heading ").trim().toIntOrNull() ?: 3
                if (n == "title") return 0
                if (n == "subtitle") return 3
            }
            return -1
        }
    }

    private class NumLevel(val fmt: String, val start: Int)

    private class Numbering {
        val abstracts = HashMap<Int, HashMap<Int, NumLevel>>()
        val nums = HashMap<Int, Int>()
    }

    private sealed interface Item {
        class Text(val text: String, val props: Props, val rStyle: String?) : Item
        class Image(val rId: String, val wPt: Float, val hPt: Float) : Item
        data object PageBreak : Item
    }

    private sealed interface Block

    private class Para : Block {
        var style: String? = null
        var jc: String? = null
        var indent: Float? = null
        var numId = 0
        var ilvl = 0
        var pageBreakBefore = false
        val items = ArrayList<Item>()
    }

    private class Cell(val paras: List<Para>, val span: Int, val fill: Int?)
    private class LaidCell(val cell: Cell, val x: Float, val w: Float, val layout: StaticLayout)
    private class Table(val widths: List<Float>, val rows: List<List<Cell>>) : Block

    private class Body {
        val blocks = ArrayList<Block>()
        var pageW = 595f
        var pageH = 842f
        var ml = 72f
        var mr = 72f
        var mt = 72f
        var mb = 72f
    }

    // ───────── XML ─────────

    /** Sin procesar espacios de nombres: así las etiquetas llegan con su prefijo ("w:p") tal como están en el archivo. */
    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    private fun XmlPullParser.a(name: String): String? = getAttributeValue(null, name)

    private fun XmlPullParser.on(): Boolean = a("w:val").let { it == null || (it != "0" && it != "false" && it != "off") }

    private fun twips(s: String?): Float? = s?.toFloatOrNull()?.div(20f)

    private fun hexColor(s: String?): Int? =
        if (s == null || s == "auto" || s.length != 6) null else s.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }

    /** Salta el elemento actual entero (para ignorar copias alternativas y cuadros de texto). */
    private fun XmlPullParser.skipElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readRunProp(p: XmlPullParser, target: Props) {
        when (p.name) {
            "w:b" -> target.bold = p.on()
            "w:i" -> target.italic = p.on()
            "w:u" -> target.underline = p.a("w:val").let { it != null && it != "none" }
            "w:sz" -> p.a("w:val")?.toFloatOrNull()?.let { target.size = it / 2f }
            "w:color" -> hexColor(p.a("w:val"))?.let { target.color = it }
            "w:rFonts" -> (p.a("w:ascii") ?: p.a("w:hAnsi"))?.let { target.family = it }
        }
    }

    private fun parseStyles(xml: ByteArray): Styles {
        val styles = Styles()
        val p = parser(xml)
        var cur: Style? = null
        var inDefaults = false
        var inPPr = false
        var inRPr = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:docDefaults" -> inDefaults = true
                    "w:style" -> {
                        val id = p.a("w:styleId")
                        if (id != null) {
                            cur = Style(id).also { styles.byId[id] = it }
                            if (p.a("w:default") == "1" && p.a("w:type") == "paragraph") styles.defaultPara = id
                        }
                    }
                    "w:name" -> cur?.name = p.a("w:val") ?: ""
                    "w:basedOn" -> cur?.basedOn = p.a("w:val")
                    "w:pPr" -> inPPr = true
                    "w:rPr" -> inRPr = true
                    "w:jc" -> if (inPPr) cur?.jc = p.a("w:val")
                    "w:ind" -> if (inPPr) cur?.indent = twips(p.a("w:left") ?: p.a("w:start"))
                    else -> if (inRPr) (cur?.run ?: if (inDefaults) styles.defaults else null)?.let { readRunProp(p, it) }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:docDefaults" -> inDefaults = false
                    "w:style" -> cur = null
                    "w:pPr" -> inPPr = false
                    "w:rPr" -> inRPr = false
                }
                XmlPullParser.END_DOCUMENT -> return styles
            }
        }
    }

    private fun parseNumbering(xml: ByteArray): Numbering {
        val num = Numbering()
        val p = parser(xml)
        var absId = -1
        var ilvl = -1
        var numId = -1
        var fmt = "decimal"
        var start = 1
        var inAbstract = false
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:abstractNum" -> { inAbstract = true; absId = p.a("w:abstractNumId")?.toIntOrNull() ?: -1 }
                    "w:lvl" -> { ilvl = p.a("w:ilvl")?.toIntOrNull() ?: 0; fmt = "decimal"; start = 1 }
                    "w:start" -> if (inAbstract) start = p.a("w:val")?.toIntOrNull() ?: 1
                    "w:numFmt" -> if (inAbstract) fmt = p.a("w:val") ?: "decimal"
                    "w:num" -> numId = p.a("w:numId")?.toIntOrNull() ?: -1
                    "w:abstractNumId" -> if (!inAbstract) p.a("w:val")?.toIntOrNull()?.let { num.nums[numId] = it }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:lvl" -> num.abstracts.getOrPut(absId) { HashMap() }[ilvl] = NumLevel(fmt, start)
                    "w:abstractNum" -> inAbstract = false
                }
                XmlPullParser.END_DOCUMENT -> return num
            }
        }
    }

    private fun parseRels(xml: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val p = parser(xml)
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> if (p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id")
                    val target = p.getAttributeValue(null, "Target")
                    if (id != null && target != null) out[id] = target
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    private class TableBuilder {
        val widths = ArrayList<Float>()
        val rows = ArrayList<List<Cell>>()
        var row = ArrayList<Cell>()
        var cellParas = ArrayList<Para>()
        var cellSpan = 1
        var cellFill: Int? = null
        var inCell = false
    }

    private fun parseBody(xml: ByteArray): Body {
        val body = Body()
        val p = parser(xml)
        var para: Para? = null
        var run = Props()
        var runStyle: String? = null
        var inRun = false
        var inPPr = false
        var inRPr = false
        var inTcPr = false
        var extentW = 0f
        var extentH = 0f
        var tblDepth = 0
        var tb: TableBuilder? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "mc:Fallback", "w:txbxContent" -> p.skipElement()
                    "w:p" -> para = Para()
                    "w:pPr" -> inPPr = true
                    "w:rPr" -> inRPr = true
                    "w:pStyle" -> para?.style = p.a("w:val")
                    "w:jc" -> if (inPPr) para?.jc = p.a("w:val")
                    "w:ind" -> if (inPPr) para?.indent = twips(p.a("w:left") ?: p.a("w:start"))
                    "w:ilvl" -> if (inPPr) para?.ilvl = p.a("w:val")?.toIntOrNull() ?: 0
                    "w:numId" -> if (inPPr) para?.numId = p.a("w:val")?.toIntOrNull() ?: 0
                    "w:pageBreakBefore" -> if (inPPr) para?.pageBreakBefore = true
                    "w:r" -> { run = Props(); runStyle = null; inRun = true }
                    "w:rStyle" -> if (inRPr && !inPPr) runStyle = p.a("w:val")
                    "w:b", "w:i", "w:u", "w:sz", "w:color", "w:rFonts" -> if (inRPr && !inPPr) readRunProp(p, run)
                    "w:t" -> {
                        val text = p.nextText()
                        if (text.isNotEmpty()) para?.items?.add(Item.Text(text, run.copy(), runStyle))
                    }
                    "w:tab" -> if (inRun) para?.items?.add(Item.Text("    ", run.copy(), runStyle))
                    "w:cr" -> para?.items?.add(Item.Text("\n", run.copy(), runStyle))
                    "w:br" -> if (p.a("w:type") == "page") para?.items?.add(Item.PageBreak)
                    else para?.items?.add(Item.Text("\n", run.copy(), runStyle))
                    "w:drawing" -> { extentW = 0f; extentH = 0f }
                    "wp:extent" -> {
                        extentW = (p.a("cx")?.toFloatOrNull() ?: 0f) / 12700f
                        extentH = (p.a("cy")?.toFloatOrNull() ?: 0f) / 12700f
                    }
                    "a:blip" -> p.a("r:embed")?.let { para?.items?.add(Item.Image(it, extentW, extentH)) }
                    "v:imagedata" -> p.a("r:id")?.let { para?.items?.add(Item.Image(it, 0f, 0f)) }
                    "w:tbl" -> { tblDepth++; if (tblDepth == 1) tb = TableBuilder() }
                    "w:gridCol" -> if (tblDepth == 1) tb?.widths?.add((p.a("w:w")?.toFloatOrNull() ?: 0f) / 20f)
                    "w:tr" -> if (tblDepth == 1) tb?.row = ArrayList()
                    "w:tc" -> if (tblDepth == 1) tb?.let { it.cellParas = ArrayList(); it.cellSpan = 1; it.cellFill = null; it.inCell = true }
                    "w:tcPr" -> inTcPr = true
                    "w:gridSpan" -> if (inTcPr && tblDepth == 1) tb?.cellSpan = p.a("w:val")?.toIntOrNull() ?: 1
                    "w:shd" -> if (inTcPr && tblDepth == 1) hexColor(p.a("w:fill"))?.let { tb?.cellFill = it }
                    "w:pgSz" -> {
                        twips(p.a("w:w"))?.let { body.pageW = it }
                        twips(p.a("w:h"))?.let { body.pageH = it }
                    }
                    "w:pgMar" -> {
                        twips(p.a("w:left"))?.let { body.ml = it }
                        twips(p.a("w:right"))?.let { body.mr = it }
                        twips(p.a("w:top"))?.let { body.mt = it }
                        twips(p.a("w:bottom"))?.let { body.mb = it }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:pPr" -> inPPr = false
                    "w:rPr" -> inRPr = false
                    "w:tcPr" -> inTcPr = false
                    "w:r" -> inRun = false
                    "w:p" -> para?.let {
                        val t = tb
                        if (tblDepth >= 1 && t != null && t.inCell) t.cellParas.add(it) else body.blocks.add(it)
                        para = null
                    }
                    "w:tc" -> if (tblDepth == 1) tb?.let { it.row.add(Cell(it.cellParas, it.cellSpan, it.cellFill)); it.inCell = false }
                    "w:tr" -> if (tblDepth == 1) tb?.let { it.rows.add(it.row) }
                    "w:tbl" -> {
                        if (tblDepth == 1) tb?.let { body.blocks.add(Table(it.widths, it.rows)); tb = null }
                        tblDepth = max(0, tblDepth - 1)
                    }
                }
                XmlPullParser.END_DOCUMENT -> return body
            }
        }
    }

    // ───────── páginas ─────────

    private class Pager(val pdf: PdfDocument, val pageW: Int, val pageH: Int, val ml: Float, val mr: Float, val mt: Float, val mb: Float) {
        private var number = 0
        private var page: PdfDocument.Page? = null
        lateinit var canvas: Canvas
        var y = mt

        val contentWidth = (pageW - ml - mr).toInt()
        val bottom = pageH - mb

        fun next() {
            page?.let { pdf.finishPage(it) }
            number++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, number).create())
            canvas = page!!.canvas
            canvas.drawColor(Color.WHITE)
            y = mt
        }

        fun finish() {
            if (page == null) next()
            page?.let { pdf.finishPage(it) }
            page = null
        }

        fun ensure() {
            if (page == null) next()
        }

        /** Dibuja el bloque línea a línea, saltando de página cuando no cabe. */
        fun draw(layout: StaticLayout, gapBefore: Float) {
            ensure()
            if (y > mt) y += gapBefore
            var start = 0
            while (start < layout.lineCount) {
                val top = layout.getLineTop(start)
                val avail = bottom - y
                var end = start
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= avail) end++
                if (end == start) {
                    if (y > mt) { next(); continue }
                    end = start + 1
                }
                val bottomPx = layout.getLineBottom(end - 1)
                canvas.save()
                canvas.translate(ml, y - top)
                canvas.clipRect(0, top, contentWidth, bottomPx)
                layout.draw(canvas)
                canvas.restore()
                y += (bottomPx - top).toFloat()
                start = end
                if (start < layout.lineCount) next()
            }
        }

        fun drawImage(bmp: Bitmap, wPt: Float, hPt: Float, gapBefore: Float) {
            ensure()
            var w = wPt
            var h = hPt
            val maxH = bottom - mt
            if (h > maxH) { w *= maxH / h; h = maxH }
            if (y > mt) y += gapBefore
            if (y + h > bottom) next()
            canvas.drawBitmap(bmp, null, RectF(ml, y, ml + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            y += h
        }
    }

    private class Ctx(val styles: Styles, val numbering: Numbering, val zip: ZipFile, val rels: Map<String, String>) {
        val counters = HashMap<Pair<Int, Int>, Int>()
    }

    private val BULLETS = listOf("•", "◦", "▪")

    private fun letter(n: Int): String {
        var v = n
        val sb = StringBuilder()
        while (v > 0) { v--; sb.insert(0, 'A' + v % 26); v /= 26 }
        return sb.toString()
    }

    private fun roman(n: Int): String {
        val map = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var v = n
        val sb = StringBuilder()
        for ((value, sym) in map) while (v >= value) { sb.append(sym); v -= value }
        return sb.toString()
    }

    private fun listPrefix(ctx: Ctx, p: Para): String? {
        if (p.numId <= 0) return null
        val lvl = ctx.numbering.nums[p.numId]?.let { ctx.numbering.abstracts[it]?.get(p.ilvl) }
        val fmt = lvl?.fmt ?: "bullet"
        if (fmt == "none") return null
        if (fmt == "bullet") return BULLETS[p.ilvl % BULLETS.size]
        val key = p.numId to p.ilvl
        val n = (ctx.counters[key] ?: ((lvl?.start ?: 1) - 1)) + 1
        ctx.counters[key] = n
        ctx.counters.keys.filter { it.first == p.numId && it.second > p.ilvl }.forEach { ctx.counters.remove(it) }
        return when (fmt) {
            "lowerLetter" -> letter(n).lowercase() + ")"
            "upperLetter" -> letter(n) + "."
            "lowerRoman" -> roman(n).lowercase() + "."
            "upperRoman" -> roman(n) + "."
            else -> "$n."
        }
    }

    private fun headingDefaults(level: Int, props: Props) {
        if (level < 0) return
        if (props.size == null) props.size = when (level) { 0 -> 26f; 1 -> 20f; 2 -> 16f; 3 -> 14f; else -> 12f }
        if (props.bold == null) props.bold = true
    }

    /** Propiedades base del párrafo: valores por defecto del documento + su estilo (+ valores de título). */
    private fun paraProps(ctx: Ctx, p: Para): Props {
        val base = ctx.styles.defaults.copy()
        val id = p.style ?: ctx.styles.defaultPara
        ctx.styles.chain(id).forEach { base.applyFrom(it.run) }
        headingDefaults(ctx.styles.headingLevel(id), base)
        return base
    }

    private fun typefaceFor(family: String?): String? {
        val f = family?.lowercase() ?: return null
        return when {
            listOf("times", "cambria", "georgia", "garamond", "book", "palatino", "serif").any { f.contains(it) } && !f.contains("sans") -> "serif"
            listOf("courier", "consolas", "mono").any { f.contains(it) } -> "monospace"
            else -> null
        }
    }

    private fun spanOf(ctx: Ctx, p: Para, texts: List<Item.Text>, prefix: String?): SpannableStringBuilder {
        val base = paraProps(ctx, p)
        val styleId = p.style ?: ctx.styles.defaultPara
        val chain = ctx.styles.chain(styleId)
        val indent = p.indent ?: chain.lastOrNull { it.indent != null }?.indent ?: if (p.numId > 0) 18f * (p.ilvl + 1) else 0f
        val sb = SpannableStringBuilder()

        if (prefix != null) {
            sb.append(prefix).append(' ')
            sb.setSpan(AbsoluteSizeSpan(Math.round(base.size ?: 11f)), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (t in texts) {
            val eff = base.copy()
            ctx.styles.chain(t.rStyle).forEach { eff.applyFrom(it.run) }
            eff.applyFrom(t.props)
            val start = sb.length
            sb.append(t.text)
            val end = sb.length
            if (end == start) continue
            sb.setSpan(AbsoluteSizeSpan(Math.round(eff.size ?: 11f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = when {
                eff.bold == true && eff.italic == true -> Typeface.BOLD_ITALIC
                eff.bold == true -> Typeface.BOLD
                eff.italic == true -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (eff.underline == true) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(eff.color ?: Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            typefaceFor(eff.family)?.let { sb.setSpan(TypefaceSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (sb.isEmpty()) {
            sb.append(' ')
            sb.setSpan(AbsoluteSizeSpan(Math.round(base.size ?: 11f)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hang = if (prefix != null) 16 else 0
        if (indent > 0f || hang > 0) {
            sb.setSpan(LeadingMarginSpan.Standard(Math.round(indent), Math.round(indent) + hang), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun alignOf(ctx: Ctx, p: Para): String? =
        p.jc ?: ctx.styles.chain(p.style ?: ctx.styles.defaultPara).lastOrNull { it.jc != null }?.jc

    private fun layoutOf(text: CharSequence, width: Int, jc: String?): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.BLACK; typeface = Typeface.SANS_SERIF }
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 20))
            .setLineSpacing(0f, 1.12f)
            .setAlignment(
                when (jc) {
                    "center" -> Layout.Alignment.ALIGN_CENTER
                    "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
        if (jc == "both" || jc == "distribute") b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    private fun image(ctx: Ctx, rId: String): Bitmap? {
        val target = ctx.rels[rId] ?: return null
        val name = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
        val entry = ctx.zip.getEntry(name) ?: return null
        val bytes = ctx.zip.getInputStream(entry).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null // formatos como EMF/WMF no se pueden dibujar
        var sample = 1
        while (bounds.outWidth / sample > 2000) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun drawPara(ctx: Ctx, pager: Pager, p: Para) {
        if (p.pageBreakBefore) pager.next()
        val level = ctx.styles.headingLevel(p.style ?: ctx.styles.defaultPara)
        val gapBefore = if (level in 0..3) 10f else 4f
        val prefix = listPrefix(ctx, p)
        val jc = alignOf(ctx, p)
        var group = ArrayList<Item.Text>()
        var first = true
        var drew = false

        fun flush(force: Boolean) {
            if (group.isEmpty() && !force) return
            val text = spanOf(ctx, p, group, if (first) prefix else null)
            first = false
            drew = true
            pager.draw(layoutOf(text, pager.contentWidth, jc), gapBefore)
            group = ArrayList()
        }

        for (item in p.items) {
            when (item) {
                is Item.Text -> group.add(item)
                is Item.PageBreak -> { flush(false); pager.next(); drew = true }
                is Item.Image -> {
                    flush(false)
                    val bmp = image(ctx, item.rId) ?: continue
                    val w = min(if (item.wPt > 0f) item.wPt else bmp.width.toFloat(), pager.contentWidth.toFloat())
                    pager.drawImage(bmp, w, w * bmp.height / bmp.width, 4f)
                    bmp.recycle()
                    drew = true
                }
            }
        }
        flush(force = !drew)
    }

    private fun drawTable(ctx: Ctx, pager: Pager, t: Table) {
        pager.ensure()
        val cols = max(t.widths.size, t.rows.maxOfOrNull { r -> r.sumOf { it.span } } ?: 1)
        val raw = List(cols) { t.widths.getOrNull(it)?.takeIf { w -> w > 0f } ?: 1f }
        val scale = pager.contentWidth / raw.sum()
        val widths = raw.map { it * scale }
        val pad = 4f
        val border = Paint().apply { color = 0xFF9A9CA6.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        pager.y += 4f

        for (row in t.rows) {
            var col = 0
            val laid = row.map { cell ->
                val x = widths.take(col).sum()
                val w = widths.drop(col).take(cell.span).sum()
                col += cell.span
                val sb = SpannableStringBuilder()
                cell.paras.forEachIndexed { i, p ->
                    if (i > 0) sb.append('\n')
                    sb.append(spanOf(ctx, p, p.items.filterIsInstance<Item.Text>(), listPrefix(ctx, p)))
                }
                if (sb.isEmpty()) sb.append(' ')
                LaidCell(cell, x, w, layoutOf(sb, (w - 2 * pad).toInt(), cell.paras.firstOrNull()?.let { alignOf(ctx, it) }))
            }
            val rowH = (laid.maxOfOrNull { it.layout.height } ?: 0) + 2 * pad
            if (pager.y + rowH > pager.bottom && pager.y > pager.mt) pager.next()
            for (c in laid) {
                val left = pager.ml + c.x
                val rect = RectF(left, pager.y, left + c.w, pager.y + rowH)
                c.cell.fill?.let { pager.canvas.drawRect(rect, Paint().apply { color = it }) }
                pager.canvas.drawRect(rect, border)
                pager.canvas.save()
                pager.canvas.clipRect(rect)
                pager.canvas.translate(left + pad, pager.y + pad)
                c.layout.draw(pager.canvas)
                pager.canvas.restore()
            }
            pager.y += rowH
        }
    }

    /** Convierte el .docx de `input` en un PDF en `out`. Lanza una excepción si el archivo no es un .docx válido. */
    fun convert(input: InputStream, out: File, tempDir: File) {
        val tmp = File.createTempFile("docx", ".zip", tempDir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            ZipFile(tmp).use { zip ->
                fun read(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                val body = parseBody(read("word/document.xml") ?: throw IllegalArgumentException("No es un documento de Word (.docx)"))
                val ctx = Ctx(
                    read("word/styles.xml")?.let { parseStyles(it) } ?: Styles(),
                    read("word/numbering.xml")?.let { parseNumbering(it) } ?: Numbering(),
                    zip,
                    read("word/_rels/document.xml.rels")?.let { parseRels(it) } ?: emptyMap(),
                )

                val pageW = body.pageW.coerceIn(200f, 1500f)
                val pageH = body.pageH.coerceIn(200f, 2200f)
                val pdf = PdfDocument()
                try {
                    val pager = Pager(
                        pdf, pageW.toInt(), pageH.toInt(),
                        body.ml.coerceIn(18f, pageW / 3), body.mr.coerceIn(18f, pageW / 3),
                        body.mt.coerceIn(18f, pageH / 3), body.mb.coerceIn(18f, pageH / 3),
                    )
                    for (block in body.blocks) {
                        when (block) {
                            is Para -> drawPara(ctx, pager, block)
                            is Table -> drawTable(ctx, pager, block)
                        }
                    }
                    pager.finish()
                    out.outputStream().use { pdf.writeTo(it) }
                } finally {
                    pdf.close()
                }
            }
        } finally {
            tmp.delete()
        }
    }
}
