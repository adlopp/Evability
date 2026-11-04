package com.evability.notes

import android.graphics.Bitmap
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
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

/** Ancho máximo de los campos y botones del formulario de cuenta. */
private val FormWidth = 360.dp

/** Ancho máximo de la lista de amigos. */
private val PanelWidth = 560.dp

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
                try { Social.heartbeat(Pomodoro.phase == PomoPhase.STUDY || SimpleCounter.running) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
                delay(30_000)
            }
        }
        launch {
            while (true) {
                try { Social.checkPokes() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red */ }
                try { Social.checkShares() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* se reintenta */ }
                try { Social.checkChats() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* se reintenta */ }
                delay(60_000)
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    val appContext = LocalContext.current.applicationContext
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Social.hearts.forEach { heart ->
            androidx.compose.runtime.key(heart.id) {
                val t = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    HeartSound.play(appContext)
                    t.animateTo(1f, tween(3000, easing = LinearEasing))
                    Social.hearts.remove(heart)
                }
                val pulse = 0.7f + 0.5f * kotlin.math.sin(t.value * 9f).let { 0.5f + it * 0.5f }
                Text(
                    "❤",
                    fontSize = 72.sp,
                    color = Accent,
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

/** La canción del logo («tiki, tiki, tiki») suena entera cada vez que te llega un corazón. */
private object HeartSound {
    private var player: android.media.MediaPlayer? = null

    fun play(ctx: android.content.Context) {
        runCatching {
            player?.release()
            player = android.media.MediaPlayer.create(ctx, R.raw.easter_egg)?.also { p ->
                p.setOnCompletionListener { it.release(); if (player === it) player = null }
                p.start()
            }
        }
    }
}

/** «Desconectado hace 5 min»: el último latido de presencia marca cuándo se fue. */
private fun offlineText(lastSeen: Long, now: Long): String {
    if (lastSeen <= 0) return "Desconectado"
    val min = ((now - lastSeen) / 60_000).coerceAtLeast(1)
    return "Desconectado hace " + when {
        min < 60 -> "$min min"
        min < 24 * 60 -> "${min / 60} h"
        min < 2 * 24 * 60 -> "1 día"
        else -> "${min / (24 * 60)} días"
    }
}

private fun decodePhoto(photo: String): Bitmap? =
    if (photo.isEmpty()) null
    else runCatching { Base64.decode(photo, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

/** Foto de perfil redonda. `onTap` = toque; `onHold(true/false)` = empieza/termina una pulsación larga (para ampliarla). */
@Composable
internal fun Avatar(
    name: String, photo: String, size: Int = 46, online: Boolean? = null,
    onTap: (() -> Unit)? = null, onHold: ((Boolean) -> Unit)? = null,
) {
    val bmp = remember(photo) { decodePhoto(photo) }
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val gestures = if (onTap != null || onHold != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = { tryAwaitRelease(); hold?.invoke(false) },
                onLongPress = { hold?.invoke(true) },
                onTap = { tap?.invoke() },
            )
        }
    } else Modifier
    Box(Modifier.size(size.dp).then(gestures)) {
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

/** Foto ampliada en el centro de la pantalla, mientras se mantiene pulsado el perfil. Primero la miniatura y, al llegar, la grande. */
@Composable
private fun PhotoZoom(target: ZoomTarget) {
    var photo by remember(target) { mutableStateOf(target.photo) }
    LaunchedEffect(target) { Social.fullPhoto(target.uid, target.photo)?.let { photo = it } }
    val bmp = remember(photo) { decodePhoto(photo) }
    val s = remember { Animatable(0.55f) }
    LaunchedEffect(Unit) { s.animateTo(1f, tween(180)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        val side = minOf(280.dp, maxHeight * 0.7f, maxWidth * 0.85f)
        Box(
            Modifier.size(side).graphicsLayer { scaleX = s.value; scaleY = s.value }
                .clip(CircleShape).background(Surface2).border(3.dp, Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            else Text(target.name.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 110.sp)
        }
    }
}

/** Abre la foto elegida (reducida para no gastar memoria) para poder recortarla. */
private fun loadForCrop(ctx: android.content.Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) sample *= 2
    return ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
}

/** Recorte cuadrado → miniatura de 256 px (para las listas, < 28.000 caracteres) y foto grande de hasta 800 px (< 140.000). */
private fun encodePhoto(square: Bitmap): Pair<String, String> {
    fun jpeg(size: Int, startQuality: Int, maxChars: Int): String {
        val small = Bitmap.createScaledBitmap(square, size, size, true)
        var quality = startQuality
        while (true) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (text.length < maxChars || quality <= 40) return text
            quality -= 8
        }
    }
    return jpeg(256, 82, 28_000) to jpeg(minOf(800, square.width), 88, 140_000)
}

/** Recortar la foto: se arrastra y se pellizca (o se usa el deslizador) para encuadrarla dentro del círculo. */
@Composable
private fun CropDialog(src: Bitmap, onCancel: () -> Unit, onApply: (Bitmap) -> Unit) {
    val side = 280.dp
    val px = with(LocalDensity.current) { side.toPx() }
    val base = px / minOf(src.width, src.height)
    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    val image = remember(src) { src.asImageBitmap() }
    fun clamp(o: Offset, z: Float): Offset {
        val mx = maxOf(0f, (src.width * base * z - px) / 2)
        val my = maxOf(0f, (src.height * base * z - px) / 2)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.background(Surface1, RoundedCornerShape(24.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Recorta tu foto", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
            Spacer(Modifier.height(4.dp))
            Text("Arrastra y pellizca para encuadrarla", fontSize = 12.sp, color = OnSurfaceDim)
            Spacer(Modifier.height(14.dp))
            Canvas(
                Modifier.size(side).clip(RoundedCornerShape(12.dp)).background(Color.Black).pointerInput(Unit) {
                    detectTransformGestures { _, pan, gz, _ ->
                        zoom = (zoom * gz).coerceIn(1f, 6f)
                        off = clamp(off + pan, zoom)
                    }
                },
            ) {
                val w = src.width * base * zoom
                val h = src.height * base * zoom
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - w) / 2 + off.x).roundToInt(), ((size.height - h) / 2 + off.y).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                )
                val r = size.minDimension / 2 - 2.dp.toPx()
                val ring = Path().apply {
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addOval(Rect(center, r))
                    fillType = PathFillType.EvenOdd
                }
                drawPath(ring, Color(0xAA000000))
                drawCircle(Accent, r, style = Stroke(2.dp.toPx()))
            }
            Slider(
                value = zoom, onValueChange = { zoom = it; off = clamp(off, it) }, valueRange = 1f..6f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                modifier = Modifier.width(side),
            )
            Row(Modifier.width(side), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancelar") }
                TextButton(onClick = {
                    val sc = base * zoom
                    val srcSide = px / sc
                    val cx = src.width / 2f - off.x / sc
                    val cy = src.height / 2f - off.y / sc
                    val l = (cx - srcSide / 2).coerceIn(0f, src.width - srcSide).roundToInt()
                    val t = (cy - srcSide / 2).coerceIn(0f, src.height - srcSide).roundToInt()
                    val n = srcSide.roundToInt().coerceAtMost(minOf(src.width - l, src.height - t)).coerceAtLeast(1)
                    onApply(Bitmap.createBitmap(src, l, t, n, n))
                }) { Text("Aplicar", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private class ZoomTarget(val name: String, val photo: String, val uid: String)

@Composable
fun SocialScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    SocialContent(onClose)
}

@Composable
private fun SocialContent(onClose: () -> Unit) {
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
                        ShareJob.schedule(ctx)
                        if (Prefs.quickAlerts) FriendsWatch.start(ctx)
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
    var cropSrc by remember { mutableStateOf<Bitmap?>(null) }
    var zoomed by remember { mutableStateOf<ZoomTarget?>(null) }
    var chatWith by remember { mutableStateOf<Social.Profile?>(null) }
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
            delay(45_000)
            if (AppVisibility.foreground) refresh++
        }
    }
    LaunchedEffect(query) {
        if (query.trim().isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        try { results = Social.search(query) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Sin conexión") }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            try { cropSrc = withContext(Dispatchers.IO) { loadForCrop(ctx, uri) } } catch (e: Exception) { toast("No se pudo abrir la foto") }
        }
    }
    cropSrc?.let { src ->
        CropDialog(
            src, onCancel = { cropSrc = null },
            onApply = { square ->
                cropSrc = null
                scope.launch {
                    try {
                        val (thumb, full) = withContext(Dispatchers.IO) { encodePhoto(square) }
                        Social.setPhoto(thumb, full)
                        myPhoto = thumb
                    } catch (e: Exception) { toast(e.message ?: "No se pudo cambiar la foto") }
                }
            },
        )
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = PanelWidth).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp)) {
        // Mi perfil
        Row(
            Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                me.username, myPhoto, 60,
                onTap = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onHold = { held -> zoomed = if (held) ZoomTarget(me.username, myPhoto, me.uid) else null },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                Text("Toca la foto para cambiarla · mantenla pulsada para verla grande", fontSize = 12.sp, color = OnSurfaceDim)
            }
            TextButton(onClick = { Social.signOut(); ShareJob.cancel(ctx); FriendsWatch.stop(ctx) }) { Text("Salir") }
        }

        Spacer(Modifier.height(10.dp))
        var quick by remember { mutableStateOf(Prefs.quickAlerts) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Avisarme al momento", fontSize = 15.sp, color = OnSurface, fontWeight = FontWeight.Medium)
                Text(
                    "Si un amigo te envía un documento, te llega una notificación aunque la app esté cerrada. Deja un aviso fijo en la barra y gasta algo más de batería.",
                    fontSize = 12.sp, color = OnSurfaceDim,
                )
            }
            androidx.compose.material3.Switch(
                checked = quick,
                onCheckedChange = {
                    quick = it
                    Prefs.quickAlerts = it
                    if (it) FriendsWatch.start(ctx) else FriendsWatch.stop(ctx)
                },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Buscar personas por nombre de usuario") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
        )
        results.forEach { p ->
            val isFriend = o?.friends?.any { it.profile.uid == p.uid } == true
            val pending = o?.outgoing?.contains(p.uid) == true
            val incoming = o?.incoming?.any { it.uid == p.uid } == true
            PersonRow(p, onHold = { held -> zoomed = if (held) ZoomTarget(p.username, p.photo, p.uid) else null }) {
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
                PersonRow(p, onHold = { held -> zoomed = if (held) ZoomTarget(p.username, p.photo, p.uid) else null }) {
                    IconButton(onClick = { act { Social.accept(p.uid) } }) { Icon(Icons.Default.Check, "Aceptar", tint = Accent) }
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
                    Avatar(f.profile.username, f.profile.photo, 46, online, onHold = { held -> zoomed = if (held) ZoomTarget(f.profile.username, f.profile.photo, f.profile.uid) else null })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.profile.username, fontSize = 17.sp, color = OnSurface, fontWeight = FontWeight.Medium)
                        Text(if (online) (if (f.studying) "Estudiando" else "En línea") else offlineText(f.lastSeen, now), fontSize = 12.sp, color = if (online) Accent else OnSurfaceDim)
                    }
                    if (wait > 0) {
                        val secs = (wait / 1000).toInt()
                        Text("%d:%02d".format(secs / 60, secs % 60), color = OnSurfaceDim, fontSize = 13.sp)
                    }
                    Box {
                        IconButton(onClick = { chatWith = f.profile }) { Icon(Icons.Default.ChatBubbleOutline, "Chat", tint = OnSurface) }
                        if (Social.unread[f.profile.uid] == true) {
                            Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(10.dp).background(Accent, CircleShape))
                        }
                    }
                    IconButton(
                        enabled = wait <= 0,
                        onClick = { act { Social.poke(f.profile.uid); toast("❤ enviado a ${f.profile.username}") } },
                    ) { Icon(Icons.Default.Favorite, "Mandar un corazón", tint = if (wait <= 0) Accent else OnSurfaceDim.copy(alpha = 0.4f)) }
                    IconButton(onClick = { removing = f.profile }) { Icon(Icons.Default.PersonRemove, "Eliminar amigo", tint = OnSurfaceDim) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    zoomed?.let { PhotoZoom(it) }
    chatWith?.let { ChatScreen(it) { chatWith = null } }
    }
}

@Composable
private fun PersonRow(p: Social.Profile, onHold: (Boolean) -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(p.username, p.photo, 42, onHold = onHold)
        Spacer(Modifier.width(12.dp))
        Text(p.username, fontSize = 16.sp, color = OnSurface, modifier = Modifier.weight(1f))
        actions()
    }
}

/** «Enviar a un amigo»: elige a quién mandar el documento (tal cual lo tienes). */
@Composable
fun SendDialog(doc: DocInfo, store: Store, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var friends by remember { mutableStateOf<List<Social.FriendRow>?>(null) }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val signedIn = Social.session != null
    // Se envía el documento tal cual: con tus anotaciones y cambios si los tiene, o el original si no.
    val anns = remember { store.loadAnns(doc.id) }
    val modified = remember { anns.isNotEmpty() || doc.extra > 0 || doc.hidden.isNotEmpty() }

    LaunchedEffect(Unit) {
        if (signedIn) try { friends = Social.overview().friends } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Sin conexión" }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Enviar «${doc.name}»") },
        text = {
            Column {
                when {
                    !signedIn -> Text("Entra primero en Amigos (el botón de las personas de la cabecera) para poder enviar documentos.", color = OnSurfaceDim)
                    friends == null && error.isEmpty() -> Text("Cargando amigos…", color = OnSurfaceDim)
                    friends.orEmpty().isEmpty() && error.isEmpty() -> Text("Todavía no tienes amigos a quienes enviárselo.", color = OnSurfaceDim)
                    else -> friends.orEmpty().forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f.profile.username, f.profile.photo, 40)
                            Spacer(Modifier.width(12.dp))
                            Text(f.profile.username, modifier = Modifier.weight(1f), color = OnSurface)
                            TextButton(
                                enabled = progress == null,
                                onClick = {
                                    scope.launch {
                                        error = ""
                                        progress = 0f
                                        preparing = modified
                                        var temp: java.io.File? = null
                                        try {
                                            val file = if (modified) {
                                                withContext(Dispatchers.IO) {
                                                    java.io.File(ctx.cacheDir, "share-${doc.id}.pdf").also { target ->
                                                        temp = target
                                                        target.outputStream().use { out ->
                                                            PdfDoc(store.pdfFile(doc.id)).use { d ->
                                                                Exporter.export(d, doc.extra, anns, out, doc.extraKinds, doc.hidden, maxPx = 1400)
                                                            }
                                                        }
                                                    }
                                                }
                                            } else store.pdfFile(doc.id)
                                            preparing = false
                                            Social.sendPdf(f.profile.uid, doc.name, file) { progress = it }
                                            Toast.makeText(ctx, "Enviado a ${f.profile.username}", Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } catch (e: Exception) {
                                            error = e.message ?: "No se pudo enviar"
                                            progress = null
                                            preparing = false
                                        } finally {
                                            temp?.delete()
                                        }
                                    }
                                },
                            ) { Text("Enviar") }
                        }
                    }
                }
                progress?.let {
                    Spacer(Modifier.height(10.dp))
                    if (preparing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    Text(if (preparing) "Preparando el documento con tus cambios…" else "Enviando…", fontSize = 12.sp, color = OnSurfaceDim, modifier = Modifier.padding(top = 4.dp))
                }
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFF6B6B), fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (modified) "Se enviará tal cual lo tienes, con tus anotaciones y cambios (máx. 10 MB)."
                    else "Se enviará el documento tal cual (máx. 10 MB).",
                    fontSize = 12.sp, color = OnSurfaceDim,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(enabled = progress == null, onClick = onDismiss) { Text("Cerrar") } },
    )
}
