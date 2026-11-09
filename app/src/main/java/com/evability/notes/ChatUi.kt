package com.evability.notes

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
import java.util.Locale
import androidx.compose.foundation.layout.aspectRatio
import androidx.activity.result.PickVisualMediaRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.SportsEsports

/** Chat con un amigo: los mensajes se ven en cuanto llegan (se consultan cada 3 s mientras la pantalla está abierta). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(friend: Social.Profile, group: Social.Group? = null, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Social.ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<Social.ChatMessage?>(null) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var showGames by remember { mutableStateOf(false) }
    var playingWordle by remember { mutableStateOf(false) }
    var chessId by remember { mutableStateOf<String?>(null) }
    var polyId by remember { mutableStateOf<String?>(null) }
    var pickingTime by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var myPhoto by remember { mutableStateOf("") }
    val myName = Social.session?.username ?: ""
    // Si es un grupo: el grupo (se actualiza al editarlo), si está abierta su información y los perfiles de quienes escriben.
    var shownGroup by remember { mutableStateOf(group) }
    var showInfo by remember { mutableStateOf(false) }
    var profs by remember { mutableStateOf<Map<String, Social.Profile>>(emptyMap()) }
    fun who(u: String) = if (u == Social.session?.uid) "Tú" else if (group != null) profs[u]?.username ?: "Alguien" else friend.username
    val listState = rememberLazyListState()
    val hour = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    var delSince by remember { mutableLongStateOf(Social.serverNow()) }
    var menuMsg by remember { mutableStateOf<Social.ChatMessage?>(null) }

    val loads = remember { IntArray(1) }

    suspend fun load(): Boolean {
        if (loads[0]++ % 3 == 0) runCatching {
            val (ids, newest) = Social.chatDeletions(friend.uid, delSince)
            for (id in ids) {
                val ix = messages.indexOfFirst { it.id == id }
                if (ix >= 0 && !messages[ix].deleted) messages[ix] = messages[ix].gone()
                if (VoicePlayer.playingId == id) VoicePlayer.stop()
            }
            delSince = maxOf(delSince, newest)
        }
        val raw = Social.chatMessages(friend.uid, lastAt)
        if (lastAt == 0L && raw.size < 50) hasMore = false
        val fresh = raw.filter { m -> messages.none { it.id == m.id } }
        if (fresh.isEmpty()) return false
        if (group != null) {
            val miss = (fresh.flatMap { listOf(it.from, it.replyFrom) } + group.members).filter { it.isNotEmpty() && it !in profs }.distinct()
            if (miss.isNotEmpty()) runCatching { profs = profs + Social.profilesOf(miss) }
        }
        messages.addAll(fresh)
        lastAt = maxOf(lastAt, fresh.maxOf { it.at })
        Social.markRead(friend.uid, lastAt)
        return true
    }

    LaunchedEffect(Unit) {
        var idle = 0
        while (true) {
            if (!AppVisibility.foreground || chessId != null || polyId != null || playingWordle) { delay(3_000); continue } // sin mirar con la app en segundo plano ni con un juego abierto encima
            var changed = false
            try { changed = load() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
            idle = if (changed) 0 else idle + 1
            delay((4_000L * (1 + minOf(3, idle / 12) * 0.5)).toLong().coerceAtMost(20_000L)) // sin novedades se va espaciando
        }
    }
    LaunchedEffect(Unit) { runCatching { myPhoto = Social.myProfile().photo } }
    // Baja solo al llegar un mensaje nuevo (no al cargar mensajes antiguos).
    LaunchedEffect(messages.lastOrNull()?.id) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }

    // Al llegar arriba del todo se cargan los 50 mensajes anteriores: el historial no tiene límite.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first == 0 && messages.isNotEmpty() && hasMore && !loadingOlder) {
                loadingOlder = true
                try {
                    val raw = Social.chatOlder(friend.uid, messages.first().at)
                    hasMore = raw.size >= 50
                    val older = raw.filter { m -> messages.none { it.id == m.id } }
                    if (older.isNotEmpty()) {
                        if (group != null) {
                            val miss = older.flatMap { listOf(it.from, it.replyFrom) }.filter { it.isNotEmpty() && it !in profs }.distinct()
                            if (miss.isNotEmpty()) runCatching { profs = profs + Social.profilesOf(miss) }
                        }
                        messages.addAll(0, older)
                        listState.scrollToItem(older.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // sin red: se reintenta al volver a subir
                } finally {
                    loadingOlder = false
                }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        val quoted = replyTo
        replyTo = null
        scope.launch {
            try {
                Social.sendMessage(friend.uid, text, quoted, quoted?.let { previewOf(it) }.orEmpty())
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                input = text
                Toast.makeText(ctx, e.message ?: "No se pudo enviar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───── fotos ─────
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { encodeChatPhoto(ctx, uri) }
                Social.sendPhoto(friend.uid, jpeg)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "No se pudo enviar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───── audios ─────
    fun finishRecording(sendIt: Boolean) {
        recording = false
        val rec = VoiceRecorder.stop() ?: return
        if (!sendIt) {
            rec.first.delete()
            return
        }
        scope.launch {
            try {
                Social.sendAudio(friend.uid, rec.first, rec.second)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "No se pudo enviar", Toast.LENGTH_SHORT).show()
            } finally {
                rec.first.delete()
            }
        }
    }
    fun startRecording() {
        if (VoiceRecorder.start(ctx)) recording = true else Toast.makeText(ctx, "No se pudo grabar", Toast.LENGTH_SHORT).show()
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else Toast.makeText(ctx, "Sin permiso de micrófono", Toast.LENGTH_SHORT).show()
    }
    fun micClick() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    val bars = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording) {
        bars.clear()
        while (recording) {
            bars.add(VoiceRecorder.level())
            if (bars.size > 70) bars.removeAt(0)
            recMs = VoiceRecorder.elapsed()
            if (recMs >= VoiceRecorder.MAX_MS) {
                finishRecording(true)
                break
            }
            delay(70)
        }
    }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop(); VoiceRecorder.cancel() } }

    if (playingWordle) {
        WordleScreen(friend) { playingWordle = false; scope.launch { runCatching { load() } } }
        return
    }
    chessId?.let { id ->
        ChessScreen(friend, id) { chessId = null; scope.launch { runCatching { load() } } }
        return
    }
    menuMsg?.let { m ->
        AlertDialog(
            onDismissRequest = { menuMsg = null },
            title = null,
            text = {
                Column {
                    TextButton(onClick = { replyTo = m; menuMsg = null }, modifier = Modifier.fillMaxWidth()) { Text("Responder", fontSize = 17.sp) }
                    if (m.mine) TextButton(
                        onClick = {
                            menuMsg = null
                            scope.launch {
                                try {
                                    Social.deleteMessage(friend.uid, m)
                                    val ix = messages.indexOfFirst { it.id == m.id }
                                    if (ix >= 0) messages[ix] = messages[ix].gone()
                                    if (VoicePlayer.playingId == m.id) VoicePlayer.stop()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, e.message ?: "No se pudo borrar", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Borrar", fontSize = 17.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { menuMsg = null }) { Text("Cancelar") } },
        )
    }
    polyId?.let { id ->
        EvapolyScreen(id) { polyId = null; scope.launch { runCatching { load() } } }
        return
    }
    if (showGames) MiniGamesDialog(
        onWordle = { showGames = false; playingWordle = true },
        onChess = { showGames = false; pickingTime = true },
        onPoly = {
            showGames = false
            scope.launch {
                try {
                    val id = Social.createPoly(friend.uid)
                    load()
                    polyId = id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(ctx, e.message ?: "No se pudo crear la sala", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDismiss = { showGames = false },
    )
    if (pickingTime) {
        ChessTimeDialog(
            onPick = { mins ->
                pickingTime = false
                scope.launch {
                    try {
                        val id = Social.createChess(friend.uid, mins)
                        load()
                        chessId = id
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(ctx, e.message ?: "No se pudo crear la partida", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { pickingTime = false },
        )
    }

    val sg = shownGroup
    if (showInfo && sg != null) GroupInfoDialog(sg, onChanged = { shownGroup = it }, onGone = { showInfo = false; onClose() }, onDismiss = { showInfo = false })

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            if (sg != null) {
                // la cabecera de un grupo (foto, nombre y miembros) abre su información
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { showInfo = true }.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(sg.name, sg.photo, 38)
                    Spacer(Modifier.padding(start = 12.dp))
                    Column {
                        Text(sg.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface, maxLines = 1)
                        Text("${sg.members.size} miembro${if (sg.members.size == 1) "" else "s"} · toca para ver el grupo", fontSize = 12.sp, color = OnSurfaceDim, maxLines = 1)
                    }
                }
            } else {
                Avatar(friend.username, friend.photo, 38)
                Spacer(Modifier.padding(start = 12.dp))
                Text(friend.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 14.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    // La foto sale junto al último mensaje de cada racha de la misma persona; en los demás queda el hueco.
                    val showAvatar = messages.getOrNull(index + 1)?.from != m.from
                    @Composable
                    fun Photo() {
                        if (showAvatar) {
                            if (group != null && !m.mine) Avatar(profs[m.from]?.username ?: "?", profs[m.from]?.photo ?: "", 32)
                            else Avatar(if (m.mine) myName else friend.username, if (m.mine) myPhoto else friend.photo, 32)
                        } else Spacer(Modifier.width(32.dp))
                    }
                    // deslizar un mensaje hacia la derecha para responderle, como en WhatsApp
                    var dragX by remember(m.id) { mutableFloatStateOf(0f) }
                    val maxPx = with(density) { 96.dp.toPx() }
                    val trigger = maxPx * 0.6f
                    val pull = (dragX / trigger).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxWidth().pointerInput(m.id, m.deleted) {
                            var crossed = false
                            detectHorizontalDragGestures(
                                onDragStart = { crossed = false },
                                onDragEnd = {
                                    if (dragX >= trigger && !m.deleted) replyTo = m
                                    scope.launch { androidx.compose.animation.core.animate(dragX, 0f, animationSpec = androidx.compose.animation.core.tween(180)) { v, _ -> dragX = v } }
                                },
                                onDragCancel = { scope.launch { androidx.compose.animation.core.animate(dragX, 0f, animationSpec = androidx.compose.animation.core.tween(180)) { v, _ -> dragX = v } } },
                                onHorizontalDrag = { _, dx ->
                                    if (!m.deleted) {
                                        dragX = (dragX + dx).coerceIn(0f, maxPx)
                                        if (!crossed && dragX >= trigger) { crossed = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    }
                                },
                            )
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Reply, null, tint = Accent,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(26.dp)
                                .graphicsLayer { alpha = pull; scaleX = 0.5f + 0.5f * pull; scaleY = 0.5f + 0.5f * pull },
                        )
                    Row(
                        Modifier.fillMaxWidth().offset { IntOffset(dragX.toInt(), 0) },
                        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        if (!m.mine) { Photo(); Spacer(Modifier.width(8.dp)) }
                        Column(
                            Modifier.widthIn(max = 440.dp)
                                .background(if (m.mine) Accent else Surface2, RoundedCornerShape(18.dp))
                                .combinedClickable(onClick = {}, onLongClick = { if (!m.deleted) menuMsg = m })
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            if (group != null && !m.mine && !m.deleted) Text(who(m.from), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
                            if (m.deleted) Text("Mensaje borrado", fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = (if (m.mine) OnAccent else OnSurface).copy(alpha = 0.65f))
                            else if (m.replyText.isNotEmpty()) {
                                val fg = if (m.mine) OnAccent else OnSurface
                                Column(Modifier.padding(bottom = 6.dp).background(fg.copy(alpha = 0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    Text(who(m.replyFrom), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = fg.copy(alpha = 0.85f))
                                    Text(m.replyText, fontSize = 13.sp, maxLines = 2, color = fg.copy(alpha = 0.85f))
                                }
                            }
                            if (m.deleted) { /* solo el aviso */ }
                            else if (m.kind == "image") PhotoRow(m, friend.uid)
                            else if (m.kind == "audio") AudioRow(m, friend.uid, if (m.mine) OnAccent else OnSurface)
                            else if (m.kind == "poly") PolyCard(m, if (m.mine) OnAccent else OnSurface) { polyId = it }
                            else if (m.kind == "chess") ChessCard(m, friend, if (m.mine) OnAccent else OnSurface, nameOf = { u -> if (group != null) profs[u]?.username ?: "…" else friend.username }) { chessId = m.id }
                            else Text(m.text, fontSize = 16.sp, color = if (m.mine) OnAccent else OnSurface)
                            Text(
                                hour.format(Date(m.at)), fontSize = 10.sp, modifier = Modifier.align(Alignment.End),
                                color = (if (m.mine) OnAccent else OnSurfaceDim).copy(alpha = 0.7f),
                            )
                        }
                        if (m.mine) { Spacer(Modifier.width(8.dp)); Photo() }
                    }
                    }
                }
            }
        }

        replyTo?.let { r ->
            Row(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 10.dp)
                    .background(Surface2, RoundedCornerShape(12.dp)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(who(r.from), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Text(previewOf(r), fontSize = 13.sp, maxLines = 1, color = OnSurfaceDim)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Default.Close, "Cancelar respuesta", tint = OnSurfaceDim) }
            }
        }
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                IconButton(onClick = { VoiceRecorder.cancel(); recording = false }) { Icon(Icons.Default.Delete, "Cancelar", tint = OnSurfaceDim) }
                Box(Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
                Spacer(Modifier.width(8.dp))
                // onda en directo: cada barra es el volumen de tu voz en ese momento
                androidx.compose.foundation.Canvas(Modifier.weight(1f).height(36.dp)) {
                    val w = 3.dp.toPx(); val gap = 2.dp.toPx(); val maxBars = (size.width / (w + gap)).toInt()
                    val shown = bars.takeLast(maxBars)
                    shown.forEachIndexed { i, v ->
                        val hgt = maxOf(3.dp.toPx(), v * size.height)
                        val x = size.width - (shown.size - i) * (w + gap)
                        drawRoundRect(Accent.copy(alpha = 0.35f + 0.65f * v), androidx.compose.ui.geometry.Offset(x, (size.height - hgt) / 2), androidx.compose.ui.geometry.Size(w, hgt), androidx.compose.ui.geometry.CornerRadius(w / 2))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(mmss(recMs), color = OnSurface, fontSize = 15.sp)
                IconButton(onClick = { finishRecording(true) }) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar audio", tint = Accent) }
            } else {
                IconButton(onClick = { showGames = true }) { Icon(Icons.Default.SportsEsports, "Minijuegos", tint = OnSurface) }
                IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AttachFile, "Foto", tint = OnSurface) }
                OutlinedTextField(
                    value = input, onValueChange = { input = it.take(5000) }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensaje") }, maxLines = 4, shape = RoundedCornerShape(22.dp),
                )
                if (input.isNotBlank()) {
                    IconButton(onClick = ::send) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Accent) }
                } else {
                    IconButton(onClick = ::micClick) { Icon(Icons.Default.Mic, "Grabar audio", tint = Accent) }
                }
            }
        }
    }
}

/** Extracto de un mensaje para citarlo en una respuesta. */
private fun previewOf(m: Social.ChatMessage): String = when (m.kind) {
    "image" -> "Foto"
    "audio" -> "Audio"
    "chess" -> "Evadrez"
    else -> m.text.take(100)
}

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Un audio dentro de una burbuja: botón de reproducir/pausa, barra de progreso y duración. */
@Composable
private fun AudioRow(m: Social.ChatMessage, friendUid: String, fg: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playing = VoicePlayer.playingId == m.id
    LaunchedEffect(playing) {
        while (playing) {
            VoicePlayer.tick()
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            scope.launch {
                try {
                    VoicePlayer.toggle(Social.audioFile(ctx, friendUid, m.id), m.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(ctx, e.message ?: "No se pudo reproducir", Toast.LENGTH_SHORT).show()
                }
            }
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pausar" else "Reproducir", tint = fg) }
        Column {
            LinearProgressIndicator(
                progress = { if (playing && m.dur > 0) (VoicePlayer.position / m.dur.toFloat()).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.width(120.dp), color = fg, trackColor = fg.copy(alpha = 0.25f),
            )
            Text(mmss(if (playing) VoicePlayer.position else m.dur), fontSize = 11.sp, color = fg.copy(alpha = 0.8f), modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Foto elegida → JPEG con el lado largo de 1280 px como mucho, y comprimido hasta que quepa en un documento (< 700.000 caracteres en base64). */
private fun encodeChatPhoto(ctx: android.content.Context, uri: Uri): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) throw Exception("Solo se pueden enviar fotos")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
    var bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: throw Exception("Solo se pueden enviar fotos")
    // la orientación de la cámara (EXIF)
    val rotation = runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
    }.getOrDefault(0f)
    if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation) }, true)
    val long = maxOf(bmp.width, bmp.height)
    if (long > 1280) bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 1280 / long, bmp.height * 1280 / long, true)
    var quality = 82
    while (true) {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        if (bytes.size * 4 / 3 < 650_000) return bytes
        if (quality > 50) quality -= 10 else bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 4 / 5, bmp.height * 4 / 5, true)
    }
}

/** Una foto dentro de la burbuja: se descarga al aparecer y al tocarla se ve a pantalla completa. */
@Composable
private fun PhotoRow(m: Social.ChatMessage, friendUid: String) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, m.id) {
        value = try {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(Social.photoFile(ctx, friendUid, m.id).path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val b = bmp
    if (b == null) {
        Box(Modifier.size(width = 240.dp, height = 160.dp).background(Color(0x22808080), RoundedCornerShape(14.dp)))
    } else {
        val ratio = (b.width.toFloat() / b.height).coerceIn(0.6f, 1.8f)
        Image(
            b.asImageBitmap(), "Foto", contentScale = ContentScale.Crop,
            modifier = Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).clickable { open = true },
        )
        if (open) {
            Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { open = false }, contentAlignment = Alignment.Center) {
                    Image(b.asImageBitmap(), "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
