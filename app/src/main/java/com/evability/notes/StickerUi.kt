package com.evability.notes

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contenido de la barra de opciones cuando la herramienta activa es "Pegatinas". */
@Composable
internal fun StickerBar(state: EditorState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var openPack by remember { mutableStateOf<StickerBitmaps.Pack?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { StickerBitmaps.importUser(ctx, it) } }
            state.userStickers = StickerBitmaps.listUser()
            names.lastOrNull()?.let { state.currentSticker = "f:$it" }
            if (names.size < uris.size) Toast.makeText(ctx, "Alguna imagen no se pudo importar", Toast.LENGTH_SHORT).show()
        }
    }

    IconButton(onClick = { picker.launch(arrayOf("image/*")) }) {
        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Importar mis imágenes como pegatinas", tint = Accent)
    }
    Text("Tamaño", color = OnSurfaceDim)
    Slider(
        value = state.stickerSize,
        onValueChange = { state.stickerSize = it },
        valueRange = 0.05f..0.4f,
        modifier = Modifier.width(160.dp).padding(horizontal = 8.dp),
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
    )
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).size(1.dp, 30.dp).background(Outline))
    StickerBitmaps.PACKS.forEach { pack -> key(pack.prefix) { PackTile(pack) { openPack = pack } } }
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).size(1.dp, 30.dp).background(Outline))
    (state.userStickers.map { "f:$it" } + StickerBitmaps.EMOJI.map { "e:$it" }).forEach { src ->
        key(src) { StickerChoice(src, src == state.currentSticker) { state.currentSticker = src } }
    }

    openPack?.let { pack ->
        PackDialog(pack, onPick = { state.currentSticker = it; openPack = null }, onDismiss = { openPack = null })
    }
}

/** Portada del pack con un "+" que abre el submenú con todas sus pegatinas. */
@Composable
private fun PackTile(pack: StickerBitmaps.Pack, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.padding(horizontal = 3.dp).size(58.dp).clip(shape)
            .background(Surface2).border(1.dp, Outline, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StickerImage("a:${pack.cover}", 44.dp)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Add, contentDescription = "Abrir ${pack.title}", tint = OnAccent, modifier = Modifier.size(14.dp)) }
    }
}

@Composable
private fun PackDialog(pack: StickerBitmaps.Pack, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val names = remember(pack) { StickerBitmaps.items(pack) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Surface1,
            border = BorderStroke(1.dp, Outline),
            modifier = Modifier.padding(24.dp).widthIn(max = 640.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(pack.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                Spacer(Modifier.height(4.dp))
                Text("Toca una pegatina para elegirla", color = OnSurfaceDim)
                Spacer(Modifier.height(16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(names) { name ->
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Surface2).clickable { onPick("a:$name") },
                            contentAlignment = Alignment.Center,
                        ) { StickerImage("a:$name", 88.dp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
            }
        }
    }
}

@Composable
private fun StickerImage(src: String, size: Dp) {
    val bmp by produceState<ImageBitmap?>(null, src) {
        value = withContext(Dispatchers.IO) { StickerBitmaps.get(src, 256)?.asImageBitmap() }
    }
    bmp?.let { Image(it, contentDescription = null, modifier = Modifier.size(size)) }
}

/** Opciones de un post-it ya colocado. */
@Composable
internal fun PostitDialog(postit: Postit, state: EditorState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post-it") },
        text = {
            Text(
                "Dibuja sobre él con el lápiz o el resaltador. Pellizca con dos dedos para cambiar su tamaño y girarlo.",
                color = OnSurfaceDim,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = {
            Row {
                if (postit.strokes.isNotEmpty()) {
                    TextButton(onClick = {
                        state.checkpoint()
                        state.replace(postit.copy(strokes = emptyList()))
                        onDismiss()
                    }) { Text("Borrar dibujo") }
                }
                TextButton(onClick = { state.remove(postit.id); onDismiss() }) { Text("Eliminar") }
            }
        },
    )
}

@Composable
private fun StickerChoice(src: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.padding(horizontal = 3.dp).size(54.dp).clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (src.startsWith("e:")) {
            Text(src.substring(2), fontSize = 30.sp)
        } else {
            StickerImage(src, 42.dp)
        }
    }
}

/** Ajustar tamaño y rotación de una pegatina ya colocada, duplicarla o quitarla. */
@Composable
internal fun StickerDialog(sticker: Sticker, state: EditorState, onDismiss: () -> Unit) {
    var size by remember { mutableFloatStateOf(sticker.size) }
    var rot by remember { mutableFloatStateOf(sticker.rot) }
    LaunchedEffect(Unit) { state.checkpoint() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pegatina") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tamaño", color = OnSurfaceDim)
                Slider(
                    value = size,
                    onValueChange = { size = it; state.replace(sticker.copy(size = it, rot = rot)) },
                    valueRange = 0.04f..0.6f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                Text("Rotación", color = OnSurfaceDim)
                Slider(
                    value = rot,
                    onValueChange = { rot = it; state.replace(sticker.copy(size = size, rot = it)) },
                    valueRange = -180f..180f,
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    state.add(sticker.copy(id = state.nextId(), x = sticker.x + 0.04f, y = sticker.y + 0.04f, size = size, rot = rot))
                    onDismiss()
                }) { Text("Duplicar") }
                TextButton(onClick = { state.remove(sticker.id); onDismiss() }) { Text("Eliminar") }
            }
        },
    )
}
