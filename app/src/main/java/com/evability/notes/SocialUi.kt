package com.evability.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val Online = Color(0xFF4CD964)

/** Ancho máximo de los campos y botones del formulario de cuenta (la mitad de lo que ocupaban en la tablet). */
private val FormWidth = 280.dp

/** Latido de presencia, aviso de toques y corazones flotantes. Va por encima de toda la app mientras haya sesión iniciada. */
@Composable
fun SocialHost() {
    val s = Social.session
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) foreground = true else if (e == Lifecycle.Event.ON_STOP) foreground = false
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(s?.uid, foreground) {
        if (s == null || !foreground) return@LaunchedEffect
        launch {
            while (true) {
                try { Social.heartbeat() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
                delay(30_000)
            }
        }
        launch {
            while (true) {
                try { Social.checkPokes() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red */ }
                delay(12_000)
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Social.hearts.forEach { heart ->
            androidx.compose.runtime.key(heart.id) {
                val t = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    t.animateTo(1f, tween(3000, easing = LinearEasing))
                    Social.hearts.remove(heart)
                }
                val pulse = 0.7f + 0.5f * kotlin.math.sin(t.value * 9f).let { 0.5f + it * 0.5f }
                Text(
                    "❤",
                    fontSize = 72.sp,
                    color = Color(0xFFFF4D79),
                    modifier = Modifier
                        .offset(maxWidth * heart.x - 36.dp, maxHeight * heart.y)
                        .graphicsLayer {
                            translationY = -140.dp.toPx() * t.value
                            alpha = if (t.value < 0.7f) 1f else ((1f - t.value) / 0.3f).coerceIn(0f, 1f)
                            scaleX = pulse; scaleY = pulse
                        },
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String, photo: String, size: Int = 46, online: Boolean? = null) {
    val bmp = remember(photo) {
        if (photo.isEmpty()) null
        else runCatching { Base64.decode(photo, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    }
    Box(Modifier.size(size.dp)) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(Surface2).border(1.dp, Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
        }
        if (online != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size((size * 0.3f).dp)
                    .background(Bg, CircleShape).padding(2.dp)
                    .background(if (online) Online else OnSurfaceDim.copy(alpha = 0.5f), CircleShape),
            )
        }
    }
}

/** Foto elegida → cuadrado de 96×96 en JPEG y base64 (unos 5 KB). */
private fun photoToBase64(ctx: android.content.Context, uri: Uri): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 192 && bounds.outHeight / (sample * 2) >= 192) sample *= 2
    val src = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val small = Bitmap.createScaledBitmap(square, 96, 96, true)
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@Composable
fun SocialScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Amigos", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        }
        if (Social.session == null) LoginForm() else FriendsPanel()
    }
}

@Composable
private fun LoginForm() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Text(if (creating) "Crea tu perfil" else "Entra en tu perfil", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Añade amigos, mira quién está en la app y mándales un ❤ cada 15 minutos.",
            color = OnSurfaceDim, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = FormWidth),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            email, { email = it; error = "" }, singleLine = true, label = { Text("Correo electrónico") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.widthIn(max = FormWidth).fillMaxWidth(),
        )
        if (creating) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                user, { user = it; error = "" }, singleLine = true, label = { Text("Nombre de usuario") },
                supportingText = { Text("Lo ven tus amigos · 3 a 16 letras, números o _") },
                modifier = Modifier.widthIn(max = FormWidth).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it; error = "" }, singleLine = true, label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.widthIn(max = FormWidth).fillMaxWidth(),
        )
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(error, color = Color(0xFFFF6B6B), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = FormWidth))
        }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        if (creating) Social.signUp(email, user, pass) else Social.signIn(email, pass)
                    } catch (e: Exception) {
                        error = e.message ?: "No se pudo completar"
                    }
                    busy = false
                }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = FormWidth).fillMaxWidth().height(48.dp),
        ) { Text(if (busy) "Un momento…" else if (creating) "Crear cuenta" else "Entrar", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        if (!creating) {
            TextButton(onClick = {
                scope.launch {
                    try {
                        Social.resetPassword(email)
                        error = ""
                        Toast.makeText(ctx, "Te hemos enviado un correo para elegir una contraseña nueva (mira también el spam)", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        error = if (email.isBlank()) "Escribe arriba tu correo y vuelve a pulsar" else e.message ?: "No se pudo enviar el correo"
                    }
                }
            }) { Text("Olvidé mi contraseña") }
        }
        TextButton(onClick = { creating = !creating; error = "" }) {
            Text(if (creating) "Ya tengo cuenta" else "No tengo cuenta: crear una")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Tu correo solo sirve para entrar y recuperar la contraseña: nadie lo ve. En la nube se guardan tu nombre de usuario, " +
                "tu foto y tu última conexión. Tus documentos NO se suben.",
            color = OnSurfaceDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = FormWidth),
        )
    }
}

@Composable
private fun FriendsPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Social.session!!
    var overview by remember { mutableStateOf<Social.Overview?>(null) }
    var myPhoto by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Social.Profile>>(emptyList()) }
    var removing by remember { mutableStateOf<Social.Profile?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableLongStateOf(0L) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun act(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo completar") }
            refresh++
        }
    }

    LaunchedEffect(refresh) {
        try { overview = Social.overview() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Sin conexión") }
    }
    LaunchedEffect(Unit) {
        try { myPhoto = Social.myProfile().photo } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            refresh++
        }
    }
    LaunchedEffect(query) {
        if (query.trim().isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        try { results = Social.search(query) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Sin conexión") }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try {
                val b64 = withContext(Dispatchers.IO) { photoToBase64(ctx, uri) }
                Social.setPhoto(b64)
                myPhoto = b64
            } catch (e: Exception) { toast("No se pudo cambiar la foto") }
        }
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Eliminar amigo") },
            text = { Text("¿Dejar de ser amigo de ${p.username}? Dejaréis de veros en línea y de mandaros corazones.") },
            confirmButton = { TextButton(onClick = { removing = null; act { Social.removeFriend(p.uid) } }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }

    val o = overview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Mi perfil
        Row(
            Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Avatar(me.username, myPhoto, 60)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                Text("Toca la foto para cambiarla", fontSize = 12.sp, color = OnSurfaceDim)
            }
            TextButton(onClick = { Social.signOut() }) { Text("Salir") }
        }

        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar personas por nombre de usuario") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
        )
        results.forEach { p ->
            val isFriend = o?.friends?.any { it.profile.uid == p.uid } == true
            val pending = o?.outgoing?.contains(p.uid) == true
            val incoming = o?.incoming?.any { it.uid == p.uid } == true
            PersonRow(p) {
                when {
                    isFriend -> Text("Amigos", color = OnSurfaceDim)
                    pending -> Text("Enviada", color = OnSurfaceDim)
                    incoming -> TextButton(onClick = { act { Social.accept(p.uid) } }) { Text("Aceptar") }
                    else -> IconButton(onClick = { act { Social.request(p.uid); toast("Solicitud enviada a ${p.username}") } }) {
                        Icon(Icons.Default.PersonAdd, "Añadir", tint = Accent)
                    }
                }
            }
        }
        if (query.isNotBlank() && results.isEmpty()) Text("Nadie con ese nombre", color = OnSurfaceDim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

        if (o != null && o.incoming.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Solicitudes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
            o.incoming.forEach { p ->
                PersonRow(p) {
                    IconButton(onClick = { act { Social.accept(p.uid) } }) { Icon(Icons.Default.Check, "Aceptar", tint = Online) }
                    IconButton(onClick = { act { Social.decline(p.uid) } }) { Icon(Icons.Default.Close, "Rechazar", tint = OnSurfaceDim) }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Mis amigos", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
        when {
            o == null -> Text("Cargando…", color = OnSurfaceDim, modifier = Modifier.padding(top = 8.dp))
            o.friends.isEmpty() -> Text("Todavía no tienes amigos. Búscalos arriba por su nombre de usuario.", color = OnSurfaceDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            else -> o.friends.forEach { f ->
                val online = now - f.lastSeen < Social.ONLINE_MS && f.lastSeen > 0
                val wait = f.lastPokeAt + Social.COOLDOWN_MS + 2000 - now
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(f.profile.username, f.profile.photo, 46, online)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.profile.username, fontSize = 17.sp, color = OnSurface, fontWeight = FontWeight.Medium)
                        Text(if (online) "En línea" else "Desconectado", fontSize = 12.sp, color = if (online) Online else OnSurfaceDim)
                    }
                    if (wait > 0) {
                        val secs = (wait / 1000).toInt()
                        Text("%d:%02d".format(secs / 60, secs % 60), color = OnSurfaceDim, fontSize = 13.sp)
                    }
                    IconButton(
                        enabled = wait <= 0,
                        onClick = { act { Social.poke(f.profile.uid); toast("❤ enviado a ${f.profile.username}") } },
                    ) { Icon(Icons.Default.Favorite, "Mandar un corazón", tint = if (wait <= 0) Color(0xFFFF4D79) else OnSurfaceDim.copy(alpha = 0.4f)) }
                    IconButton(onClick = { removing = f.profile }) { Icon(Icons.Default.PersonRemove, "Eliminar amigo", tint = OnSurfaceDim) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PersonRow(p: Social.Profile, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.username, p.photo, 42)
        Spacer(Modifier.width(12.dp))
        Text(p.username, fontSize = 16.sp, color = OnSurface, modifier = Modifier.weight(1f))
        actions()
    }
}
