package com.evability.notes

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import androidx.compose.material.icons.filled.Add
import kotlin.math.roundToInt
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectDragGestures

private enum class SortMode(val label: String) { RECENT("Recientes"), NAME("Nombre"), CREATED("Creación") }

private sealed interface DocFilter {
    object All : DocFilter
    object Favs : DocFilter
    object Friends : DocFilter
    data class Folder(val name: String) : DocFilter
}

private sealed interface Entry {
    data class Header(val title: String) : Entry
    data class Doc(val info: DocInfo) : Entry
}

private fun buildEntries(docs: List<DocInfo>, sort: SortMode): List<Entry> {
    val out = ArrayList<Entry>()
    when (sort) {
        SortMode.RECENT -> {
            val favs = docs.filter { it.favorite }.sortedByDescending { it.lastOpened }
            if (favs.isNotEmpty()) {
                out += Entry.Header("Favoritos")
                favs.forEach { out += Entry.Doc(it) }
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val today = cal.timeInMillis
            val yesterday = today - 86_400_000L
            val week = today - 6 * 86_400_000L
            fun bucket(t: Long) = when {
                t >= today -> "Hoy"
                t >= yesterday -> "Ayer"
                t >= week -> "Esta semana"
                else -> "Anteriores"
            }
            var last: String? = null
            for (d in docs.filter { !it.favorite }.sortedByDescending { it.lastOpened }) {
                val b = bucket(d.lastOpened)
                if (b != last) {
                    out += Entry.Header(b)
                    last = b
                }
                out += Entry.Doc(d)
            }
        }
        SortMode.NAME -> docs.sortedBy { it.name.lowercase() }.forEach { out += Entry.Doc(it) }
        SortMode.CREATED -> docs.sortedByDescending { it.added }.forEach { out += Entry.Doc(it) }
    }
    return out
}

private fun ago(t: Long): String =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    docs: List<DocInfo>,
    store: Store,
    onImport: () -> Unit,
    onTemplate: (TemplateKind) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpen: (DocInfo) -> Unit,
    onExport: (DocInfo) -> Unit,
    onChanged: () -> Unit,
) {
    val appCtx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val version = remember { Updater.installedVersion(appCtx).second }

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.RECENT) }
    var filter by remember { mutableStateOf<DocFilter>(DocFilter.All) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showTemplates by remember { mutableStateOf(false) }
    var showAnalyze by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    var showPomodoro by remember { mutableStateOf(false) }
    var showSimple by remember { mutableStateOf(false) }
    var showTimeChooser by remember { mutableStateOf(false) }
    var showSocial by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DocInfo?>(null) }
    var sending by remember { mutableStateOf<DocInfo?>(null) }
    var moving by remember { mutableStateOf<List<DocInfo>?>(null) }
    var newFolderFor by remember { mutableStateOf<List<DocInfo>?>(null) }
    var removingFolder by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var ordering by remember { mutableStateOf<String?>(null) }
    var folderTick by remember { mutableStateOf(0) }

    val folders = remember(docs, folderTick) { store.folders() }
    val folderTokens = remember(docs, folderTick) { store.orderedFolders() }
    val friendsName = remember(folderTick) { Prefs.friendsName }
    fun labelOf(token: String) = if (token == FRIENDS_TOKEN) friendsName else token
    fun say(msg: String) {
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(msg) }
    }
    val selecting = selected.isNotEmpty()

    // Si cambia la lista (p. ej. al eliminar), descarta selecciones que ya no existan.
    LaunchedEffect(docs) { selected = selected.filter { id -> docs.any { it.id == id } }.toSet() }

    val visible = remember(docs, query, filter) {
        docs.filter { d ->
            (query.isBlank() || d.name.contains(query.trim(), ignoreCase = true)) && when (val f = filter) {
                DocFilter.All -> true
                DocFilter.Favs -> d.favorite
                DocFilter.Friends -> d.from.isNotBlank()
                is DocFilter.Folder -> d.folder == f.name
            }
        }
    }
    val entries = remember(visible, sort) { buildEntries(visible, sort) }
    val last = remember(docs) { docs.maxByOrNull { it.lastOpened } }
    val selectedDocs = docs.filter { it.id in selected }

    fun deleteDocs(list: List<DocInfo>) {
        store.softDelete(list.map { it.id })
        selected = emptySet()
        onChanged()
        scope.launch {
            val r = snackbar.showSnackbar(
                message = if (list.size == 1) "«${list[0].name}» movido a la papelera" else "${list.size} documentos movidos a la papelera",
                actionLabel = "Deshacer",
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) store.restore(list)
            onChanged()
        }
    }

    fun toggleSelect(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = if (selecting) 84.dp else 10.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TopRow(version, onAnalyze = { showAnalyze = true }, onSocial = { showSocial = true }, onTrash = { showTrash = true }, onSettings = { showSettings = true })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onImport,
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Importar PDF/Word", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { showTemplates = true },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Accent),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Default.GridView, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Plantillas", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            when {
                                Pomodoro.phase != PomoPhase.IDLE -> showPomodoro = true
                                SimpleCounter.running -> showSimple = true
                                else -> showTimeChooser = true
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Accent),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Default.Timer, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Tiempo", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (last != null && query.isBlank() && filter == DocFilter.All && !selecting) {
                item(span = { GridItemSpan(maxLineSpan) }) { ContinueCard(last, store) { onOpen(last) } }
            }

            if (docs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Buscar documentos", color = OnSurfaceDim) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = OnSurfaceDim) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Borrar búsqueda") } }
                            } else {
                                null
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent, unfocusedBorderColor = Outline, cursorColor = Accent,
                                focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                            ),
                        )
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(sort.label)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label, fontWeight = if (m == sort) FontWeight.Bold else FontWeight.Normal) },
                                        onClick = { sort = m; menu = false },
                                    )
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip("Todos", filter == DocFilter.All, null, onLongClick = { say("Esa carpeta no se puede modificar") }) { filter = DocFilter.All }
                        FilterChip("Favoritos", filter == DocFilter.Favs, Icons.Default.Star, onLongClick = { say("Esa carpeta no se puede modificar") }) { filter = DocFilter.Favs }
                        folderTokens.forEach { token ->
                            if (token == FRIENDS_TOKEN) {
                                FilterChip(friendsName, filter == DocFilter.Friends, Icons.Default.People, onLongClick = { folderMenu = token }) { filter = DocFilter.Friends }
                            } else {
                                FilterChip(token, filter == DocFilter.Folder(token), Icons.Default.DriveFileMove, onLongClick = { folderMenu = token }) {
                                    filter = DocFilter.Folder(token)
                                }
                            }
                        }
                        AddChip("Carpeta nueva") { newFolderFor = emptyList() }
                    }
                }
            }

            if (docs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
            } else if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (filter == DocFilter.Friends && query.isBlank()) "Aquí aparecerán los documentos que te envíen tus amigos. Para enviar uno: menú ⋮ de un documento → «Enviar a un amigo»."
                        else "Ningún documento coincide con la búsqueda o el filtro.",
                        color = OnSurfaceDim, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }

            items(
                entries,
                key = { e -> when (e) { is Entry.Header -> "h:" + e.title; is Entry.Doc -> e.info.id } },
                span = { e -> if (e is Entry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { e ->
                when (e) {
                    is Entry.Header -> Text(
                        e.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    is Entry.Doc -> DocCard(
                        doc = e.info,
                        store = store,
                        selected = e.info.id in selected,
                        selecting = selecting,
                        onClick = { if (selecting) toggleSelect(e.info.id) else onOpen(e.info) },
                        onLongClick = { toggleSelect(e.info.id) },
                        onRename = { renaming = e.info },
                        onDuplicate = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.duplicate(e.info.id) }
                                onChanged()
                            }
                        },
                        onFavorite = { store.setFavorite(listOf(e.info.id), !e.info.favorite); onChanged() },
                        onMove = { moving = listOf(e.info) },
                        onSend = { sending = e.info },
                        onExport = { onExport(e.info) },
                        onDelete = { deleteDocs(listOf(e.info)) },
                    )
                }
            }
        }

        if (selecting) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(14.dp).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Surface2,
                border = BorderStroke(1.dp, Outline),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancelar selección") }
                    Text("${selected.size} seleccionado${if (selected.size == 1) "" else "s"}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val allFav = selectedDocs.isNotEmpty() && selectedDocs.all { it.favorite }
                    IconButton(onClick = { store.setFavorite(selected, !allFav); selected = emptySet(); onChanged() }) {
                        Icon(if (allFav) Icons.Default.StarBorder else Icons.Default.Star, contentDescription = "Favorito", tint = Accent)
                    }
                    IconButton(onClick = { moving = selectedDocs }) { Icon(Icons.Default.DriveFileMove, contentDescription = "Mover a carpeta") }
                    IconButton(onClick = { deleteDocs(selectedDocs) }) { Icon(Icons.Default.Delete, contentDescription = "Eliminar") }
                }
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))

        if (showPomodoro) PomodoroScreen { showPomodoro = false }
        if (showSimple) SimpleCounterScreen { showSimple = false }
        if (showSocial) SocialScreen { showSocial = false }
    }

    if (showTimeChooser) {
        TimeChooserDialog(
            onTernurin = { showTimeChooser = false; showPomodoro = true },
            onSimple = { showTimeChooser = false; showSimple = true },
            onDismiss = { showTimeChooser = false },
        )
    }

    if (showTemplates) {
        TemplateDialog(onPick = { showTemplates = false; onTemplate(it) }, onDismiss = { showTemplates = false })
    }
    if (showAnalyze) {
        AnalyzeFlow(
            docs, store,
            onOpenSettings = { showAnalyze = false; showSettings = true },
            onCreated = { onChanged(); onOpen(it) },
            onDismiss = { showAnalyze = false },
        )
    }
    if (showTrash) TrashDialog(store, onChanged) { showTrash = false }
    sending?.let { SendDialog(it, store) { sending = null } }
    if (showSettings) SettingsDialog(version, store, onChanged, onCheckUpdate = { showSettings = false; onCheckUpdate() }) { showSettings = false }

    renaming?.let { doc ->
        TextPromptDialog(
            title = "Renombrar documento", label = "Nombre", initial = doc.name, confirm = "Guardar",
            onDismiss = { renaming = null },
        ) { name ->
            if (name.isNotBlank()) store.rename(doc.id, name.trim())
            renaming = null
            onChanged()
        }
    }

    moving?.let { list ->
        MoveDialog(
            folders = folders,
            onDismiss = { moving = null },
            onPick = { folder ->
                store.setFolder(list.map { it.id }, folder)
                moving = null
                selected = emptySet()
                folderTick++
                onChanged()
            },
            onNew = { newFolderFor = list; moving = null },
        )
    }

    newFolderFor?.let { list ->
        TextPromptDialog(
            title = "Carpeta nueva", label = "Nombre de la carpeta", initial = "", confirm = "Crear",
            onDismiss = { newFolderFor = null },
        ) { name ->
            if (name.isNotBlank()) {
                if (list.isEmpty()) store.addFolder(name) else store.setFolder(list.map { it.id }, name.trim())
                selected = emptySet()
                folderTick++
                onChanged()
            }
            newFolderFor = null
        }
    }

    folderMenu?.let { token ->
        AlertDialog(
            onDismissRequest = { folderMenu = null },
            title = { Text(labelOf(token)) },
            text = {
                Column {
                    TextButton(onClick = { folderMenu = null; renamingFolder = token }) { Text("Renombrar", fontSize = 16.sp) }
                    TextButton(onClick = { folderMenu = null; ordering = token }) { Text("Mover (cambiar el orden)", fontSize = 16.sp) }
                    if (token == FRIENDS_TOKEN) {
                        Text("Esta carpeta se puede renombrar y mover, pero no eliminar.", fontSize = 13.sp, color = OnSurfaceDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    } else {
                        TextButton(onClick = { folderMenu = null; removingFolder = token }) { Text("Eliminar", fontSize = 16.sp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { folderMenu = null }) { Text("Cancelar") } },
        )
    }

    renamingFolder?.let { token ->
        TextPromptDialog(
            title = "Renombrar carpeta", label = "Nombre de la carpeta", initial = labelOf(token), confirm = "Guardar",
            onDismiss = { renamingFolder = null },
        ) { name ->
            val n = name.trim()
            val taken = (listOf("Todos", "Favoritos", friendsName) + folders).filter { it != labelOf(token) }.any { it.equals(n, ignoreCase = true) }
            when {
                n.isBlank() -> {}
                taken -> say("Ya existe una carpeta con ese nombre")
                token == FRIENDS_TOKEN -> { Prefs.friendsName = n; folderTick++ }
                else -> {
                    store.renameFolder(token, n)
                    if (filter == DocFilter.Folder(token)) filter = DocFilter.Folder(n)
                    folderTick++
                    onChanged()
                }
            }
            renamingFolder = null
        }
    }

    ordering?.let { selectedToken ->
        FolderOrderDialog(
            start = folderTokens, selected = selectedToken, labelOf = ::labelOf,
            onLocked = { say("Esa carpeta no se puede modificar") },
            onDone = { list -> store.saveOrder(list); ordering = null; folderTick++ },
            onDismiss = { ordering = null },
        )
    }

    removingFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { removingFolder = null },
            title = { Text("Quitar carpeta") },
            text = { Text("La carpeta «$name» desaparecerá. Sus documentos se conservan, sin carpeta.") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeFolder(name)
                    if (filter == DocFilter.Folder(name)) filter = DocFilter.All
                    removingFolder = null
                    folderTick++
                    onChanged()
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { removingFolder = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Ordenar carpetas: «Todos» y «Favoritos» quedan fijos arriba (con candado); el resto se reordena arrastrando el asa ☰
 * o con las flechas ▲▼.
 */
@Composable
private fun FolderOrderDialog(
    start: List<String>, selected: String, labelOf: (String) -> String,
    onLocked: () -> Unit, onDone: (List<String>) -> Unit, onDismiss: () -> Unit,
) {
    val items = remember { mutableStateListOf<String>().apply { addAll(start) } }
    val rowH = 52.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ordenar carpetas") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Arrastra ☰ para cambiar el orden, o usa las flechas.", fontSize = 13.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(8.dp))
                listOf("Todos", "Favoritos").forEach { name ->
                    Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock, contentDescription = "Fija",
                            tint = OnSurfaceDim,
                            modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(Unit) {
                                detectDragGestures(onDragStart = { onLocked() }) { _, _ -> }
                            },
                        )
                        Text(name, color = OnSurfaceDim, modifier = Modifier.weight(1f))
                    }
                }
                for (token in items.toList()) key(token) {
                    val index = items.indexOf(token)
                    val isDrag = dragging == token
                    Row(
                        Modifier.fillMaxWidth().height(rowH).zIndex(if (isDrag) 1f else 0f)
                            .graphicsLayer { translationY = if (isDrag) dragY else 0f; shadowElevation = if (isDrag) 16f else 0f }
                            .background(if (isDrag) Surface2 else Color.Transparent, RoundedCornerShape(12.dp)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DragHandle, contentDescription = "Arrastrar",
                            tint = if (token == selected) Accent else OnSurfaceDim,
                            modifier = Modifier.size(40.dp).padding(8.dp).pointerInput(token) {
                                detectDragGestures(
                                    onDragStart = { dragging = token; dragY = 0f },
                                    onDragEnd = { dragging = null; dragY = 0f },
                                    onDragCancel = { dragging = null; dragY = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragY += amount.y
                                    val i = items.indexOf(token)
                                    val target = (i + (dragY / rowPx).roundToInt()).coerceIn(0, items.lastIndex)
                                    if (target != i) {
                                        items.add(target, items.removeAt(i))
                                        dragY -= (target - i) * rowPx
                                    }
                                }
                            },
                        )
                        Text(
                            labelOf(token), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (token == selected) Accent else OnSurface,
                            fontWeight = if (token == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        IconButton(enabled = index > 0, onClick = { items.add(index - 1, items.removeAt(index)) }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir")
                        }
                        IconButton(enabled = index < items.lastIndex, onClick = { items.add(index + 1, items.removeAt(index)) }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(items.toList()) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ───────────────────────────── Cabecera ─────────────────────────────

@Composable
private fun TopRow(version: String, onAnalyze: () -> Unit, onSocial: () -> Unit, onTrash: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val player = remember { MediaPlayer.create(context, R.raw.easter_egg) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    var bounce by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (bounce) 1.16f else 1f,
        animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f),
        label = "logo",
    )
    LaunchedEffect(bounce) {
        if (bounce) {
            delay(180)
            bounce = false
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            // Cuadrado con las esquinas redondeadas, como el icono de la app: así no se corta la cabeza del Ternurín.
            Modifier.size(58.dp).scale(scale).clip(RoundedCornerShape(15.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    bounce = true
                    if (!Prefs.logoMuted) player?.let { it.seekTo(0); it.start() }
                }
                .background(Color(0xFF1B1D24), RoundedCornerShape(15.dp))
                .border(1.dp, Outline, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_fg), contentDescription = "Evability", modifier = Modifier.fillMaxSize().scale(1.3f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Evability", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = OnSurface, letterSpacing = 0.4.sp)
            Text("v$version", fontSize = 12.sp, color = OnSurfaceDim)
        }
        RoundIconButton(Icons.Default.People, "Amigos", onSocial, badge = Social.unread.values.any { it } || Social.pending > 0)
        Spacer(Modifier.width(10.dp))
        RoundIconButton(Icons.Default.SmartToy, "Analizar", onAnalyze)
        Spacer(Modifier.width(10.dp))
        RoundIconButton(Icons.Default.Delete, "Papelera", onTrash)
        Spacer(Modifier.width(10.dp))
        RoundIconButton(Icons.Default.Settings, "Ajustes", onSettings)
    }
}

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, badge: Boolean = false) {
    Box {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(46.dp).clip(CircleShape).background(Surface1).border(1.dp, Outline, CircleShape),
        ) { Icon(icon, contentDescription = description, tint = Accent) }
        if (badge) Box(Modifier.align(Alignment.TopEnd).size(12.dp).background(Accent, CircleShape).border(2.dp, Bg, CircleShape))
    }
}

@Composable
private fun ContinueCard(doc: DocInfo, store: Store, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, doc.id, doc.lastOpened) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.thumbFile(doc.id).path)?.asImageBitmap() }
    }
    val total = (doc.pages + doc.extra - doc.hidden.size).coerceAtLeast(1)
    val page = (doc.lastPage + 1).coerceIn(1, total)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Outline),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(92.dp).aspectRatio(0.78f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                thumb?.let {
                    Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text("Continuar donde lo dejaste", fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(doc.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Página $page de $total · ${ago(doc.lastOpened)}", fontSize = 13.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { page / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = Accent, trackColor = Surface2, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onClick, shape = RoundedCornerShape(14.dp)) { Text("Continuar", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterChip(text: String, selected: Boolean, icon: ImageVector?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.2f) else Surface1)
            .border(1.dp, if (selected) Accent else Outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = if (selected) Accent else OnSurfaceDim)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontSize = 14.sp, color = if (selected) Accent else OnSurface, fontWeight = FontWeight.Medium)
    }
}

/** Botón «+» al final de las pestañas de carpeta, para crear una carpeta nueva. */
@Composable
private fun AddChip(description: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(Surface1).border(1.dp, Outline, CircleShape).clickable(onClick = onClick).padding(9.dp),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Add, contentDescription = description, modifier = Modifier.size(20.dp), tint = Accent) }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val cat by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) { StickerBitmaps.get("a:cat_gray_sleep", 512)?.asImageBitmap() }
        }
        cat?.let { Image(it, contentDescription = null, modifier = Modifier.size(150.dp)) }
        Spacer(Modifier.height(12.dp))
        Text("Aquí aparecerán tus documentos", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Importa un PDF o empieza con una plantilla.", color = OnSurfaceDim)
    }
}

// ───────────────────────────── Tarjetas ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(
    doc: DocInfo,
    store: Store,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onSend: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, doc.id, doc.lastOpened) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.thumbFile(doc.id).path)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }
    val total = (doc.pages + doc.extra - doc.hidden.size).coerceAtLeast(1)
    val page = (doc.lastPage + 1).coerceIn(1, total)
    val shape = RoundedCornerShape(18.dp)

    Surface(
        modifier = Modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        color = Surface1,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Outline),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.85f).background(Color.White)) {
                thumb?.let {
                    Image(it, contentDescription = doc.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                }
                if (selecting) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(10.dp).size(28.dp).clip(CircleShape)
                            .background(if (selected) Accent else Color(0xB3000000))
                            .border(1.5.dp, if (selected) Accent else Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = OnAccent, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (doc.favorite) {
                            Icon(
                                Icons.Default.Star, contentDescription = "Favorito", tint = Accent,
                                modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xB3000000)).padding(6.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Box {
                            IconButton(
                                onClick = { menu = true },
                                modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xB3000000)),
                            ) { Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.White, modifier = Modifier.size(20.dp)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuRow("Renombrar", Icons.Default.Edit) { menu = false; onRename() }
                                MenuRow("Duplicar", Icons.Default.ContentCopy) { menu = false; onDuplicate() }
                                MenuRow(if (doc.favorite) "Quitar de favoritos" else "Añadir a favoritos", if (doc.favorite) Icons.Default.StarBorder else Icons.Default.Star) {
                                    menu = false; onFavorite()
                                }
                                MenuRow("Mover a carpeta", Icons.Default.DriveFileMove) { menu = false; onMove() }
                                MenuRow("Enviar a un amigo", Icons.AutoMirrored.Filled.Send) { menu = false; onSend() }
                                MenuRow("Exportar con anotaciones", Icons.Default.FileDownload) { menu = false; onExport() }
                                MenuRow("Eliminar", Icons.Default.Delete) { menu = false; onDelete() }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (doc.from.isNotBlank()) "de ${doc.from} · " else "") + (if (doc.folder.isNotBlank()) doc.folder + " · " else "") + ago(doc.lastOpened),
                    color = OnSurfaceDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { page / total.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Accent, trackColor = Surface2, gapSize = 0.dp, drawStopIndicator = {},
                )
                Spacer(Modifier.height(5.dp))
                Text("Página $page de $total", color = OnSurfaceDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null) }, onClick = onClick)
}

// ───────────────────────────── Diálogos ─────────────────────────────

@Composable
private fun TextPromptDialog(
    title: String, label: String, initial: String, confirm: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun MoveDialog(folders: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit, onNew: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mover a carpeta") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onPick("") }) { Text("Sin carpeta") }
                folders.forEach { f -> TextButton(onClick = { onPick(f) }) { Text(f) } }
                TextButton(onClick = onNew) { Text("+ Carpeta nueva…", color = Accent) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun TrashDialog(store: Store, onChanged: () -> Unit, onDismiss: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val items = remember(tick) { store.loadTrash() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Papelera") },
        text = {
            if (items.isEmpty()) {
                Text("La papelera está vacía.", color = OnSurfaceDim)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Se vacía sola a los 30 días.", fontSize = 12.sp, color = OnSurfaceDim)
                    items.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.info.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("Eliminado ${ago(t.deleted)}", fontSize = 12.sp, color = OnSurfaceDim)
                            }
                            IconButton(onClick = { store.restore(listOf(t.info)); tick++; onChanged() }) {
                                Icon(Icons.Default.Restore, contentDescription = "Restaurar", tint = Accent)
                            }
                            IconButton(onClick = { store.purge(listOf(t.info)); tick++ }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = "Eliminar para siempre", tint = OnSurfaceDim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
        dismissButton = if (items.isEmpty()) null else ({ TextButton(onClick = { confirmEmpty = true }) { Text("Vaciar papelera") } }),
    )
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Vaciar papelera") },
            text = { Text("Se eliminarán para siempre ${items.size} documento(s). No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { store.purge(items.map { it.info }); confirmEmpty = false; tick++ }) { Text("Vaciar") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SettingsDialog(version: String, store: Store, onChanged: () -> Unit, onCheckUpdate: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logoMuted by remember { mutableStateOf(Prefs.logoMuted) }
    var heartMuted by remember { mutableStateOf(Prefs.heartMuted) }
    var busy by remember { mutableStateOf(false) }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val n = withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { Backup.export(store, it) } }
                Toast.makeText(ctx, "Copia guardada con $n documento${if (n == 1) "" else "s"}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "No se pudo crear la copia", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val n = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { Backup.import(ctx, store, it) } }
                Toast.makeText(ctx, if (n > 0) "Se han recuperado $n documento${if (n == 1) "" else "s"}" else "No había documentos nuevos en esa copia", Toast.LENGTH_LONG).show()
                onChanged()
            } catch (e: Exception) {
                Toast.makeText(ctx, "No se pudo leer esa copia de seguridad", Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Color de los botones", color = OnSurfaceDim)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Prefs.ACCENTS.forEach { (name, argb) ->
                        val c = Color(argb)
                        val sel = AccentBase == c
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(c)
                                .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Outline, CircleShape)
                                .clickable { Prefs.setAccent(argb) },
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Icon(Icons.Default.Check, contentDescription = name, tint = Color(0xFF2A1420), modifier = Modifier.size(20.dp)) }
                    }
                }
                Text("Color de fondo", color = OnSurfaceDim)
                BackgroundRow("Oscuros", Prefs.BACKGROUNDS)
                BackgroundRow("Claros", Prefs.LIGHT_BACKGROUNDS)
                GeminiKeySection()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copia de seguridad", color = OnSurfaceDim)
                    Text(
                        "Guarda todos tus documentos y anotaciones en un archivo .zip (por ejemplo en Google Drive) y recupéralos cuando quieras.",
                        fontSize = 12.sp, color = OnSurfaceDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { saveBackup.launch("evability-copia-${java.time.LocalDate.now()}.zip") }) { Text(if (busy) "Trabajando…" else "Exportar copia") }
                        OutlinedButton(enabled = !busy, onClick = { openBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("Importar copia") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Silenciar icono del Ternurín", color = OnSurface)
                        Text("El logo de arriba no suena al tocarlo", fontSize = 12.sp, color = OnSurfaceDim)
                    }
                    Switch(checked = logoMuted, onCheckedChange = { logoMuted = it; Prefs.logoMuted = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Silenciar el sonido de los corazones", color = OnSurface)
                        Text("El corazón sale y vibra, pero sin la canción", fontSize = 12.sp, color = OnSurfaceDim)
                    }
                    Switch(checked = heartMuted, onCheckedChange = { heartMuted = it; Prefs.heartMuted = it })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Actualizaciones", color = OnSurfaceDim)
                    TextButton(onClick = onCheckUpdate, contentPadding = PaddingValues(0.dp)) {
                        Text("Comprobar actualizaciones", color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Evability v$version", fontSize = 12.sp, color = OnSurfaceDim)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@Composable
private fun TemplateDialog(onPick: (TemplateKind) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Surface1,
            border = BorderStroke(1.dp, Outline),
            modifier = Modifier.padding(24.dp).widthIn(max = 820.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp)) {
                Text("Plantillas", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                Spacer(Modifier.height(4.dp))
                Text("Elige cómo quieres empezar", fontSize = 16.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TemplateKind.entries.forEach { kind ->
                        TemplateCard(kind, Modifier.weight(1f)) { onPick(kind) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancelar", fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(kind: TemplateKind, modifier: Modifier, onClick: () -> Unit) {
    val preview by produceState<ImageBitmap?>(null, kind) {
        value = withContext(Dispatchers.IO) { Templates.preview(kind, 420).asImageBitmap() }
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Surface2,
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(595f / 842f).clip(RoundedCornerShape(8.dp)).background(Color.White),
            ) {
                preview?.let { Image(it, contentDescription = kind.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            }
            Spacer(Modifier.height(12.dp))
            Text(kind.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurface)
            Spacer(Modifier.height(2.dp))
            Text(kind.subtitle, fontSize = 13.sp, color = OnSurfaceDim, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun BackgroundRow(title: String, list: List<Pair<String, Long>>) {
    Text(title, fontSize = 12.sp, color = OnSurfaceDim)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        list.forEach { (name, argb) ->
            val c = Color(argb)
            val sel = Bg == c
            Column(Modifier.clickable { Prefs.setBackground(argb) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(c)
                        .border(if (sel) 3.dp else 1.dp, if (sel) Accent else lerp(c, OnSurface, 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (sel) Icon(Icons.Default.Check, contentDescription = name, tint = Accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.height(4.dp))
                Text(name, fontSize = 11.sp, color = if (sel) OnSurface else OnSurfaceDim)
            }
        }
    }
}
