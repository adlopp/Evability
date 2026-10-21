package com.evability.notes

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    recents: List<DocInfo>,
    store: Store,
    onImport: () -> Unit,
    onTemplate: (TemplateKind) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpen: (DocInfo) -> Unit,
    onDelete: (DocInfo) -> Unit,
) {
    var toDelete by remember { mutableStateOf<DocInfo?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    val appCtx = LocalContext.current
    val version = remember { Updater.installedVersion(appCtx).second }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(190.dp),
        modifier = Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Hero(onImport, onTemplates = { showTemplates = true }) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Recientes", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                if (recents.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Todavía no has abierto ningún documento. Pulsa «Importar PDF» para empezar.",
                        color = OnSurfaceDim, fontSize = 16.sp,
                    )
                }
            }
        }
        items(recents, key = { it.id }) { doc ->
            RecentCard(doc, store, onClick = { onOpen(doc) }, onDelete = { toDelete = doc })
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.align(Alignment.TopEnd).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("v$version", color = OnSurfaceDim, fontSize = 13.sp)
            IconButton(
                onClick = onCheckUpdate,
                modifier = Modifier.size(46.dp).clip(CircleShape).background(Surface1).border(1.dp, Outline, CircleShape),
            ) { Icon(Icons.Default.Sync, contentDescription = "Buscar actualizaciones", tint = Accent) }
        }
    }

    if (showTemplates) {
        TemplateDialog(
            onPick = { showTemplates = false; onTemplate(it) },
            onDismiss = { showTemplates = false },
        )
    }

    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminar documento") },
            text = { Text("«${doc.name}» y sus anotaciones se borrarán de la app. El PDF original no se toca.") },
            confirmButton = { TextButton(onClick = { onDelete(doc); toDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Hero(onImport: () -> Unit, onTemplates: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val context = LocalContext.current
        val player = remember { MediaPlayer.create(context, R.raw.easter_egg) }
        DisposableEffect(Unit) { onDispose { player?.release() } }
        var bounce by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (bounce) 1.14f else 1f,
            animationSpec = spring(dampingRatio = 0.35f, stiffness = 500f),
            label = "logo",
        )
        LaunchedEffect(bounce) {
            if (bounce) {
                delay(180)
                bounce = false
            }
        }
        Box(
            Modifier.size(176.dp)
                .scale(scale)
                .clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    bounce = true
                    player?.let { it.seekTo(0); it.start() }
                }
                .background(Brush.radialGradient(listOf(Color(0xFF2A2D36), Surface1)), CircleShape)
                .border(1.dp, Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.logo), contentDescription = "Evability", modifier = Modifier.padding(18.dp).fillMaxSize())
        }
        Spacer(Modifier.height(20.dp))
        Text("Evability", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = OnSurface, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(6.dp))
        Text("Abre un PDF, subraya y escribe sobre él", fontSize = 16.sp, color = OnSurfaceDim)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 18.dp),
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Importar PDF", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onTemplates,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.5.dp, Accent),
                contentPadding = PaddingValues(horizontal = 40.dp, vertical = 18.dp),
            ) {
                Icon(Icons.Default.GridView, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Plantillas", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RecentCard(doc: DocInfo, store: Store, onClick: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, doc.id) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.thumbFile(doc.id).path)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Surface1,
        border = BorderStroke(1.dp, Outline),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.85f).background(Color(0xFF23262E))) {
                thumb?.let {
                    Image(
                        it, contentDescription = doc.name, modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    )
                }
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(
                        onClick = { menu = true },
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(0xB3000000)),
                    ) { Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Eliminar") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(4.dp))
                val ago = DateUtils.getRelativeTimeSpanString(doc.lastOpened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                Text("${doc.pages + doc.extra} págs · $ago", color = OnSurfaceDim, fontSize = 13.sp)
            }
        }
    }
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
