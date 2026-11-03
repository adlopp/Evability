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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val listState = rememberLazyListState()
    val hour = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    suspend fun load() {
        val fresh = Social.chatMessages(friend.uid, lastAt).filter { m -> messages.none { it.id == m.id } }
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
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }

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
                items(messages, key = { it.id }) { m ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 460.dp)
                                .background(if (m.mine) Accent else Surface2, RoundedCornerShape(18.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(m.text, fontSize = 16.sp, color = if (m.mine) OnAccent else OnSurface)
                            Text(
                                hour.format(Date(m.at)), fontSize = 10.sp, modifier = Modifier.align(Alignment.End),
                                color = (if (m.mine) OnAccent else OnSurfaceDim).copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it.take(500) }, modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje") }, maxLines = 4, shape = RoundedCornerShape(22.dp),
            )
            IconButton(enabled = input.isNotBlank(), onClick = ::send) {
                Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = if (input.isNotBlank()) Accent else OnSurfaceDim)
            }
        }
    }
}
