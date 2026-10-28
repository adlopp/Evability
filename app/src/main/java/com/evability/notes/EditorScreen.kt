package com.evability.notes

import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.filled.Close
import android.widget.Toast
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.splineBasedDecay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlin.math.abs
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

private val EditorBg: Color get() = lerp(Bg, Color.Black, if (IsLight) 0.10f else 0.35f)

private val Palette = listOf(
    0xFF111111, 0xFFFFFFFF, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A,
).map { it.toInt() }

/** Paleta pastel del subrayador: amarillo, naranja, verde, azul, morado y rosa. */
private val HighlightPalette = listOf(
    0xFFFFF59D, 0xFFFFCC80, 0xFFA5D6A7, 0xFF90CAF9, 0xFFCE93D8, 0xFFF8BBD0,
).map { it.toInt() }

private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M16.24,3.56l4.95,4.94c0.78,0.79 0.78,2.05 0,2.84L12,20.53a4.008,4.008 0,0 1,-5.66,0L2.81,17c-0.78,-0.79 -0.78,-2.05 0,-2.84l10.6,-10.6c0.79,-0.78 2.05,-0.78 2.83,0zM4.22,15.58l3.54,3.53c0.78,0.79 2.04,0.79 2.83,0l3.53,-3.53 -4.95,-4.95 -4.95,4.95z"
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

@Composable
fun EditorScreen(state: EditorState, exporting: Boolean, onClose: () -> Unit, onExport: () -> Unit) {
    BackHandler(onBack = onClose)
    var edit by remember { mutableStateOf<TextEdit?>(null) }
    var stickerEdit by remember { mutableStateOf<Sticker?>(null) }
    var postitEdit by remember { mutableStateOf<Postit?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.info.lastPage.coerceIn(0, state.visibleCount - 1))
    val addPage: () -> Unit = {
        scope.launch {
            state.addBlankPage()
            delay(80)
            listState.animateScrollToItem(state.visibleCount - 1)
        }
    }
    val addTemplate: () -> Unit = {
        scope.launch {
            state.addTemplatePage()
            delay(80)
            listState.animateScrollToItem(state.visibleCount - 1)
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar página") },
            text = { Text("¿Eliminar la página ${state.currentPage + 1} de ${state.visibleCount}? Desaparecerá del documento y de la exportación a PDF.") },
            confirmButton = {
                TextButton(onClick = {
                    state.deletePageAt(state.currentPage)
                    confirmDelete = false
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    // Autoguardado de las anotaciones poco después de cada cambio.
    LaunchedEffect(state) {
        snapshotFlow { state.anns }.collect { list ->
            kotlinx.coroutines.delay(500)
            withContext(Dispatchers.IO) { state.store.saveAnns(state.info.id, list) }
        }
    }

    var barsHidden by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(EditorBg).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (!barsHidden) {
                TopBar(state, onClose, onExport, addPage, addTemplate, onDeletePage = { confirmDelete = true }, onHide = { barsHidden = true })
                OptionsBar(state)
                if (state.searchOpen) SearchBar(state)
            }
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Viewer(state, listState, addPage, onTextTap = { edit = it }, onStickerTap = { stickerEdit = it }, onPostitTap = { postitEdit = it }, modifier = Modifier.weight(1f))
        }
        if (barsHidden) {
            IconButton(
                onClick = { barsHidden = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp).clip(CircleShape)
                    .background(Surface1.copy(alpha = 0.92f)),
            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Mostrar barra", tint = Accent) }
        }
    }
    edit?.let { TextDialog(it, state) { edit = null } }
    stickerEdit?.let { StickerDialog(it, state) { stickerEdit = null } }
    postitEdit?.let { PostitDialog(it, state) { postitEdit = null } }
}

// ───────────────────────────── Barras de herramientas ─────────────────────────────

@Composable
private fun TopBar(state: EditorState, onClose: () -> Unit, onExport: () -> Unit, onAddPage: () -> Unit, onAddTemplate: () -> Unit, onDeletePage: () -> Unit, onHide: () -> Unit) {
    Surface(color = Surface1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            IconButton(onClick = onHide) { Icon(Icons.Default.KeyboardArrowUp, "Ocultar barra") }
            Text(
                state.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(max = 220.dp),
            )
            Spacer(Modifier.width(14.dp))
            VDivider()
            ToolBtn(state.tool == Tool.SELECT, Icons.Default.PanTool, "Mover") { state.tool = Tool.SELECT }
            ToolBtn(state.tool == Tool.PEN, Icons.Default.Edit, "Lápiz") { state.tool = Tool.PEN }
            ToolBtn(state.tool == Tool.HIGHLIGHTER, Icons.Default.Highlight, "Resaltador") { state.tool = Tool.HIGHLIGHTER }
            ToolBtn(state.tool == Tool.TEXT, Icons.Default.TextFields, "Texto") { state.tool = Tool.TEXT }
            ToolBtn(state.tool == Tool.STICKER, Icons.Default.EmojiEmotions, "Pegatinas") { state.tool = Tool.STICKER }
            ToolBtn(state.tool == Tool.COVER, Icons.Default.FindReplace, "Cambiar texto del PDF") { state.tool = Tool.COVER }
            ToolBtn(state.tool == Tool.POSTIT, Icons.Default.StickyNote2, "Post-it") { state.tool = Tool.POSTIT }
            ToolBtn(state.tool == Tool.ERASER, EraserIcon, "Borrador") { state.tool = Tool.ERASER }
            ToolBtn(state.straight, Icons.Default.Straighten, "Línea recta") { state.straight = !state.straight }
            VDivider()
            ToolBtn(false, Icons.AutoMirrored.Filled.Undo, "Deshacer", enabled = state.canUndo) { state.undo() }
            ToolBtn(false, Icons.AutoMirrored.Filled.Redo, "Rehacer", enabled = state.canRedo) { state.redo() }
            VDivider()
            ToolBtn(state.searchOpen, Icons.Default.Search, "Buscar en el PDF") { if (state.searchOpen) state.closeSearch() else state.searchOpen = true }
            VDivider()
            ToolBtn(false, Icons.Default.ZoomOut, "Alejar") { state.zoom = (state.zoom - 0.25f).coerceAtLeast(MIN_ZOOM) }
            Text("${(state.zoom * 100).roundToInt()}%", color = OnSurfaceDim, modifier = Modifier.widthIn(min = 46.dp))
            ToolBtn(false, Icons.Default.ZoomIn, "Acercar") { state.zoom = (state.zoom + 0.25f).coerceAtMost(3f) }
            VDivider()
            ToolBtn(false, Icons.Default.NoteAdd, "Añadir página en blanco") { onAddPage() }
            ToolBtn(false, Icons.Default.Delete, "Eliminar esta página", enabled = state.visibleCount > 1) { onDeletePage() }
            if (state.info.templateKind != null) {
                ToolBtn(false, Icons.Default.ContentCopy, "Duplicar la plantilla (vacía) al final") { onAddTemplate() }
            }
            ToolBtn(state.fingerDraw, Icons.Default.TouchApp, "Dibujar también con el dedo") { state.fingerDraw = !state.fingerDraw }
            ToolBtn(false, Icons.Default.FileDownload, "Exportar PDF con anotaciones") { onExport() }
        }
    }
}

/** Barra de búsqueda: escribe y salta entre las coincidencias del PDF (resaltadas en la página). */
@Composable
private fun SearchBar(state: EditorState) {
    val focus = remember { FocusRequester() }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.searchQuery) {
        delay(350) // espera a que termine de escribir
        state.runSearch(state.searchQuery)
    }
    val supported = remember { textSelectionSupported() }
    Surface(color = Surface2) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { state.searchQuery = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                placeholder = { Text(if (supported) "Buscar en el PDF" else "Buscar necesita Android 15 o superior") },
                enabled = supported,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state.searching -> "…"
                    state.searchQuery.trim().length < 2 -> ""
                    state.hits.isEmpty() -> "0 resultados"
                    else -> "${state.hitIndex + 1} de ${state.hits.size}"
                },
                color = OnSurfaceDim, modifier = Modifier.widthIn(min = 84.dp),
            )
            ToolBtn(false, Icons.Default.KeyboardArrowUp, "Coincidencia anterior", enabled = state.hits.isNotEmpty()) { state.stepHit(-1) }
            ToolBtn(false, Icons.Default.KeyboardArrowDown, "Coincidencia siguiente", enabled = state.hits.isNotEmpty()) { state.stepHit(1) }
            ToolBtn(false, Icons.Default.Close, "Cerrar búsqueda") { state.closeSearch() }
        }
    }
}

@Composable
private fun VDivider() {
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(26.dp).background(Outline))
}

@Composable
private fun ToolBtn(selected: Boolean, icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (selected) Accent else OnSurface,
            disabledContentColor = OnSurface.copy(alpha = 0.3f),
        ),
    ) { Icon(icon, label) }
}

@Composable
private fun OptionsBar(state: EditorState) {
    Surface(color = Surface2) {
        Row(
            Modifier.fillMaxWidth().height(if (state.tool == Tool.STICKER) 74.dp else 54.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state.tool) {
                Tool.STICKER -> StickerBar(state)
                Tool.COVER -> Text(
                    "Cambiar texto: arrastra un recuadro con el lápiz sobre el texto del PDF y escribe el nuevo · el borrador quita el tapado",
                    color = OnSurfaceDim,
                )
                Tool.POSTIT -> {
                    Text("Tamaño", color = OnSurfaceDim)
                    Slider(
                        value = state.postitSize,
                        onValueChange = { state.postitSize = it },
                        valueRange = 0.12f..0.6f,
                        modifier = Modifier.width(180.dp).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                    Text("Toca la página para pegar un post-it y dibuja sobre él con el lápiz · pellizca con dos dedos para cambiar su tamaño", color = OnSurfaceDim)
                }
                Tool.SELECT -> Text(
                    "Arrastra textos y trazos con el lápiz para moverlos · toca un texto para editarlo · desplázate con el dedo",
                    color = OnSurfaceDim,
                )
                else -> {
                    if (state.tool != Tool.ERASER) {
                        val color = when (state.tool) {
                            Tool.PEN -> state.penColor
                            Tool.HIGHLIGHTER -> state.hlColor
                            else -> state.textColor
                        }
                        ColorRow(color, palette = if (state.tool == Tool.HIGHLIGHTER) HighlightPalette else Palette) {
                            when (state.tool) {
                                Tool.PEN -> state.penColor = it
                                Tool.HIGHLIGHTER -> state.hlColor = it
                                else -> state.textColor = it
                            }
                        }
                        Spacer(Modifier.width(24.dp))
                    }
                    val (label, value, range) = when (state.tool) {
                        Tool.PEN -> Triple("Grosor", state.penWidth, 0.001f..0.012f)
                        Tool.HIGHLIGHTER -> Triple("Grosor", state.hlWidth, 0.01f..0.05f)
                        Tool.TEXT -> Triple("Tamaño", state.textSize, 0.014f..0.07f)
                        else -> Triple("Borrador", state.eraserRadius, 0.01f..0.06f)
                    }
                    Text(label, color = OnSurfaceDim)
                    Slider(
                        value = value,
                        onValueChange = {
                            when (state.tool) {
                                Tool.PEN -> state.penWidth = it
                                Tool.HIGHLIGHTER -> state.hlWidth = it
                                Tool.TEXT -> state.textSize = it
                                else -> state.eraserRadius = it
                            }
                        },
                        valueRange = range,
                        modifier = Modifier.width(220.dp).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorRow(selected: Int, palette: List<Int> = Palette, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        palette.forEach { c ->
            val sel = c == selected
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) Accent else Outline, CircleShape)
                    .clickable { onSelect(c) }
            )
        }
    }
}

// ───────────────────────────── Visor ─────────────────────────────

@Composable
private fun Viewer(state: EditorState, listState: LazyListState, onAddPage: () -> Unit, onTextTap: (TextEdit) -> Unit, onStickerTap: (Sticker) -> Unit, onPostitTap: (Postit) -> Unit, modifier: Modifier) {
    val visible = remember(state.hidden, state.totalPages) { state.visiblePages() }
    val count = visible.size
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { state.currentPage = minOf(it, state.visibleCount - 1) }
    }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var panX by remember { mutableFloatStateOf(0f) }
    val maxPanHolder = remember { FloatArray(1) }
    val fling = remember { object { var job: Job? = null } }
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    BoxWithConstraints(
        modifier.fillMaxWidth().clipToBounds().pointerInput(Unit) {
            // Pellizco con dos dedos para hacer zoom (el lápiz nunca interviene).
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val touches = ev.changes.filter { it.pressed && it.type == PointerType.Touch }
                    if (touches.size >= 2 && !state.objectGesture) {
                        val z = ev.calculateZoom()
                        if (z != 1f) state.zoom = (state.zoom * z).coerceIn(MIN_ZOOM, 3f)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }.pointerInput(Unit) {
            // Desplazamiento con un dedo en cualquier dirección (vertical y horizontal a la vez) con inercia.
            // Se ejecuta después de los hijos, así que no interfiere con el lápiz ni con arrastrar textos.
            val tracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                // Un nuevo toque frena la inercia. (Debe ir DESPUÉS de esperar el toque: este bloque se reinicia
                // en cuanto termina el gesto anterior y, si cancelara antes, la inercia moriría al soltar el dedo.)
                fling.job?.cancel()
                if (down.type != PointerType.Touch) return@awaitEachGesture
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                var panning = false
                var last = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (ev.changes.count { it.pressed } >= 2 || ch.isConsumed) break
                    tracker.addPosition(ch.uptimeMillis, ch.position)
                    if (!panning && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        panning = true
                        last = ch.position
                    }
                    if (panning) {
                        val d = ch.position - last
                        last = ch.position
                        listState.dispatchRawDelta(-d.y)
                        panX = (panX + d.x).coerceIn(-maxPanHolder[0], 0f)
                        ch.consume()
                    }
                    if (!ch.pressed) {
                        if (panning) {
                            val v = tracker.calculateVelocity()
                            fling.job = scope.launch {
                                // Inercia como la del sistema (algo más larga), en vertical y horizontal a la vez.
                                coroutineScope {
                                    launch {
                                        var prev = 0f
                                        AnimationState(0f, -v.y * FLING_BOOST).animateDecay(decay) {
                                            val delta = value - prev
                                            prev = value
                                            if (abs(delta - listState.dispatchRawDelta(delta)) > 0.5f) cancelAnimation()
                                        }
                                    }
                                    launch {
                                        var prev = 0f
                                        AnimationState(0f, v.x * FLING_BOOST).animateDecay(decay) {
                                            val delta = value - prev
                                            prev = value
                                            val before = panX
                                            panX = (panX + delta).coerceIn(-maxPanHolder[0], 0f)
                                            if (panX == before && delta != 0f) cancelAnimation()
                                        }
                                    }
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    ) {
        val viewportW = maxWidth
        val pageW = (viewportW - 32.dp) * state.zoom
        val contentW = maxOf(viewportW, pageW + 32.dp)
        val maxPan = with(density) { (contentW - viewportW).toPx() }.coerceAtLeast(0f)
        maxPanHolder[0] = maxPan

        // Lleva la vista hasta la coincidencia actual de la búsqueda.
        val pageWpx = with(density) { pageW.toPx() }
        val viewportHpx = with(density) { maxHeight.toPx() }
        LaunchedEffect(state.hitJump) {
            val hit = state.hits.getOrNull(state.hitIndex) ?: return@LaunchedEffect
            val idx = visible.indexOf(hit.page)
            if (idx < 0) return@LaunchedEffect
            val y = hit.rects.minOfOrNull { it.top } ?: 0f
            listState.animateScrollToItem(idx, scrollOffset = (y * pageWpx - viewportHpx / 3f).roundToInt().coerceAtLeast(0))
            panX = panX.coerceIn(-maxPan, 0f)
        }

        // Precarga las páginas cercanas para que, al deslizar, ya estén dibujadas.
        val rw = renderWidthFor(with(density) { pageW.toPx() })
        LaunchedEffect(rw, visible) {
            snapshotFlow { listState.firstVisibleItemIndex to (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                .collectLatest { (first, last) ->
                    delay(50)
                    val wanted = ((first - 1)..(last + 3)).mapNotNull { visible.getOrNull(it) }.filter { it < state.doc.pageCount }.sortedBy { abs(visible.indexOf(it) - first) }
                    for (i in wanted) {
                        val key = "$i@$rw"
                        if (state.cache.get(key) == null) {
                            try {
                                val bmp = withContext(Dispatchers.IO) { state.doc.render(i, rw) }
                                state.cache.put(key, bmp)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                return@collectLatest
                            }
                        }
                    }
                }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(contentW).fillMaxHeight()
                    .offset { IntOffset(panX.coerceIn(-maxPan, 0f).roundToInt(), 0) },
                state = listState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(count, key = { visible[it] }) { i ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PageItem(state, visible[i], pageW, onTextTap, onStickerTap, onPostitTap)
                    }
                }
                item(key = "add-page") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(
                            onClick = onAddPage,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Añadir página en blanco", fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
            shape = RoundedCornerShape(50),
            color = Surface1.copy(alpha = 0.92f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
        ) {
            Text(
                "${state.currentPage + 1} / $count",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = OnSurface,
            )
        }

        state.textSel?.let { sel ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = RoundedCornerShape(50),
                color = Surface1.copy(alpha = 0.97f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
                shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sel.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = OnSurfaceDim,
                        modifier = Modifier.widthIn(max = 200.dp).padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Evability", sel.text))
                        Toast.makeText(ctx, "Texto copiado", Toast.LENGTH_SHORT).show()
                        state.textSel = null
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copiar")
                    }
                    TextButton(onClick = { state.highlightSelection() }) {
                        Icon(Icons.Default.Highlight, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Subrayar")
                    }
                    IconButton(onClick = { state.textSel = null }) { Icon(Icons.Default.Close, "Cerrar selección") }
                }
            }
        }
    }
}

private const val FLING_BOOST = 1.3f

/** Trazo -> línea recta de su primer a su último punto. Si queda casi horizontal o vertical (menos de ~4°), se endereza del todo. */
private fun straightLine(pts: List<Offset>): List<Offset> {
    if (pts.size < 2) return pts
    val a = pts.first()
    var b = pts.last()
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (abs(dx) < 0.004f && abs(dy) < 0.004f) return pts // un toque o garabato diminuto: se deja como punto
    val snap = 0.07f
    if (abs(dy) < abs(dx) * snap) b = Offset(b.x, a.y) else if (abs(dx) < abs(dy) * snap) b = Offset(a.x, b.y)
    return listOf(a, b)
}

/** Zoom mínimo: 0.5 = la página ocupa la mitad del ancho; a los lados queda el fondo oscuro (no se puede escribir fuera de la página). */
private const val MIN_ZOOM = 0.5f

private fun renderWidthFor(px: Float): Int = (ceil(px / 400f) * 400).toInt().coerceIn(800, 2800)

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged() = position != previousPosition
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PageItem(state: EditorState, index: Int, pageWidth: Dp, onTextTap: (TextEdit) -> Unit, onStickerTap: (Sticker) -> Unit, onPostitTap: (Postit) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val renderW = renderWidthFor(with(density) { pageWidth.toPx() })
    // Si la página ya está en caché aparece en el mismo fotograma (sin parpadeo en blanco al volver a ella).
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(state.cache.get("$index@$renderW")?.asImageBitmap()) }

    // Mantiene el bitmap anterior mientras se renderiza el nuevo (p. ej. al cambiar el zoom).
    LaunchedEffect(index, renderW) {
        if (index >= state.doc.pageCount) {
            // Página extra: en blanco (nada que renderizar) o copia de la plantilla del documento.
            val kind = state.templateAt(index) ?: return@LaunchedEffect
            val key = "$index@$renderW"
            val bmp = state.cache.get(key) ?: withContext(Dispatchers.IO) { Templates.preview(kind, renderW) }.also { state.cache.put(key, it) }
            bitmap = bmp.asImageBitmap()
            return@LaunchedEffect
        }
        val key = "$index@$renderW"
        try {
            val bmp = state.cache.get(key) ?: withContext(Dispatchers.IO) { state.doc.render(index, renderW) }
                .also { state.cache.put(key, it) }
            bitmap = bmp.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // El documento se cerró mientras se renderizaba.
        }
    }

    val live = remember { mutableStateListOf<Offset>() }
    val liveStyle = remember { mutableStateOf(Triple(false, 0, 0f)) }
    val liveRect = remember { mutableStateOf<Pair<Offset, Offset>?>(null) }

    Box(
        Modifier.size(pageWidth, pageWidth * state.aspect(index)).background(Color.White)
            .pointerInput(state.tool, state.fingerDraw) {
                awaitEachGesture {
                    val w = size.width.toFloat()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val stylus = down.type == PointerType.Stylus || down.type == PointerType.Mouse || down.type == PointerType.Eraser
                    val canDraw = stylus || state.fingerDraw
                    val tool = if (down.type == PointerType.Eraser) Tool.ERASER else state.tool
                    fun norm(o: Offset) = Offset(o.x / w, o.y / w)

                    when (tool) {
                        Tool.PEN, Tool.HIGHLIGHTER -> {
                            if (!canDraw) return@awaitEachGesture
                            val hl = tool == Tool.HIGHLIGHTER
                            val color = if (hl) state.hlColor else state.penColor
                            val width = if (hl) state.hlWidth else state.penWidth
                            down.consume()
                            // Si el lápiz empieza sobre un post-it, el trazo se dibuja EN el post-it (y lo acompaña al moverlo).
                            val onPost = state.postitAt(index, norm(down.position))
                            liveStyle.value = Triple(hl, color, width)
                            live.clear()
                            live.add(norm(down.position))
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) live.add(norm(h.position))
                                live.add(norm(ch.position))
                                ch.consume()
                                if (!ch.pressed) break
                            }
                            if (live.isNotEmpty()) {
                                val pts = if (state.straight) straightLine(live.toList()) else live.toList()
                                if (onPost != null) state.addPostitStroke(onPost.id, hl, color, width, pts)
                                else state.add(Stroke(state.nextId(), index, hl, color, width, pts))
                            }
                            live.clear()
                        }

                        Tool.ERASER -> {
                            if (!canDraw) return@awaitEachGesture
                            down.consume()
                            state.beginErase()
                            state.eraseAt(index, norm(down.position), state.eraserRadius)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) state.eraseAt(index, norm(h.position), state.eraserRadius)
                                state.eraseAt(index, norm(ch.position), state.eraserRadius)
                                ch.consume()
                                if (!ch.pressed) break
                            }
                        }

                        Tool.COVER -> {
                            if (!canDraw) return@awaitEachGesture
                            val p0 = norm(down.position)
                            down.consume()
                            val existing = state.hit(index, p0, only = Tool.COVER)
                            if (existing != null) {
                                // Mover un tapado ya creado.
                                var moved = false
                                var last = p0
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                        state.checkpoint()
                                    }
                                    if (moved) {
                                        val pos = norm(ch.position)
                                        state.move(existing.id, pos.x - last.x, pos.y - last.y)
                                        last = pos
                                    }
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                            } else {
                                var cur = p0
                                liveRect.value = p0 to p0
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    cur = norm(ch.position)
                                    liveRect.value = p0 to cur
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                                liveRect.value = null
                                val x0 = minOf(p0.x, cur.x)
                                val y0 = minOf(p0.y, cur.y)
                                val x1 = maxOf(p0.x, cur.x)
                                val y1 = maxOf(p0.y, cur.y)
                                if (x1 - x0 > 0.015f && y1 - y0 > 0.006f) {
                                    val bg = bitmap?.let { AnnPainter.sampleBackground(it.asAndroidBitmap(), x0, y0, x1, y1) } ?: 0xFFFFFFFF.toInt()
                                    val pad = 0.003f
                                    val cover = Cover(state.nextId(), index, x0 - pad, y0 - pad, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad, bg)
                                    val size = ((y1 - y0) * 0.8f).coerceIn(0.012f, 0.1f)
                                    val top = y0 + (y1 - y0 - size * 1.17f) / 2f
                                    onTextTap(TextEdit(null, index, Offset(x0, top + size * 0.6f), size, cover))
                                }
                            }
                        }

                        Tool.SELECT, Tool.TEXT, Tool.STICKER, Tool.POSTIT -> {
                            val p0 = norm(down.position)
                            // Con "Texto" solo se arrastran textos; con "Mover" también los trazos.
                            val target = state.hit(index, p0, only = tool)
                            if (target == null) {
                                if (tool == Tool.TEXT || tool == Tool.STICKER || tool == Tool.POSTIT) {
                                    // Un toque corto en una zona vacía crea un texto nuevo. Si el dedo arrastra, es un scroll.
                                    var tapped = true
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id }
                                        if (ch == null || ch.isConsumed ||
                                            (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                                        ) {
                                            tapped = false
                                            break
                                        }
                                        if (!ch.pressed) {
                                            ch.consume()
                                            break
                                        }
                                    }
                                    if (tapped) {
                                        when (tool) {
                                            Tool.TEXT -> onTextTap(TextEdit(null, index, p0))
                                            Tool.POSTIT -> state.placePostit(index, p0)
                                            else -> state.placeSticker(index, p0)
                                        }
                                    }
                                }
                                return@awaitEachGesture
                            }
                            down.consume()
                            // Con dos dedos sobre una pegatina o un texto: pellizco para cambiar el tamaño (y girar).
                            val resizable = target is Sticker || target is TextNote || target is Postit
                            state.objectGesture = resizable
                            var moved = false
                            var last = p0
                            var wasMulti = false
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (resizable && ev.changes.count { it.pressed } >= 2) {
                                        if (!moved) {
                                            moved = true
                                            state.checkpoint()
                                        }
                                        val pan = ev.calculatePan()
                                        state.transformAnn(target.id, ev.calculateZoom(), ev.calculateRotation(), pan.x / w, pan.y / w)
                                        ev.changes.forEach { it.consume() }
                                        wasMulti = true
                                        continue
                                    }
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (wasMulti) {
                                        last = norm(ch.position)
                                        wasMulti = false
                                    }
                                    if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                        state.checkpoint()
                                    }
                                    if (moved) {
                                        val pos = norm(ch.position)
                                        state.move(target.id, pos.x - last.x, pos.y - last.y)
                                        last = pos
                                    }
                                    ch.consume()
                                    if (!ch.pressed) break
                                }
                            } finally {
                                state.objectGesture = false
                            }
                            if (!moved && target is TextNote) onTextTap(TextEdit(target, index, p0))
                            else if (!moved && target is Sticker) onStickerTap(target)
                            else if (!moved && target is Postit) onPostitTap(target)
                        }
                    }
                }
            }
            .pointerInput(index) {
                // Pulsación larga con un dedo: selecciona la palabra (y, arrastrando, un tramo de texto) para copiarla o subrayarla.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.type != PointerType.Touch) return@awaitEachGesture
                    val w = size.width.toFloat()
                    val p0 = Offset(down.position.x / w, down.position.y / w)
                    val fingerDrawing = state.fingerDraw && state.tool in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.COVER)
                    val onObject = state.tool in listOf(Tool.SELECT, Tool.TEXT, Tool.STICKER, Tool.POSTIT) && state.hit(index, p0, only = state.tool) != null
                    if (fingerDrawing || onObject) return@awaitEachGesture

                    var cancelled = false
                    var tapped = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (!cancelled) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null || !ch.pressed || ev.changes.count { it.pressed } >= 2 ||
                                (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            ) {
                                cancelled = true
                                tapped = ch != null && !ch.pressed
                            }
                        }
                    }
                    if (cancelled) {
                        if (tapped) state.textSel = null // un toque corto fuera quita la selección
                        return@awaitEachGesture
                    }

                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    down.consume()
                    var job: Job? = null
                    var pending: Offset? = null
                    // Una sola consulta a la vez: si el dedo se mueve mientras hay una en marcha, solo se guarda el último punto.
                    fun select(a: Offset, b: Offset, first: Boolean) {
                        if (job?.isActive == true) {
                            pending = b
                            return
                        }
                        job = scope.launch {
                            var target: Offset? = b
                            var isFirst = first
                            while (target != null) {
                                val end = target
                                val r = withContext(Dispatchers.IO) {
                                    try { state.doc.selectText(index, a.x, a.y, end.x, end.y) } catch (e: Exception) { null }
                                }
                                if (r != null) state.textSel = r
                                else if (isFirst) {
                                    val msg = if (textSelectionSupported()) "No hay texto seleccionable aquí (¿es un documento escaneado?)" else "Seleccionar texto necesita Android 15 o superior"
                                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                }
                                isFirst = false
                                target = pending
                                pending = null
                            }
                        }
                    }
                    select(p0, p0, true)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            select(p0, Offset(ch.position.x / w, ch.position.y / w), false)
                        }
                    }
                }
            }
    ) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                AnnPainter.drawAll(nc, state.anns.filter { it.page == index }, size.width)
                if (state.hits.isNotEmpty()) {
                    val soft = android.graphics.Paint().apply { color = 0x66FFD54F }
                    val strong = android.graphics.Paint().apply { color = Accent.copy(alpha = 0.55f).toArgb() }
                    val current = state.hits.getOrNull(state.hitIndex)
                    state.hits.forEach { hit ->
                        if (hit.page != index) return@forEach
                        val paint = if (hit === current) strong else soft
                        hit.rects.forEach { r -> nc.drawRect(r.left * size.width, r.top * size.width, r.right * size.width, r.bottom * size.width, paint) }
                    }
                }
                state.textSel?.takeIf { it.page == index }?.let { sel ->
                    val paint = android.graphics.Paint().apply { color = Accent.copy(alpha = 0.35f).toArgb() }
                    sel.rects.forEach { r -> nc.drawRect(r.left * size.width, r.top * size.width, r.right * size.width, r.bottom * size.width, paint) }
                }
                liveRect.value?.let { (a, b) ->
                    val w = size.width
                    nc.drawRect(
                        minOf(a.x, b.x) * w, minOf(a.y, b.y) * w, maxOf(a.x, b.x) * w, maxOf(a.y, b.y) * w,
                        android.graphics.Paint().apply { color = 0x66F5A9C6 },
                    )
                }
                if (live.isNotEmpty()) {
                    val (hl, color, width) = liveStyle.value
                    AnnPainter.drawStroke(nc, if (state.straight) straightLine(live.toList()) else live.toList(), color, width, hl, size.width)
                }
            }
        }
    }
}

// ───────────────────────────── Diálogo de texto ─────────────────────────────

@Composable
private fun TextDialog(edit: TextEdit, state: EditorState, onDismiss: () -> Unit) {
    val existing = edit.existing
    var text by remember { mutableStateOf(existing?.text ?: "") }
    var color by remember { mutableIntStateOf(existing?.color ?: state.textColor) }
    var size by remember { mutableFloatStateOf(existing?.size ?: edit.size ?: state.textSize) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.cover != null) "Escribe el texto nuevo" else if (existing == null) "Nuevo texto" else "Editar texto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus), minLines = 2,
                    label = { Text("Texto") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño", color = OnSurfaceDim)
                    Slider(
                        value = size, onValueChange = { size = it }, valueRange = 0.014f..0.07f,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) { ColorRow(color) { color = it } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = text.trimEnd()
                state.textColor = color
                if (edit.cover == null) state.textSize = size
                if (existing != null) {
                    if (t.isBlank()) state.remove(existing.id)
                    else {
                        state.checkpoint()
                        state.replace(existing.copy(text = t, color = color, size = size))
                    }
                } else if (edit.cover != null) {
                    val items = mutableListOf<Ann>(edit.cover)
                    if (t.isNotBlank()) items += TextNote(state.nextId(), edit.page, t, color, size, edit.pos.x, edit.pos.y - size * 0.6f)
                    state.addAll(items)
                } else if (t.isNotBlank()) {
                    state.add(TextNote(state.nextId(), edit.page, t, color, size, edit.pos.x, edit.pos.y - size * 0.6f))
                }
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { state.remove(existing.id); onDismiss() }) { Text("Eliminar") }
                }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
