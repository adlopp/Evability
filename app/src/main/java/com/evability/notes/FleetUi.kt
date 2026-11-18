package com.evability.notes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hundir la flota (2 jugadores): sala, colocar la flota, batalla y resultado. Misma lógica que `fleet.js`; las reglas están en [Fleet].
 * La flota de cada uno vive solo en su dispositivo ([Prefs.fleetOf]) y en la nube va su hash; al terminar se revela para comprobar que
 * no hubo trampa.
 */
private val SeaTop = Color(0xFF0F6AA3)
private val SeaBottom = Color(0xFF083A63)
private val BlueSeat = Color(0xFF1E88E5)
private val RedSeat = Color(0xFFE53935)

/** Sonidos de Hundir la flota, sintetizados (agua, impacto, hundido, disparo, colocar, tu turno, victoria y derrota). */
object FleetSound {
    private const val RATE = 22050

    private fun tone(buf: FloatArray, start: Double, f1: Double, f2: Double, dur: Double, amp: Double, shape: Int = 0) {
        val s0 = (start * RATE).toInt(); val n = (dur * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = f1 * Math.pow(f2 / f1, t)
            phase += 2 * Math.PI * f / RATE
            val env = (if (i < 0.004 * RATE) i / (0.004 * RATE) else 1.0) * Math.exp(-4.5 * t)
            val w = when (shape) { 1 -> if (Math.sin(phase) >= 0) 0.5 else -0.5; 2 -> ((phase / (2 * Math.PI)) % 1.0) * 2 - 1; else -> Math.sin(phase) }
            val idx = s0 + i
            if (idx in buf.indices) buf[idx] += (w * amp * env).toFloat()
        }
    }

    /** Ruido con filtro paso bajo sencillo (`lp` = cuánto suaviza, 0 a 1) y caída suave. */
    private fun noise(buf: FloatArray, start: Double, dur: Double, amp: Double, lp: Double) {
        val s0 = (start * RATE).toInt(); val n = (dur * RATE).toInt()
        val rnd = java.util.Random(7)
        var prev = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            prev += (rnd.nextDouble() * 2 - 1 - prev) * lp
            val idx = s0 + i
            if (idx in buf.indices) buf[idx] += (prev * amp * (1 - t) * (1 - t)).toFloat()
        }
    }

    private fun synth(kind: String): FloatArray = when (kind) {
        "0" -> FloatArray((0.5 * RATE).toInt()).also { noise(it, 0.0, 0.45, 0.7, 0.35); tone(it, 0.0, 240.0, 90.0, 0.3, 0.3) }
        "1" -> FloatArray((0.55 * RATE).toInt()).also { tone(it, 0.0, 160.0, 40.0, 0.45, 0.85); noise(it, 0.0, 0.3, 0.7, 0.2) }
        "shot" -> FloatArray((0.3 * RATE).toInt()).also { noise(it, 0.0, 0.25, 0.3, 0.8); tone(it, 0.0, 320.0, 1000.0, 0.22, 0.15, 2) }
        "place" -> FloatArray((0.12 * RATE).toInt()).also { tone(it, 0.0, 440.0, 300.0, 0.08, 0.2, 1); noise(it, 0.0, 0.06, 0.3, 0.7) }
        "turn" -> FloatArray((0.45 * RATE).toInt()).also { tone(it, 0.0, 660.0, 660.0, 0.14, 0.4); tone(it, 0.12, 880.0, 880.0, 0.28, 0.4) }
        "win" -> FloatArray((1.1 * RATE).toInt()).also { b -> doubleArrayOf(523.0, 659.0, 784.0, 1047.0).forEachIndexed { i, f -> tone(b, i * 0.15, f, f, 0.45, 0.4) } }
        "lose" -> FloatArray((1.3 * RATE).toInt()).also { b -> doubleArrayOf(392.0, 330.0, 262.0, 196.0).forEachIndexed { i, f -> tone(b, i * 0.24, f, f, 0.5, 0.4, 1) } }
        else -> FloatArray((1.1 * RATE).toInt()).also { // hundido: explosión grande con un segundo estallido
            tone(it, 0.0, 120.0, 28.0, 0.8, 0.9); noise(it, 0.0, 0.6, 0.8, 0.15)
            tone(it, 0.25, 80.0, 25.0, 0.7, 0.7); noise(it, 0.25, 0.45, 0.5, 0.12)
        }
    }

    /** `kind`: '0' agua, '1' tocado, '2'–'5' hundido, o "shot", "place", "turn", "win", "lose". */
    fun play(kind: String) {
        runCatching {
            val data = synth(kind).map { (it.coerceIn(-1f, 1f) * 30000).toInt().toShort() }.toShortArray()
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_GAME).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(RATE).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size - 1)
            track.setPlaybackPositionUpdateListener(
                object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: android.media.AudioTrack) { runCatching { t.release() } }
                    override fun onPeriodicNotification(t: android.media.AudioTrack) {}
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            track.play()
        }
    }
}

private class MineFleet(val ships: List<Fleet.Ship>, val f: String, val salt: String) { fun reveal() = "$f:$salt" }

private fun loadMine(roomId: String): MineFleet? {
    val raw = Prefs.fleetOf(roomId)
    val cut = raw.indexOf('|')
    if (cut < 0) return null
    val f = raw.substring(0, cut); val salt = raw.substring(cut + 1)
    val ships = Fleet.parseFleet(f) ?: return null
    return MineFleet(ships, f, salt)
}

// ───── dibujo ─────

/** `tint`: 0 normal, 1 gris (barco ya hundido en la lista), 2 rojo (hundido en el tablero). */
private fun DrawScope.drawShip(i: Int, s: Fleet.Ship, cs: Float, tint: Int, originX: Float = (s.cell % 10) * cs, originY: Float = (s.cell / 10) * cs, alpha: Float = 1f) {
    val vertical = s.dir == 'v'
    fun p(x: Float, y: Float) = if (vertical) Offset(originX + (1f - y) * cs, originY + x * cs) else Offset(originX + x * cs, originY + y * cs)
    fun col(c: Long): Color = Color(c).let { when (tint) { 1 -> lerp(it, Color(0xFF2A2A2A), 0.7f); 2 -> lerp(it, Color(0xFFE53935), 0.7f); else -> it } }.copy(alpha = alpha)
    fun poly(pts: List<Offset>, c: Color) = drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y); close() }, c)
    for (part in Fleet.shipParts(i)) when (part) {
        is Fleet.Poly -> poly(part.pts.map { p(it.first, it.second) }, col(part.color))
        is Fleet.Rect -> poly(listOf(p(part.x, part.y), p(part.x + part.w, part.y), p(part.x + part.w, part.y + part.h), p(part.x, part.y + part.h)), col(part.color))
        is Fleet.Ell -> poly((0 until 28).map { k -> val a = k / 28f * 2f * Math.PI.toFloat(); p(part.cx + part.rx * kotlin.math.cos(a), part.cy + part.ry * kotlin.math.sin(a)) }, col(part.color))
        is Fleet.Line -> drawLine(col(part.color), p(part.x1, part.y1), p(part.x2, part.y2), strokeWidth = part.w * cs, cap = StrokeCap.Round)
    }
}

/** El barco `i` dibujado en horizontal, para las fichas de la bandeja y la lista de barcos que quedan. */
@Composable
private fun ShipIcon(i: Int, cell: Dp, gray: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(cell * Fleet.LENS[i], cell)) { drawShip(i, Fleet.Ship(0, 'h'), size.height, if (gray) 1 else 0, 0f, 0f) }
}

/** Arrastre sobre el tablero (al colocar la flota): `onDown` dice si empieza un gesto en esa casilla; `onMove` y `onUp` llevan la posición en píxeles del tablero. */
private class BoardDrag(
    val onDown: (cell: Int, pos: Offset, cs: Float) -> Boolean,
    val onMove: (pos: Offset, cs: Float) -> Unit,
    val onUp: (moved: Boolean, pos: Offset, cs: Float) -> Unit,
)

/** Tablero de 10 × 10. `marks`: 'w' agua, 'h' tocado, 's' hundido, 'p' disparo sin responder. `sunk` = barcos que salen en rojo. */
@Composable
private fun FleetBoard(
    ships: List<Fleet.Ship?>, sunk: Set<Int>, marks: Map<Int, Char>, bs: Dp, glow: Boolean = false, labels: Boolean = true,
    ghost: Pair<Int, Fleet.Ship>? = null, ghostOk: Boolean = true, faded: Int? = null, drag: BoardDrag? = null,
    onRootPos: ((Offset) -> Unit)? = null, onCell: ((Int) -> Unit)? = null,
) {
    val cell = bs / 10
    val dragState by androidx.compose.runtime.rememberUpdatedState(drag)
    Column {
        if (labels) Row(Modifier.padding(start = 18.dp)) { for (c in 0 until 10) Text(('A' + c).toString(), Modifier.width(cell), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnSurfaceDim, textAlign = TextAlign.Center) }
        Row {
            if (labels) Column(Modifier.width(18.dp)) { for (r in 1..10) Box(Modifier.height(cell).width(18.dp), contentAlignment = Alignment.Center) { Text("$r", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnSurfaceDim) } }
            val shape = RoundedCornerShape(12.dp)
            Canvas(
                Modifier.size(bs).clip(shape).then(if (glow) Modifier.border(3.dp, Accent, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape))
                    .onGloballyPositioned { onRootPos?.invoke(it.positionInRoot()) }
                    .pointerInput(drag != null) {
                        if (drag == null) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val cs = size.width / 10f
                            val c0 = (down.position.y / cs).toInt().coerceIn(0, 9) * 10 + (down.position.x / cs).toInt().coerceIn(0, 9)
                            val d = dragState ?: return@awaitEachGesture
                            if (!d.onDown(c0, down.position, cs)) return@awaitEachGesture
                            down.consume()
                            var moved = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { dragState?.onUp?.invoke(moved, ch.position, cs); ch.consume(); break }
                                if (!moved && (ch.position - down.position).getDistance() > 8.dp.toPx()) moved = true
                                if (moved) { dragState?.onMove?.invoke(ch.position, cs); ch.consume() }
                            }
                        }
                    }
                    .pointerInput(onCell) {
                        detectTapGestures { off ->
                            if (onCell != null) {
                                val cs = size.width / 10f
                                onCell((off.y / cs).toInt().coerceIn(0, 9) * 10 + (off.x / cs).toInt().coerceIn(0, 9))
                            }
                        }
                    },
            ) {
                val cs = size.width / 10f
                drawRect(Brush.linearGradient(listOf(SeaTop, SeaBottom), Offset.Zero, Offset(size.width, size.height)))
                for (k in 1 until 10) {
                    drawLine(Color.White.copy(alpha = 0.13f), Offset(k * cs, 0f), Offset(k * cs, size.height), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.13f), Offset(0f, k * cs), Offset(size.width, k * cs), 1.dp.toPx())
                }
                ships.forEachIndexed { i, s -> if (s != null) drawShip(i, s, cs, if (i in sunk) 2 else 0, alpha = if (i == faded) 0.25f else 1f) }
                if (ghost != null) {
                    val (gi, gs) = ghost
                    drawShip(gi, gs, cs, if (ghostOk) 0 else 2, alpha = if (ghostOk) 0.95f else 0.7f)
                    val w = (if (gs.dir == 'h') Fleet.LENS[gi] else 1) * cs; val hh = (if (gs.dir == 'v') Fleet.LENS[gi] else 1) * cs
                    drawRect(if (ghostOk) Color(0xFF43A047) else Color(0xFFE53935), Offset((gs.cell % 10) * cs, (gs.cell / 10) * cs), androidx.compose.ui.geometry.Size(w, hh), style = Stroke(2.dp.toPx()))
                }
                for ((c, m) in marks) {
                    val center = Offset((c % 10 + 0.5f) * cs, (c / 10 + 0.5f) * cs)
                    when (m) {
                        'w' -> { drawCircle(Color.White.copy(alpha = 0.18f), cs * 0.26f, center); drawCircle(Color.White.copy(alpha = 0.6f), cs * 0.14f, center) }
                        'p' -> drawCircle(Color.White, cs * 0.3f, center, style = Stroke(3.dp.toPx()))
                        else -> {
                            drawCircle(Color(0xFFFF6B3D).copy(alpha = 0.45f), cs * 0.46f, center)
                            drawCircle(if (m == 's') Color(0xFFB71C1C) else Color(0xFFE53935), cs * 0.34f, center)
                            val d = cs * 0.14f
                            drawLine(Color.White, center + Offset(-d, -d), center + Offset(d, d), 2.5.dp.toPx(), StrokeCap.Round)
                            drawLine(Color.White, center + Offset(-d, d), center + Offset(d, -d), 2.5.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** Invitación a Hundir la flota en el chat: cómo va la sala y botón para unirse o abrirla. */
@Composable
fun FleetCard(m: Social.ChatMessage, fg: Color, onOpen: (String) -> Unit) {
    val roomId = m.game.ifEmpty { m.id }
    var room by remember(roomId) { mutableStateOf<Social.FRoom?>(null) }
    LaunchedEffect(roomId) {
        while (true) {
            if (AppVisibility.foreground) {
                try { room = Social.fRoom(roomId); if (room?.status == "finished") break } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red o sin acceso */ }
            }
            delay(20_000)
        }
    }
    val r = room
    val inRoom = r?.players?.contains(Social.session?.uid) == true
    val status = when {
        r == null -> ""
        else -> (if (r.status == "lobby") "Sala · ${r.players.size}/2" else if (r.status == "playing") "En juego" else "Terminada") + (if (r.shots > 1) " · ${r.shots} disparos por turno" else "")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(fg.copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ship), null, tint = fg, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Hundir la flota", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = fg)
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = fg.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(14.dp))
        Button(
            onClick = { onOpen(roomId) },
            enabled = !(r != null && !inRoom && (r.status != "lobby" || r.players.size >= 2)),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.18f), contentColor = fg),
        ) { Text(if (r?.status == "lobby" && !inRoom) (if (r.players.size >= 2) "Completa" else "Unirse") else "Abrir") }
    }
}

@Composable
fun FleetScreen(roomId: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Social.session?.uid ?: ""
    var dark by remember { mutableStateOf(Prefs.gameDark) }
    DisposableEffect(dark) {
        val prev = Bg
        if (dark) Bg = Color.Black
        onDispose { Bg = prev }
    }
    var room by remember { mutableStateOf<Social.FRoom?>(null) }
    val names = remember { mutableStateMapOf<String, String>() }
    val photos = remember { mutableStateMapOf<String, String>() }
    var mine by remember { mutableStateOf<MineFleet?>(null) }
    var verifyMsg by remember { mutableStateOf("") }
    var verifyDone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var inviteList by remember { mutableStateOf<List<Social.Profile>?>(null) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    /** Un ciclo: lee la sala y hace lo que le toca a este dispositivo aunque nadie toque nada (responder al rival, revelar la flota). */
    suspend fun pollOnce() {
        val r = Social.fRoom(roomId)
        if (r == null) { toast("La sala se ha cerrado"); onClose(); return }
        val missing = (r.players + r.invited).filter { it !in names }
        if (missing.isNotEmpty()) {
            val p = runCatching { Social.profilesOf(missing) }.getOrDefault(emptyMap())
            for (u in missing) { names[u] = p[u]?.username ?: "?"; photos[u] = p[u]?.photo ?: "" }
        }
        var cur = r
        if (r.status != "lobby") {
            if (mine == null) mine = loadMine(roomId)
            val m = mine
            val opp = r.players.firstOrNull { it != me }
            if (m != null && me in r.players && opp != null) {
                var wrote = false
                if (r.status == "playing" && r.h.containsKey(me) && r.h.containsKey(opp)) {
                    val rp = Fleet.replay(r.players, r.seed, r.again, r.s, r.a, r.shots)
                    if (rp.pending && rp.cur != me) {
                        val mineAns = r.a[me] ?: ""
                        val n = rp.log.count { it.by == rp.cur }
                        if (mineAns.length == n) {
                            val next = mineAns + Fleet.answers(m.ships, Fleet.shotCells(r.s[rp.cur]).take(n + 1))[n]
                            val lost = next.count { it >= '2' } >= Fleet.LENS.size
                            Social.fAnswer(r, next, if (lost) m.reveal() else null)
                            wrote = true
                        }
                    } else if (rp.winner == me) { // he hundido los 5 barcos: la partida termina ya, aunque el rival no tenga la pantalla abierta
                        Social.fFinish(r); wrote = true
                    }
                }
                if (r.status == "finished" && !r.v.containsKey(me)) { Social.fReveal(r, m.reveal()); wrote = true }
                if (wrote) cur = Social.fRoom(roomId) ?: r
                if (cur.status == "finished" && !verifyDone && cur.v.containsKey(opp) && cur.h.containsKey(opp)) {
                    verifyDone = true
                    val why = Fleet.verify(cur.v[opp], cur.h[opp], cur.adj, cur.s[me] ?: "", cur.a[opp] ?: "")
                    val nm = names[opp] ?: "?"
                    verifyMsg = if (why.isEmpty()) "✓ La flota de $nm está verificada: todo fue limpio." else "⚠ $nm ha hecho trampa: $why."
                }
            }
        }
        room = cur
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (AppVisibility.foreground) {
                try { pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
            }
            delay(if (room?.status == "lobby") 3500L else 2200L)
        }
    }

    val r = room
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Bg).statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Hundir la flota", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { dark = !dark; Prefs.gameDark = dark }) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
        }
        val opp = r?.players?.firstOrNull { it != me } ?: ""
        when {
            r == null -> Text("Cargando…", color = OnSurfaceDim, modifier = Modifier.padding(16.dp))
            r.status == "lobby" -> FleetLobby(
                r, me, names, photos,
                onJoin = { scope.launch { try { Social.fJoin(r); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onLeave = { scope.launch { try { Social.fLeave(r); onClose() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onRemove = { scope.launch { runCatching { Social.fRemove(roomId) }; onClose() } },
                onCfg = { adj, again, shots -> scope.launch { try { Social.fCfg(r, adj, again, shots); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onStart = { scope.launch { try { Social.fStart(r, (Math.random() * 2_000_000_000).toInt()); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onInvite = { scope.launch { try { inviteList = Social.overview().friends.map { it.profile } } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
            )
            me !in r.players -> Text("La partida ya ha empezado.", color = OnSurfaceDim, modifier = Modifier.padding(16.dp))
            !r.h.containsKey(me) && r.status == "playing" -> FleetPlace(
                r.adj, busy,
                onReady = { ships ->
                    scope.launch {
                        busy = true
                        try {
                            val f = Fleet.packFleet(ships); val salt = Fleet.newSalt()
                            Prefs.setFleetOf(roomId, "$f|$salt")
                            try { Social.fPlace(r, Fleet.commitOf(f, salt)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Prefs.setFleetOf(roomId, ""); throw e }
                            mine = MineFleet(ships, f, salt)
                            pollOnce()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo guardar") }
                        busy = false
                    }
                },
            )
            !r.h.containsKey(opp) && r.status == "playing" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tu flota está lista. Esperando a que ${names[opp] ?: "?"} coloque la suya…", color = OnSurface, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                FleetBoard(mine?.ships ?: emptyList(), emptySet(), emptyMap(), 300.dp)
                TextButton(onClick = { scope.launch { try { Social.fForfeit(r, mine?.reveal()); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } }) { Text("Abandonar", color = OnSurfaceDim) }
            }
            else -> FleetBattle(
                r, me, names, photos, mine, verifyMsg,
                onShoot = { cell ->
                    if (!busy) scope.launch {
                        busy = true
                        try { Social.fShoot(r, (r.s[me] ?: "") + Fleet.pad2(cell)); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "No se pudo disparar") }
                        busy = false
                    }
                },
                onForfeit = { scope.launch { try { Social.fForfeit(r, mine?.reveal()); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onClose = onClose,
            )
        }
    }

    inviteList?.let { list ->
        val rr = room
        val free = list.filter { f -> rr != null && f.uid !in rr.invited && f.uid !in rr.players }
        AlertDialog(
            onDismissRequest = { inviteList = null }, title = { Text("Invitar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (free.isEmpty() || rr == null || rr.invited.isNotEmpty()) Text("—", color = OnSurfaceDim)
                    else for (f in free) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).clickable {
                                scope.launch {
                                    try {
                                        Social.fInvite(rr, f.uid)
                                        names[f.uid] = f.username; photos[f.uid] = f.photo
                                        pollOnce(); inviteList = null
                                        toast("Invitación enviada")
                                    } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") }
                                }
                            }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(f.username, f.photo, 32)
                            Spacer(Modifier.width(10.dp))
                            Text(f.username, color = OnSurface, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inviteList = null }) { Text("Cerrar") } },
        )
    }
}

/** Sala de espera: asientos y reglas (misma estética que la del Evapoly). */
@Composable
private fun FleetLobby(
    r: Social.FRoom, me: String, names: Map<String, String>, photos: Map<String, String>,
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onCfg: (Boolean, Boolean, Int) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
) {
    val isHost = r.host == me
    val inRoom = me in r.players
    val canJoin = !inRoom && (me in r.invited || r.group.isNotEmpty()) && r.players.size < 2
    val pending = r.invited.filter { it !in r.players }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..1) {
                    val u = r.players.getOrNull(i)
                    val shape = RoundedCornerShape(16.dp)
                    val col = if (i == 0) RedSeat else BlueSeat
                    Row(
                        Modifier.weight(1f).height(54.dp).clip(shape).background(Surface1).border(2.dp, col.copy(alpha = if (u != null) 1f else 0.4f), shape).padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (u != null) {
                            Avatar(names[u] ?: "?", photos[u] ?: "", 34)
                            Spacer(Modifier.width(8.dp))
                            Text(if (u == me) "Tú" else names[u] ?: "?", color = OnSurface, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (u == r.host) { Spacer(Modifier.width(4.dp)); Text("♛", color = Color(0xFFE3A94A), fontSize = 18.sp) }
                        } else {
                            val inv = pending.firstOrNull()
                            Text(if (inv != null) (names[inv] ?: "?") + " …" else "—", color = OnSurfaceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Surface1).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Los barcos pueden tocarse", color = OnSurface)
                        Text("Si no, entre barcos queda siempre una casilla de agua.", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                    Switch(r.adj, { onCfg(it, r.again, r.shots) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Repetir turno al acertar", color = OnSurface)
                        Text("Quien acierta (tocado o hundido) vuelve a disparar sin gastar disparo.", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                    Switch(r.again, { onCfg(r.adj, it, r.shots) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Disparos por turno", color = OnSurface, modifier = Modifier.weight(1f))
                    Text("${r.shots}", color = OnSurface, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in 1..5) {
                        val sel = r.shots == n
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(shape).background(if (sel) Accent.copy(alpha = 0.2f) else Surface2)
                                .border(2.dp, if (sel) Accent else Outline, shape)
                                .then(if (isHost) Modifier.clickable { if (!sel) onCfg(r.adj, r.again, n) } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (sel) Accent else OnSurface.copy(alpha = if (isHost) 1f else 0.6f)) }
                    }
                }
                Text(
                    "Cada uno coloca su flota en secreto: portaaviones (5), acorazado (4), crucero (3), submarino (3) y destructor (2). Gana quien hunde antes toda la flota del rival.",
                    color = OnSurfaceDim, fontSize = 13.sp,
                )
            }
            if (canJoin) PolyButton("Unirse", true, true) { onJoin() }
            if (isHost) {
                if (r.group.isEmpty()) PolyButton("Invitar", false, r.invited.isEmpty() && r.players.size < 2) { onInvite() }
                PolyButton(if (r.players.size < 2) "Esperando rival…" else "Empezar", true, r.players.size >= 2) { onStart() }
                TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cerrar sala", color = Accent) }
            } else if (inRoom) {
                TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Salir", color = Accent) }
            }
        }
    }
}

/** Colocar la flota: se arrastran los barcos con el dedo (de la bandeja al mar, o de un sitio a otro); un toque sobre un barco puesto lo gira. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FleetPlace(adj: Boolean, busy: Boolean, onReady: (List<Fleet.Ship>) -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(List<Fleet.Ship?>(Fleet.LENS.size) { null }) }
    var sel by remember { mutableStateOf<Int?>(0) }
    var dir by remember { mutableStateOf('h') }
    val placed = draft.all { it != null }
    val bs = 320.dp
    var boardRoot by remember { mutableStateOf(Offset.Zero) }
    var boardPx by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    // arrastre en curso: qué barco, por qué casilla del barco se agarra, orientación, de dónde viene (0 = barco puesto, 1 = bandeja, 2 = mar con un barco elegido) y dónde caería
    class Drag(val i: Int, val k: Int, val dir: Char, val mode: Int, val target: Fleet.Ship?, val ok: Boolean)
    var drag by remember { mutableStateOf<Drag?>(null) }
    val noFit = if (adj) "Ahí no cabe" else "Ahí no cabe: entre barcos tiene que quedar una casilla de agua"

    fun snap(d: Drag, pos: Offset, cs: Float): Drag {
        val col = Math.floor((pos.x / cs).toDouble()).toInt(); val row = Math.floor((pos.y / cs).toDouble()).toInt()
        if (col !in 0..9 || row !in 0..9) return Drag(d.i, d.k, d.dir, d.mode, null, false)
        val len = Fleet.LENS[d.i]
        val hc = (if (d.dir == 'h') col - d.k else col).coerceIn(0, if (d.dir == 'h') 10 - len else 9)
        val hr = (if (d.dir == 'v') row - d.k else row).coerceIn(0, if (d.dir == 'v') 10 - len else 9)
        val s = Fleet.Ship(hr * 10 + hc, d.dir)
        return Drag(d.i, d.k, d.dir, d.mode, s, Fleet.canPlace(draft, d.i, s, adj))
    }
    fun nextFree() = draft.indices.firstOrNull { draft[it] == null }
    fun finish(moved: Boolean, d: Drag) {
        drag = null
        if (moved || d.mode == 2) {
            if (d.target != null && d.ok) { draft = draft.toMutableList().also { it[d.i] = d.target }; sel = nextFree(); FleetSound.play("place") }
            else if (d.target != null) Toast.makeText(ctx, noFit, Toast.LENGTH_SHORT).show()
        } else if (d.mode == 0) { // un toque en un barco puesto: lo gira
            val s = draft[d.i] ?: return
            val nd = if (s.dir == 'h') 'v' else 'h'
            var row = s.cell / 10; var col = s.cell % 10
            if (nd == 'h') col = minOf(col, 10 - Fleet.LENS[d.i]) else row = minOf(row, 10 - Fleet.LENS[d.i])
            val ns = Fleet.Ship(row * 10 + col, nd)
            if (Fleet.canPlace(draft, d.i, ns, adj)) draft = draft.toMutableList().also { it[d.i] = ns } else Toast.makeText(ctx, "No se puede girar ahí", Toast.LENGTH_SHORT).show()
        } else { // un toque en la ficha: se elige ese barco (si estaba puesto, se recoge)
            if (draft[d.i] != null) { dir = draft[d.i]!!.dir; draft = draft.toMutableList().also { it[d.i] = null } }
            sel = d.i
        }
    }

    val boardDrag = BoardDrag(
        onDown = { cell, pos, cs ->
            boardPx = cs * 10f
            val at = draft.indices.firstOrNull { i -> draft[i]?.let { Fleet.cellsOf(it, i).contains(cell) } == true }
            val s0 = sel
            val start = if (at != null) Drag(at, Fleet.cellsOf(draft[at]!!, at).indexOf(cell), draft[at]!!.dir, 0, null, false)
            else if (s0 != null) Drag(s0, Fleet.LENS[s0] / 2, dir, 2, null, false) else null
            if (start == null) false else { drag = snap(start, pos, cs); true }
        },
        onMove = { pos, cs -> drag?.let { drag = snap(it, pos, cs) } },
        onUp = { moved, pos, cs -> drag?.let { finish(moved, snap(it, pos, cs)) } },
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when {
                placed -> "Flota lista. Arrastra un barco para moverlo o tócalo para girarlo; pulsa «Listo» cuando quieras."
                sel == null -> "Arrastra un barco de abajo al mar."
                else -> "Arrastra el ${Fleet.NAMES[sel!!].lowercase()} (${Fleet.LENS[sel!!]}) al mar o toca donde quieras ponerlo."
            },
            color = OnSurface, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        FleetBoard(
            draft, emptySet(), emptyMap(), bs, drag = boardDrag, onRootPos = { boardRoot = it },
            ghost = drag?.target?.let { drag!!.i to it }, ghostOk = drag?.ok ?: true, faded = drag?.takeIf { it.mode == 0 && it.target != null }?.i,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in Fleet.LENS.indices) {
                val shape = RoundedCornerShape(14.dp)
                val on = sel == i
                var chipRoot by remember { mutableStateOf(Offset.Zero) }
                Column(
                    Modifier.clip(shape).background(if (on) Accent.copy(alpha = 0.18f) else Surface1).border(2.dp, if (on) Accent else Outline, shape)
                        .onGloballyPositioned { chipRoot = it.positionInRoot() }
                        .pointerInput(i) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val cs = boardPx.takeIf { it > 0f }?.div(10f) ?: with(density) { bs.toPx() / 10f }
                                val start = Drag(i, Fleet.LENS[i] / 2, draft[i]?.dir ?: dir, 1, null, false)
                                var moved = false
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    val inBoard = chipRoot + ch.position - boardRoot
                                    if (!ch.pressed) { finish(moved, snap(drag ?: start, inBoard, cs)); ch.consume(); break }
                                    if (!moved && (ch.position - down.position).getDistance() > 8.dp.toPx()) moved = true
                                    if (moved) { drag = snap(start, inBoard, cs); ch.consume() }
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ShipIcon(i, 24.dp, modifier = Modifier.padding(bottom = 3.dp))
                    Text("${Fleet.NAMES[i]} · ${Fleet.LENS[i]}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (draft[i] != null && !on) OnSurfaceDim else OnSurface)
                }
            }
        }
        Row(Modifier.fillMaxWidth().widthIn(max = 430.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PolyButton(if (dir == 'h') "Girar ↻ (horizontal)" else "Girar ↻ (vertical)", false, sel != null, Modifier.weight(1f), compact = true) { dir = if (dir == 'h') 'v' else 'h' }
            PolyButton("Aleatorio", false, true, Modifier.weight(1f), compact = true) { draft = Fleet.randomFleet(adj); sel = null }
        }
        Row(Modifier.fillMaxWidth().widthIn(max = 430.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { draft = List(Fleet.LENS.size) { null }; sel = 0 }) { Text("Limpiar", color = Accent) }
            PolyButton("Listo", true, placed && !busy, Modifier.weight(1f)) { onReady(draft.map { it!! }) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** La batalla y el resultado: la flota rival en grande a la izquierda y la tuya a la derecha, para verlas a la vez sin hacer scroll. */
@Composable
private fun FleetBattle(
    r: Social.FRoom, me: String, names: Map<String, String>, photos: Map<String, String>, mine: MineFleet?, verifyMsg: String,
    onShoot: (Int) -> Unit, onForfeit: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val opp = r.players.firstOrNull { it != me } ?: ""
    val rp = Fleet.replay(r.players, r.seed, r.again, r.s, r.a, r.shots)
    val finished = r.status == "finished" || rp.winner.isNotEmpty() // con los 5 barcos hundidos ya no se dispara más
    val winner = if (r.ff.isNotEmpty()) (if (r.ff == me) opp else me) else rp.winner
    val nameOpp = names[opp] ?: "?"
    val haptic = LocalHapticFeedback.current
    var banner by remember { mutableStateOf("") }
    var bannerHit by remember { mutableStateOf(false) }
    var lastLog by remember { mutableStateOf(-1) }
    var confirmForfeit by remember { mutableStateOf(false) }

    val eMarks = HashMap<Int, Char>(); val oMarks = HashMap<Int, Char>(); val eSunk = ArrayList<Int>()
    for (e in rp.log) {
        val mk = if (e.res == '0') 'w' else if (e.res == '1') 'h' else 's'
        (if (e.by == me) eMarks else oMarks)[e.cell] = mk
        if (e.by == me && e.res >= '2') eSunk.add(e.res - '0')
    }
    val myShots = Fleet.shotCells(r.s[me])
    if (!finished && rp.cur == me && rp.pending && myShots.isNotEmpty()) eMarks[myShots.last()] = 'p'
    val hitCells = oMarks.filterValues { it != 'w' }.keys
    val mySunk = mine?.ships?.indices?.filter { i -> Fleet.cellsOf(mine.ships[i], i).all { it in hitCells } }?.toSet() ?: emptySet()
    val oppShips = if (finished) r.v[opp]?.let { rv -> Fleet.parseFleet(rv.substringBeforeLast(':')) } else null
    val myTurn = !finished && rp.cur == me && !rp.pending

    // los barcos que he hundido salen enteros y en rojo; sobre ellos no se puede volver a disparar
    val eShips = MutableList<Fleet.Ship?>(Fleet.LENS.size) { null }
    val eRed = HashSet<Int>(); val sunkCells = HashSet<Int>()
    for (sk in Fleet.sunkShips(rp.log, me)) if (sk.idx >= 0) { eShips[sk.idx] = sk.ship; eRed.add(sk.idx); sunkCells.addAll(Fleet.cellsOf(sk.ship, sk.idx)) }

    // aviso y sonido de cada disparo nuevo (el primer vistazo solo toma nota de lo que ya había)
    LaunchedEffect(rp.log.size) {
        if (lastLog < 0) { lastLog = rp.log.size; return@LaunchedEffect }
        if (rp.log.size > lastLog) {
            lastLog = rp.log.size
            val e = rp.log.last()
            val who = if (e.by == me) "Tu disparo" else "Disparo de ${names[e.by] ?: "?"}"
            banner = when {
                e.res == '0' -> "$who: agua"
                e.res == '1' -> "$who: ¡tocado!"
                else -> "$who: ¡hundido! (${Fleet.NAMES[Fleet.LENS.indexOfFirst { it == e.res - '0' }.coerceAtLeast(0)]})"
            }
            bannerHit = e.res != '0'
            FleetSound.play(e.res.toString())
            if (e.res != '0') haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(2400)
            banner = ""
        }
    }
    var wasMine by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(myTurn) { val w = wasMine; wasMine = myTurn; if (w == false && myTurn) FleetSound.play("turn") }
    var endSounded by remember { mutableStateOf(finished) } // si la partida ya estaba terminada al abrirla, no suena
    LaunchedEffect(finished) { if (finished && !endSounded) { endSounded = true; FleetSound.play(if (winner == me) "win" else "lose") } }

    val turnText = if (finished) {
        (if (winner == me) "¡Has ganado! 🎉" else "Gana ${names[winner] ?: "?"}") + (if (r.ff.isNotEmpty()) (if (r.ff == me) " · te has rendido" else " · $nameOpp se ha rendido") else "")
    } else if (rp.cur == me) (if (rp.pending) "Esperando la respuesta de $nameOpp…" else if (r.shots > 1) "¡Tu turno! Dispara (te quedan ${rp.left})" else "¡Tu turno! Dispara al mar enemigo")
    else (if (rp.pending) "$nameOpp ha disparado…" else "Turno de $nameOpp" + (if (r.shots > 1) " · ${rp.left} disparos" else ""))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            turnText, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, fontSize = 15.sp,
            color = if (myTurn) OnAccent else if (finished) Color.White else OnSurface,
            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth()
                .background(if (myTurn) Accent else if (finished) Color(0xFF2E7D6B) else Surface2, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val enemyBs = ((maxWidth - 6.dp) * 0.68f - 18.dp).coerceAtMost(430.dp)
            val ownBs = ((maxWidth - 6.dp) * 0.32f).coerceAtMost(300.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Flota de $nameOpp", fontWeight = FontWeight.Bold, color = OnSurface, fontSize = 14.sp, maxLines = 1)
                    Box(contentAlignment = Alignment.Center) {
                        FleetBoard(
                            oppShips ?: eShips, eRed, eMarks, enemyBs, glow = myTurn,
                            onCell = if (myTurn) { c -> if (c in sunkCells) Toast.makeText(ctx, "Ahí ya has hundido un barco", Toast.LENGTH_SHORT).show() else if (!eMarks.containsKey(c)) onShoot(c) } else null,
                        )
                        if (banner.isNotEmpty()) {
                            Text(
                                banner, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(start = 18.dp).background(if (bannerHit) Color(0xFFE53935) else Color(0xFF2F6FA8), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                    // barcos enemigos que quedan, según lo que he hundido
                    val left = Fleet.LENS.toMutableList().also { l -> eSunk.forEach { s -> l.remove(s) } }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (i in Fleet.LENS.indices) { val alive = left.remove(Fleet.LENS[i]); ShipIcon(i, 10.dp, gray = !alive) }
                    }
                }
                Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tu flota", fontWeight = FontWeight.Bold, color = OnSurface, fontSize = 13.sp)
                    FleetBoard(mine?.ships ?: emptyList(), mySunk, oMarks, ownBs, labels = false)
                }
            }
        }
        if (finished) {
            val shots = rp.log.count { it.by == me }; val hits = rp.log.count { it.by == me && it.res != '0' }
            Column(
                Modifier.widthIn(max = 430.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface1).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$shots disparos · $hits aciertos", fontWeight = FontWeight.Bold, color = OnSurface)
                if (verifyMsg.isNotEmpty()) Text(verifyMsg, color = if (verifyMsg.startsWith("⚠")) Color(0xFFE57373) else OnSurfaceDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                else if (r.ff.isEmpty() && !r.v.containsKey(opp)) Text("Esperando a que $nameOpp muestre su flota…", color = OnSurfaceDim, fontSize = 13.sp)
                PolyButton("Cerrar", true, true) { onClose() }
            }
        } else TextButton(onClick = { confirmForfeit = true }) { Text("Rendirse", color = OnSurfaceDim) }
        Spacer(Modifier.height(12.dp))
    }
    if (confirmForfeit) {
        AlertDialog(
            onDismissRequest = { confirmForfeit = false }, title = { Text("Rendirse") }, text = { Text("Pierdes la partida y gana $nameOpp.") },
            confirmButton = { TextButton(onClick = { confirmForfeit = false; onForfeit() }) { Text("Rendirme") } },
            dismissButton = { TextButton(onClick = { confirmForfeit = false }) { Text("Cancelar") } },
        )
    }
}
