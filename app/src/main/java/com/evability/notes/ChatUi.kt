package com.evability.notes

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

/** Chat con un amigo: los mensajes se ven en cuanto llegan (se consultan cada 3 s mientras la pantalla está abierta). */
@Composable
fun ChatScreen(friend: Social.Profile, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Social.ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var lastAt by remember { mutableLongStateOf(0L) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var showGames by remember { mutableStateOf(false) }
    var playingWordle by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recMs by remember { mutableLongStateOf(0L) }
    var myPhoto by remember { mutableStateOf("") }
    val myName = Social.session?.username ?: ""
    val listState = rememberLazyListState()
    val hour = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    suspend fun load() {
        val raw = Social.chatMessages(friend.uid, lastAt)
        if (lastAt == 0L && raw.size < 50) hasMore = false
        val fresh = raw.filter { m -> messages.none { it.id == m.id } }
        if (fresh.isEmpty()) return
        messages.addAll(fresh)
        lastAt = maxOf(lastAt, fresh.maxOf { it.at })
        Social.markRead(friend.uid, lastAt)
    }

    LaunchedEffect(Unit) {
        while (true) {
            try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
            delay(3_000)
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
        scope.launch {
            try {
                Social.sendMessage(friend.uid, text)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                input = text
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
    LaunchedEffect(recording) {
        while (recording) {
            recMs = VoiceRecorder.elapsed()
            if (recMs >= VoiceRecorder.MAX_MS) {
                finishRecording(true)
                break
            }
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { VoicePlayer.stop(); VoiceRecorder.cancel() } }

    if (playingWordle) {
        WordleScreen(friend) { playingWordle = false; scope.launch { runCatching { load() } } }
        return
    }
    if (showGames) MiniGamesDialog(onWordle = { showGames = false; playingWordle = true }, onDismiss = { showGames = false })

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Avatar(friend.username, friend.photo, 38)
            Spacer(Modifier.padding(start = 12.dp))
            Text(friend.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 14.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    // La foto sale junto al último mensaje de cada racha de la misma persona; en los demás queda el hueco.
                    val showAvatar = messages.getOrNull(index + 1)?.mine != m.mine
                    @Composable
                    fun Photo() {
                        if (showAvatar) Avatar(if (m.mine) myName else friend.username, if (m.mine) myPhoto else friend.photo, 32)
                        else Spacer(Modifier.width(32.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        if (!m.mine) { Photo(); Spacer(Modifier.width(8.dp)) }
                        Column(
                            Modifier.widthIn(max = 440.dp)
                                .background(if (m.mine) Accent else Surface2, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            if (m.kind == "audio") AudioRow(m, friend.uid, if (m.mine) OnAccent else OnSurface)
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

        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recording) {
                IconButton(onClick = { VoiceRecorder.cancel(); recording = false }) { Icon(Icons.Default.Delete, "Cancelar", tint = OnSurfaceDim) }
                Box(Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(mmss(recMs), color = OnSurface, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { finishRecording(true) }) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar audio", tint = Accent) }
            } else {
                IconButton(onClick = { showGames = true }) { Icon(Icons.Default.AttachFile, "Minijuegos", tint = OnSurface) }
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
