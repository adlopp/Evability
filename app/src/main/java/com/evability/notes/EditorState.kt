package com.evability.notes

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlinx.coroutines.ensureActive

class EditorState(
    val store: Store,
    val info: DocInfo,
    val doc: PdfDoc,
    val aspects: List<Float>,
) {
    var anns by mutableStateOf(store.loadAnns(info.id))
        private set

    var tool by mutableStateOf(Tool.PEN)
    var penColor by mutableIntStateOf(0xFF1E88E5.toInt())
    var hlColor by mutableIntStateOf(0xFFFFF59D.toInt())
    var textColor by mutableIntStateOf(0xFF111111.toInt())
    var penWidth by mutableFloatStateOf(0.003f)
    var hlWidth by mutableFloatStateOf(0.024f)
    var textSize by mutableFloatStateOf(0.028f)
    var eraserRadius by mutableFloatStateOf(0.02f)

    var currentSticker by mutableStateOf<String?>("a:cat_tabby_sit")
    var stickerSize by mutableFloatStateOf(0.14f)
    var postitSize by mutableFloatStateOf(0.3f)
    var userStickers by mutableStateOf(StickerBitmaps.listUser())

    /** True mientras un dedo arrastra una pegatina o un texto: el visor no debe interpretar el pellizco como zoom de página. */
    @Volatile
    var objectGesture = false

    /** Texto seleccionado con pulsación larga (null = nada seleccionado). */
    var textSel by mutableStateOf<TextSelection?>(null)

    // ───── Búsqueda de texto en el PDF ─────

    class SearchHit(val page: Int, val rects: List<android.graphics.RectF>)

    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var hitIndex by mutableIntStateOf(0)
    var searching by mutableStateOf(false)
        private set
    /** Cambia cada vez que hay que llevar la vista hasta la coincidencia actual. */
    var hitJump by mutableIntStateOf(0)

    /** Busca `query` en todas las páginas visibles del PDF (las en blanco no tienen texto). */
    suspend fun runSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) {
            hits = emptyList(); hitIndex = 0; searching = false
            return
        }
        searching = true
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = ArrayList<SearchHit>()
            for (p in visiblePages()) {
                if (p >= doc.pageCount) continue
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try { doc.search(p, q).forEach { out += SearchHit(p, it) } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            }
            out
        }
        hits = found
        hitIndex = 0
        searching = false
        if (found.isNotEmpty()) hitJump++
    }

    fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        hitIndex = (hitIndex + delta).mod(hits.size)
        hitJump++
    }

    fun closeSearch() {
        searchOpen = false; searchQuery = ""; hits = emptyList(); hitIndex = 0
    }

    /** Subraya con el resaltador (en su color actual) las líneas del texto seleccionado. */
    fun highlightSelection() {
        val sel = textSel ?: return
        addAll(
            sel.rects.map { r ->
                Stroke(nextId(), sel.page, true, hlColor, r.height() * 1.05f, listOf(Offset(r.left, r.centerY()), Offset(r.right, r.centerY())))
            }
        )
        textSel = null
    }

    /** Pellizco/giro sobre una pegatina o un texto. `dx`, `dy` en coordenadas normalizadas. */
    fun transformAnn(id: Long, zoom: Float, rotDeg: Float, dx: Float, dy: Float) {
        anns = anns.map {
            if (it.id != id) it else when (it) {
                is Sticker -> it.copy(size = (it.size * zoom).coerceIn(0.03f, 1.2f), rot = it.rot + rotDeg, x = it.x + dx, y = it.y + dy)
                is TextNote -> it.copy(size = (it.size * zoom).coerceIn(0.01f, 0.2f), x = it.x + dx, y = it.y + dy)
                is Postit -> it.copy(size = (it.size * zoom).coerceIn(0.08f, 1.2f), rot = it.rot + rotDeg, x = it.x + dx, y = it.y + dy)
                else -> it
            }
        }
    }

    /** Pega un post-it vacío y pasa al lápiz para poder dibujar en él enseguida. */
    fun placePostit(page: Int, p: Offset) {
        val half = postitSize / 2f
        add(Postit(nextId(), page, p.x.coerceIn(half, 1f - half), p.y.coerceAtLeast(half), postitSize, 0f))
        tool = Tool.PEN
    }

    fun postitAt(page: Int, p: Offset): Postit? =
        anns.asReversed().firstOrNull { it is Postit && it.page == page && AnnPainter.hitPostit(it, p) } as? Postit

    /** Añade un trazo (puntos en coordenadas de página) al post-it, convertido a coordenadas locales. */
    fun addPostitStroke(id: Long, highlighter: Boolean, color: Int, width: Float, pts: List<Offset>) {
        val ps = anns.firstOrNull { it.id == id } as? Postit ?: return
        checkpoint()
        replace(ps.copy(strokes = ps.strokes + PostitStroke(color, width / ps.size, highlighter, pts.map { AnnPainter.toLocal(ps, it) })))
    }

    fun placeSticker(page: Int, p: Offset) {
        val src = currentSticker ?: return
        add(Sticker(nextId(), page, src, p.x, p.y, stickerSize, 0f))
    }

    /** Páginas en blanco añadidas debajo del PDF. */
    var extraPages by mutableIntStateOf(info.extra)
        private set
    val totalPages get() = doc.pageCount + extraPages

    /** Páginas eliminadas (índices reales). El PDF no se modifica: simplemente no se muestran ni se exportan. */
    var hidden by mutableStateOf(info.hidden)
        private set

    /** Índices reales de las páginas que se ven, en orden. La posición en esta lista es la que cuenta el visor. */
    fun visiblePages(): List<Int> = (0 until totalPages).filter { it !in hidden }

    val visibleCount get() = totalPages - hidden.count { it < totalPages }

    /** Elimina la página que está en la posición `position` del visor. Siempre queda al menos una. */
    fun deletePageAt(position: Int) {
        val pages = visiblePages()
        if (pages.size <= 1) return
        val real = pages.getOrNull(position) ?: return
        hidden = hidden + real
        store.setHidden(info.id, hidden)
    }

    /** Página original que se usa de miniatura (la primera visible); null si solo quedan páginas añadidas. */
    fun thumbPage(): Int? = visiblePages().firstOrNull { it < doc.pageCount }

    fun aspect(i: Int): Float = aspects.getOrNull(i) ?: aspects.firstOrNull() ?: 1.4142f

    /** Por cada página extra: "" = en blanco, o el nombre de la plantilla que se duplicó. */
    var extraKinds by mutableStateOf(List(info.extra) { info.extraKinds.getOrNull(it).orEmpty() })
        private set

    fun addBlankPage() = addExtra("")

    /** Duplica la plantilla (vacía) del documento como nueva página al final. */
    fun addTemplatePage() {
        info.templateKind?.let { addExtra(it.name) }
    }

    private fun addExtra(kind: String) {
        extraKinds = extraKinds + kind
        extraPages += 1
        store.setExtra(info.id, extraPages, extraKinds)
    }

    /** Plantilla con la que se dibuja la página `index` si es una copia de la plantilla. */
    fun templateAt(index: Int): TemplateKind? =
        TemplateKind.entries.firstOrNull { it.name == extraKinds.getOrNull(index - doc.pageCount) }

    /** Con "línea recta" activada, cada trazo de lápiz o resaltador se queda recto (de donde empieza a donde acaba). */
    var straight by mutableStateOf(false)

    /** Si es false, solo el lápiz dibuja y el dedo desplaza/hace zoom. */
    var fingerDraw by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var currentPage by mutableIntStateOf(info.lastPage)

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<List<Ann>>()
    private val redoStack = ArrayList<List<Ann>>()
    private var idSeq = max(System.currentTimeMillis(), (anns.maxOfOrNull { it.id } ?: 0L) + 1)
    private var erasing = false

    val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun nextId() = idSeq++

    private fun sync() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Guarda el estado actual para poder deshacer; llamar antes de una modificación. */
    fun checkpoint() {
        undoStack.add(anns)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        sync()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(anns)
        anns = undoStack.removeAt(undoStack.size - 1)
        sync()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(anns)
        anns = redoStack.removeAt(redoStack.size - 1)
        sync()
    }

    fun add(a: Ann) {
        checkpoint()
        anns = anns + a
    }

    fun addAll(list: List<Ann>) {
        checkpoint()
        anns = anns + list
    }

    fun remove(id: Long) {
        checkpoint()
        anns = anns.filter { it.id != id }
    }

    /** Sustituye una anotación sin crear punto de deshacer (usar tras checkpoint()). */
    fun replace(a: Ann) {
        anns = anns.map { if (it.id == a.id) a else it }
    }

    fun move(id: Long, dx: Float, dy: Float) {
        anns = anns.map { if (it.id == id) it.moved(dx, dy) else it }
    }

    /** La anotación más alta bajo el punto `p` (coordenadas normalizadas) de esa página. */
    fun hit(page: Int, p: Offset, only: Tool? = null): Ann? = anns.asReversed().firstOrNull {
        val any = only == null || only == Tool.SELECT
        it.page == page && when (it) {
            is TextNote -> (any || only == Tool.TEXT) && AnnPainter.hitText(it, p)
            is Stroke -> any && AnnPainter.distToStroke(it, p) <= max(it.width / 2f, 0.012f)
            is Sticker -> (any || only == Tool.STICKER) && AnnPainter.hitSticker(it, p)
            is Postit -> (any || only == Tool.POSTIT) && AnnPainter.hitPostit(it, p)
            is Cover -> only == Tool.COVER && AnnPainter.hitCover(it, p)
        }
    }

    fun beginErase() {
        erasing = false
    }

    fun eraseAt(page: Int, p: Offset, r: Float): Boolean {
        val hits = anns.filter {
            it.page == page && when (it) {
                is Stroke -> AnnPainter.distToStroke(it, p) <= r + it.width / 2f
                is TextNote -> AnnPainter.hitText(it, p)
                is Sticker -> AnnPainter.hitSticker(it, p)
                is Postit -> false // el post-it entero solo se elimina desde su diálogo; el borrador quita sus trazos
                is Cover -> AnnPainter.hitCover(it, p)
            }
        }
        // Un tapado solo se borra si no hay nada más (trazos, textos...) bajo el borrador.
        val nonCover = hits.filter { it !is Cover }
        val ids = (if (nonCover.isNotEmpty()) nonCover else hits).map { it.id }.toSet()
        // Trazos dibujados sobre un post-it.
        var stroked = false
        val afterStrokes = anns.map { a ->
            if (a is Postit && a.page == page && a.strokes.isNotEmpty()) {
                val lp = AnnPainter.toLocal(a, p)
                val rl = r / a.size
                val kept = a.strokes.filter { AnnPainter.distToPoints(it.points, lp) > rl + it.width / 2f }
                if (kept.size != a.strokes.size) {
                    stroked = true
                    a.copy(strokes = kept)
                } else {
                    a
                }
            } else {
                a
            }
        }
        if (ids.isEmpty() && !stroked) return false
        if (!erasing) {
            checkpoint()
            erasing = true
        }
        anns = afterStrokes.filter { it.id !in ids }
        return true
    }

    fun save() = store.saveAnns(info.id, anns)
}
