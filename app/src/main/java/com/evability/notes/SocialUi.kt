package com.evability.notes

import android.graphics.Bitmap
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

/** Ancho máximo de la lista de amigos (la mitad de lo que ocupaba en la tablet). */
private val PanelWidth = 340.dp

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

private fun decodePhoto(photo: String): Bitmap? =
    if (photo.isEmpty()) null
    else runCatching { Base64.decode(photo, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

/** Foto de perfil redonda. `onTap` = toque; `onHold(true/false)` = empieza/termina una pulsación larga (para ampliarla). */
@Composable
private fun Avatar(
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

/** Foto ampliada en el centro de la pantalla, mientras se mantiene pulsado el perfil. */
@Composable
private fun PhotoZoom(name: String, photo: String) {
    val bmp = remember(photo) { decodePhoto(photo) }
    val s = remember { Animatable(0.55f) }
    LaunchedEffect(Unit) { s.animateTo(1f, tween(180)) }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(280.dp).graphicsLayer { scaleX = s.value; scaleY = s.value }
                .clip(CircleShape).background(Surface2).border(3.dp, Accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 110.sp)
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

/** Recorte cuadrado → 192×192 en JPEG y base64 (unos 10 KB; las reglas de Firestore admiten hasta 30.000 caracteres). */
private fun encodePhoto(square: Bitmap): String {
    val small = Bitmap.createScaledBitmap(square, 192, 192, true)
    var quality = 82
    while (true) {
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (text.length < 28_000 || quality <= 40) return text
        quality -= 8
    }
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
    var cropSrc by remember { mutableStateOf<Bitmap?>(null) }
    var zoomed by remember { mutableStateOf<Pair<String, String>?>(null) }
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
                        val b64 = withContext(Dispatchers.IO) { encodePhoto(square) }
                        Social.setPhoto(b64)
                        myPhoto = b64
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
                onHold = { held -> zoomed = if (held) me.username to myPhoto else null },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.username, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                Text("Toca la foto para cambiarla · mantenla pulsada para verla grande", fontSize = 12.sp, color = OnSurfaceDim)
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
            PersonRow(p, onHold = { held -> zoomed = if (held) p.username to p.photo else null }) {
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
                PersonRow(p, onHold = { held -> zoomed = if (held) p.username to p.photo else null }) {
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
                    Avatar(f.profile.username, f.profile.photo, 46, online, onHold = { held -> zoomed = if (held) f.profile.username to f.profile.photo else null })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.profile.username, fontSize = 17.sp, color = OnSurface, fontWeight = FontWeight.Medium)
                        Text(if (online) "En línea" else "Desconectado", fontSize = 12.sp, color = if (online) Accent else OnSurfaceDim)
                    }
                    if (wait > 0) {
                        val secs = (wait / 1000).toInt()
                        Text("%d:%02d".format(secs / 60, secs % 60), color = OnSurfaceDim, fontSize = 13.sp)
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
    zoomed?.let { PhotoZoom(it.first, it.second) }
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
