package com.evability.notes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Ink = Color(0xFF1C1B22)

private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

/** Posición de la casilla `i` en el tablero, en % del lado: [izquierda, arriba, ancho, alto]. */
private fun cellOf(i: Int): FloatArray {
    val k = 12.5f
    val e = (100f - 2 * k) / 9f
    val row: Int
    val col: Int
    when {
        i <= 10 -> { row = 10; col = 10 - i }
        i <= 20 -> { row = 10 - (i - 10); col = 0 }
        i <= 30 -> { row = 0; col = i - 20 }
        else -> { row = i - 30; col = 10 }
    }
    fun pos(n: Int) = if (n == 0) 0f else if (n == 10) 100f - k else k + (n - 1) * e
    fun len(n: Int) = if (n == 0 || n == 10) k else e
    return floatArrayOf(pos(col), pos(row), len(col), len(row))
}

private fun sideOf(i: Int) = if (i % 10 == 0) "corner" else if (i < 10) "bot" else if (i < 20) "left" else if (i < 30) "top" else "right"

private class DiceView(val d1: Int, val d2: Int, val rolling: Boolean)

/** Ficha dibujada con los trazados de evapoly-tokens.json, dentro de un círculo con el color del jugador. */
@Composable
private fun TokenView(index: Int, size: Dp, ring: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val parts = remember(index) { PolyData.tokens(ctx).let { it[index.coerceIn(0, it.lastIndex)].second } }
    Box(
        modifier.size(size).background(Color.White.copy(alpha = 0.94f), CircleShape).border(size * 0.09f, ring, CircleShape).padding(size * 0.06f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sc = this.size.minDimension / 100f
            withTransform({ scale(sc, sc, pivot = Offset.Zero) }) {
                for (p in parts) {
                    p.fill?.let { drawPath(p.path, it, alpha = p.alpha) }
                    p.stroke?.let { drawPath(p.path, it, alpha = p.alpha, style = Stroke(width = p.width, cap = PolyData.cap, join = PolyData.join)) }
                }
            }
        }
    }
}

/** Icono de data/evapoly-icons.json dibujado en un cuadrado. */
@Composable
private fun PolyIcon(name: String?, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val parts = remember(name) { name?.let { PolyData.icons(ctx)[it] } }
    if (parts == null) return
    Canvas(modifier.size(size)) {
        val sc = this.size.minDimension / 100f
        withTransform({ scale(sc, sc, pivot = Offset.Zero) }) {
            for (p in parts) {
                p.fill?.let { drawPath(p.path, it, alpha = p.alpha) }
                p.stroke?.let { drawPath(p.path, it, alpha = p.alpha, style = Stroke(width = p.width, cap = PolyData.cap, join = PolyData.join)) }
            }
        }
    }
}

private val PIPS = mapOf(1 to setOf(4), 2 to setOf(0, 8), 3 to setOf(0, 4, 8), 4 to setOf(0, 2, 6, 8), 5 to setOf(0, 2, 4, 6, 8), 6 to setOf(0, 2, 3, 5, 6, 8))

@Composable
private fun Die(n: Int, size: Dp, rot: Float) {
    Canvas(Modifier.size(size).rotate(rot)) {
        drawRoundRect(Color.White, cornerRadius = androidx.compose.ui.geometry.CornerRadius(this.size.width * 0.14f))
        val cell = this.size.width / 3f
        for (idx in PIPS[n.coerceIn(1, 6)].orEmpty()) {
            drawCircle(Ink, radius = cell * 0.17f, center = Offset((idx % 3 + 0.5f) * cell, (idx / 3 + 0.5f) * cell))
        }
    }
}

@Composable
private fun SquareView(i: Int, q: Poly.Sq, side: Dp, st: Poly.State, data: Poly.Data, zoomed: Boolean = false, onHold: (Boolean) -> Unit = {}, onClick: () -> Unit = {}) {
    val c = cellOf(i)
    val zs = if (zoomed) 2.6f else 1f
    val u = side / 62f * zs
    val fs = with(LocalDensity.current) { u.toSp() }
    val where = sideOf(i)
    val band = q.g?.let { hex(data.groups[it] ?: "#888888") }
    val bg = when (q.t) { "go" -> Color(0xFFF6E3B4); "gotojail" -> Color(0xFFF4C7C3); "jail" -> Color(0xFFF1E1C9); "parking" -> Color(0xFFD9E8F5); else -> Color(0xFFE9F4E7) }
    val owner = st.own[i]
    val lvl = st.lvl[i]
    val bandPad = if (band == null) 0.5f else 2.6f
    val (pt, pb, ps, pe) = when (where) {
        "bot" -> listOf(bandPad, 1.7f, 0.3f, 0.3f)
        "top" -> listOf(1.7f, bandPad, 0.3f, 0.3f)
        "left" -> listOf(0.3f, 1.5f, 0.3f, bandPad)
        "right" -> listOf(0.3f, 1.5f, bandPad, 0.3f)
        else -> listOf(0.3f, 0.3f, 0.3f, 0.3f)
    }
    Box(
        (if (zoomed) Modifier.offset((side - side * (c[2] / 100f) * zs) / 2f, (side - side * (c[3] / 100f) * zs) / 2f) else Modifier.offset(side * (c[0] / 100f), side * (c[1] / 100f)))
            .size(side * (c[2] / 100f) * zs, side * (c[3] / 100f) * zs)
            .alpha(if (st.mort[i]) 0.6f else 1f).background(bg).border(if (zoomed) 2.dp else 0.7.dp, Ink)
            .then(if (zoomed) Modifier else Modifier.pointerInput(i) { detectTapGestures(onPress = { tryAwaitRelease(); onHold(false) }, onLongPress = { onHold(true) }, onTap = { onClick() }) }),
    ) {
        if (band != null) {
            val bm = when (where) {
                "bot" -> Modifier.align(Alignment.TopCenter).fillMaxWidth().height(u * 2.2f)
                "top" -> Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(u * 2.2f)
                "left" -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(u * 2.2f)
                else -> Modifier.align(Alignment.CenterStart).fillMaxHeight().width(u * 2.2f)
            }
            Box(bm.background(band))
        }
        Column(
            Modifier.align(Alignment.Center).padding(top = u * pt, bottom = u * pb, start = u * ps, end = u * pe),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            val special = q.t == "chance" || q.t == "chest"
            PolyIcon(q.i, u * (if (where == "corner") 4.2f else if (where == "left" || where == "right") (if (special) 2.6f else 2.1f) else if (special) 3.5f else 2.7f))
            Text(
                q.n, color = Ink,
                fontSize = if (special) fs * 0.95f else if (where == "corner") fs * 1.5f else
                    (if (where == "bot" || where == "top") fs * 0.92f else fs * 1.05f) * minOf(if (q.n.length <= 14) 1f else if (q.n.length <= 20) 0.85f else if (q.n.length <= 26) 0.72f else 0.62f, q.n.split(" ").maxOf { it.length }.let { w -> if (w >= 12) 0.62f else if (w >= 10) 0.75f else 1f }),
                fontWeight = if (special) FontWeight.Normal else FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = fs * 1.1f,
            )
            if (q.p > 0) Text(Poly.eur(q.p), color = Ink, fontSize = fs * 0.95f, fontWeight = FontWeight.SemiBold)
        }
        if (owner >= 0) {
            val om = when (where) {
                "bot" -> Modifier.align(Alignment.BottomCenter).padding(start = u * 0.5f, end = u * 0.5f, bottom = u * 0.2f).fillMaxWidth().height(u * 1.25f)
                "top" -> Modifier.align(Alignment.TopCenter).padding(start = u * 0.5f, end = u * 0.5f, top = u * 0.2f).fillMaxWidth().height(u * 1.25f)
                "left" -> Modifier.align(Alignment.BottomStart).padding(start = u * 0.4f, end = u * 2.6f, bottom = u * 0.2f).fillMaxWidth().height(u * 1.1f)
                else -> Modifier.align(Alignment.BottomEnd).padding(start = u * 2.6f, end = u * 0.4f, bottom = u * 0.2f).fillMaxWidth().height(u * 1.1f)
            }
            Box(om.background(PolyData.colors[owner], RoundedCornerShape(u * 0.35f)).border(u * 0.2f, Color.White, RoundedCornerShape(u * 0.35f)))
        }
        if (lvl > 0) {
            val unit: @Composable () -> Unit = {
                if (lvl == 5) Box(Modifier.size(u * 2f, u * 1.2f).background(Color(0xFFD62828), RoundedCornerShape(u * 0.15f)).border(0.7.dp, Color(0xFF5B0D0D)))
                else repeat(lvl) { Box(Modifier.size(u * 1.2f).background(Color(0xFF2E9B4F), RoundedCornerShape(u * 0.15f)).border(0.7.dp, Color(0xFF0D3B1D))) }
            }
            when (where) {
                "bot" -> Row(Modifier.align(Alignment.TopCenter).padding(top = u * 0.35f), horizontalArrangement = Arrangement.spacedBy(u * 0.2f)) { unit() }
                "top" -> Row(Modifier.align(Alignment.BottomCenter).padding(bottom = u * 0.35f), horizontalArrangement = Arrangement.spacedBy(u * 0.2f)) { unit() }
                "left" -> Column(Modifier.align(Alignment.CenterEnd).padding(end = u * 0.35f), verticalArrangement = Arrangement.spacedBy(u * 0.2f)) { unit() }
                else -> Column(Modifier.align(Alignment.CenterStart).padding(start = u * 0.35f), verticalArrangement = Arrangement.spacedBy(u * 0.2f)) { unit() }
            }
        }
    }
}

@Composable
private fun PolyBoard(
    side: Dp, data: Poly.Data, st: Poly.State, viewPos: List<Int>, tokenOf: (Int) -> Int, dice: DiceView?, onSquare: (Int) -> Unit,
) {
    val density = LocalDensity.current
    Box(Modifier.size(side).background(Color(0xFFCFE6D2)).border(2.dp, Ink, RoundedCornerShape(6.dp))) {
        var zoom by remember { mutableStateOf<Int?>(null) }
        for (i in 0 until 40) SquareView(i, data.squares[i], side, st, data, onHold = { zoom = if (it) i else null }) { onSquare(i) }
        Column(
            Modifier.offset(side * 0.125f, side * 0.125f).size(side * 0.75f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "EVAPOLY", color = Color(0xFFC93B3B), fontWeight = FontWeight.Black, letterSpacing = 3.sp,
                fontSize = with(density) { (side / 11f).toSp() }, modifier = Modifier.rotate(-7f),
            )
            if (st.pot > 0) {
                Text(
                    "La Peza · ${Poly.eur(st.pot)}", color = Color(0xFF8A5A14), fontWeight = FontWeight.ExtraBold, fontSize = with(density) { (side / 28f).toSp() },
                    modifier = Modifier.background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = side * 0.02f, vertical = side * 0.004f),
                )
            }
            if (dice != null) {
                Spacer(Modifier.height(side * 0.04f))
                val t = rememberInfiniteTransition(label = "dados")
                val r by t.animateFloat(-14f, 12f, infiniteRepeatable(tween(90, easing = LinearEasing), RepeatMode.Reverse), label = "giro")
                Row(horizontalArrangement = Arrangement.spacedBy(side * 0.04f)) {
                    Die(dice.d1, side / 10f, if (dice.rolling) r else 0f)
                    Die(dice.d2, side / 10f, if (dice.rolling) -r else 0f)
                }
            }
        }
        val tk = side * 0.056f
        for (s in 0 until st.n) {
            if (st.bk[s]) continue
            val ci = cellOf(viewPos[s])
            val dx = (if (s % 2 == 1) 1f else -1f) * 1.6f
            val dy = (if (s < 2) -1f else 1f) * 1.6f
            val x by animateDpAsState(side * ((ci[0] + ci[2] / 2f + dx) / 100f) - tk / 2f, tween(140, easing = LinearEasing), label = "x$s")
            val y by animateDpAsState(side * ((ci[1] + ci[3] / 2f + dy) / 100f) - tk / 2f, tween(140, easing = LinearEasing), label = "y$s")
            TokenView(tokenOf(s), tk, PolyData.colors[s], Modifier.offset(x, y))
        }
        zoom?.let { SquareView(it, data.squares[it], side, st, data, zoomed = true) }
    }
}

/** Invitación al Evapoly en el chat: cómo va la sala y botón para unirse o abrirla. */
@Composable
fun PolyCard(m: Social.ChatMessage, fg: Color, onOpen: (String) -> Unit) {
    val roomId = m.game.ifEmpty { m.id }
    var room by remember(roomId) { mutableStateOf<Social.PolyRoom?>(null) }
    LaunchedEffect(roomId) {
        while (true) {
            try { room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red o sin acceso */ }
            delay(6_000)
        }
    }
    val r = room
    val inRoom = r?.players?.contains(Social.session?.uid) == true
    val status = when {
        r == null -> ""
        r.status == "lobby" -> "Sala · ${r.players.size}/4"
        r.status == "playing" -> "En juego"
        else -> "Terminada"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Color(0xFFCFE6D2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { PolyIcon("dice", 36.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Evapoly", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = fg)
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = fg.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(14.dp))
        Button(
            onClick = { onOpen(roomId) }, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.18f), contentColor = fg),
        ) { Text(if (r?.status == "lobby" && !inRoom) "Unirse" else "Abrir") }
    }
}

/** La sala del Evapoly: sala de espera (reglas, fichas, invitados) y la partida. Las reglas están en [Poly]; la partida vive en la nube. */
@Composable
fun EvapolyScreen(roomId: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = remember { PolyData.board(ctx) }
    val toks = remember { PolyData.tokens(ctx) }
    val me = Social.session?.uid ?: ""

    var room by remember { mutableStateOf<Social.PolyRoom?>(null) }
    val names = remember { mutableStateMapOf<String, String>() }
    val photos = remember { mutableStateMapOf<String, String>() }
    var st by remember { mutableStateOf<Poly.State?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    val acts = remember { mutableListOf<String>() }
    val viewPos = remember { mutableStateListOf(0, 0, 0, 0) }
    var dice by remember { mutableStateOf<DiceView?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val queue = remember { ArrayDeque<Poly.Ev>() }
    var cardInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    var squareSel by remember { mutableStateOf<Int?>(null) }
    var showTrade by remember { mutableStateOf(false) }
    var inviteList by remember { mutableStateOf<List<Social.Profile>?>(null) }
    var musicOn by remember { mutableStateOf(Prefs.polyMusic) }
    var lastTurn by remember { mutableIntStateOf(-1) }
    var loadError by remember { mutableStateOf("") }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun seatOf(uid: String) = room?.players?.indexOf(uid) ?: -1
    fun mySeat() = seatOf(me)
    fun nameOf(uid: String) = names[uid] ?: "?"
    fun seatName(seat: Int) = room?.players?.getOrNull(seat)?.let { nameOf(it) } ?: "?"
    fun say(m: String) = m.replace(Regex("@(\\d)")) { seatName(it.groupValues[1].toInt()) }
    fun tokenOf(seat: Int): Int = room?.let { r -> r.tok[r.players.getOrNull(seat) ?: ""] } ?: seat

    suspend fun loadNames(r: Social.PolyRoom) {
        val missing = (r.players + r.invited).filter { it !in names }
        if (missing.isEmpty()) return
        val p = runCatching { Social.profilesOf(missing) }.getOrDefault(emptyMap())
        for (u in missing) { names[u] = p[u]?.username ?: "?"; photos[u] = p[u]?.photo ?: "" }
    }

    fun buildState(r: Social.PolyRoom) {
        val s = Poly.initGame(data, r.players.size, Poly.Cfg(r.money, r.goBonus, r.auction), r.seed)
        acts.clear()
        for (a in r.acts) { Poly.applyText(s, a); acts.add(a) }
        st = s
        queue.clear()
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
        lastTurn = s.turn
        ver++
    }

    suspend fun play() {
        if (running) return
        running = true
        ver++
        val s = st ?: run { running = false; return }
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            when (e.k) {
                "dice" -> {
                    PolySound.play("dice")
                    dice = DiceView(e.d1, e.d2, true); delay(900)
                    dice = DiceView(e.d1, e.d2, false)
                }
                "move", "jail" -> {
                    if (e.k == "jail") { PolySound.play("jail"); viewPos[e.seat] = e.to; delay(450) }
                    else {
                        val dir = e.dir
                        val steps = ((e.to - viewPos[e.seat]) * dir + 40) % 40
                        var p = viewPos[e.seat]
                        repeat(steps) {
                            p = (p + dir + 40) % 40
                            viewPos[e.seat] = p
                            PolySound.play("step")
                            delay(if (steps < 8) 150 else 110)
                        }
                    }
                }
                "pay", "debt" -> PolySound.play("pay")
                "go", "gain" -> PolySound.play("coin")
                "buy" -> PolySound.play("buy")
                "build" -> PolySound.play("build")
                "card" -> {
                    PolySound.play("card")
                    cardInfo = (if (e.sq == 0) "Suerte" else if (e.sq == 1) "Caja de comunidad" else "La Peza") to e.text
                    ver++
                    scope.launch { val shown = cardInfo; delay(4200); if (cardInfo == shown) cardInfo = null }
                    delay(900)
                }
                "win" -> PolySound.play("win")
            }
            if (e.m.isNotEmpty()) { log.add(say(e.m)); if (log.size > 40) log.removeAt(0) }
            ver++
        }
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
        running = false
        if (s.turn != lastTurn) { lastTurn = s.turn; if (s.turn == mySeat() && s.phase != "over") PolySound.play("turn") }
        ver++
    }

    fun syncActs(r: Social.PolyRoom) {
        val s = st ?: return
        if (r.acts.size < acts.size) return // lo nuestro todavía no ha llegado a la nube
        for (i in acts.indices) if (r.acts[i] != acts[i]) { buildState(r); return }
        if (r.acts.size == acts.size) return
        for (i in acts.size until r.acts.size) {
            Poly.applyText(s, r.acts[i]); acts.add(r.acts[i])
            queue.addAll(s.ev)
        }
        ver++
        scope.launch { play() }
    }

    fun send(code: String) {
        val s = st ?: return
        val r = room ?: return
        if (busy || running) return
        val seat = mySeat()
        val err = Poly.act(s, seat, code, true)
        if (err != null) { toast(err); return }
        val text = "$seat.$code"
        Poly.applyText(s, text)
        acts.add(text)
        queue.addAll(s.ev)
        busy = true
        ver++
        scope.launch { play() }
        scope.launch {
            try {
                Social.polyAct(roomId, acts.toList(), s.phase == "over")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(e.message ?: "No se pudo enviar")
                runCatching { Social.polyRoom(roomId) }.getOrNull()?.let { room = it; buildState(it) }
            } finally {
                busy = false
                ver++
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = r
    }

    LaunchedEffect(Unit) { PolyMusic.start(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val r = Social.polyRoom(roomId)
                if (r == null) { toast("La sala se ha cerrado"); onClose(); return@LaunchedEffect }
                val wasLobby = room == null || room?.status == "lobby"
                room = r
                loadError = ""
                loadNames(r)
                if (r.status != "lobby") { if (st == null || wasLobby) buildState(r) else syncActs(r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName // sin red o sin permiso: se reintenta y se enseña el motivo
            }
            delay(1_500)
        }
    }
    DisposableEffect(Unit) { onDispose { PolyMusic.stop() } }

    @Suppress("UNUSED_VARIABLE") val redraw = ver // las acciones cambian el estado del motor sin que Compose se entere: `ver` obliga a redibujar

    val r = room
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Evapoly", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                musicOn = !musicOn; Prefs.polyMusic = musicOn
                if (musicOn) PolyMusic.start(ctx) else PolyMusic.stop()
            }) { Icon(if (musicOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Música", tint = OnSurface) }
        }
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text("…", color = OnSurfaceDim, fontSize = 24.sp)
                    if (loadError.isNotEmpty()) Text(loadError, color = OnSurfaceDim, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        } else if (r.status == "lobby" || st == null) {
            PolyLobby(
                r, me, names, photos, toks.size, onToast = ::toast,
                onJoin = { scope.launch { try { Social.polyJoin(r, firstFreeToken(r, toks.size)); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onLeave = { scope.launch { try { Social.polyLeave(r); onClose() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onRemove = { scope.launch { runCatching { Social.polyRemove(roomId) }; onClose() } },
                onTok = { t -> scope.launch { try { Social.polyTok(r, t); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onCfg = { money, go, auc -> scope.launch { try { Social.polyCfg(r, money, go, auc); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onStart = { scope.launch { try { Social.polyStart(r, kotlin.random.Random.nextInt(Int.MAX_VALUE)); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onInvite = { scope.launch { try { inviteList = Social.overview().friends.map { it.profile } } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
            )
        } else {
            val s = st!!
            val my = mySeat()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val land = maxWidth > maxHeight * 1.15f
                val side = (if (land) minOf(maxHeight - 8.dp, maxWidth - 400.dp) else minOf(maxWidth, maxHeight - 290.dp)).coerceAtLeast(260.dp)
                val panel: @Composable (Modifier) -> Unit = { mod ->
                    GamePanel(
                        s, r, my, data, ::seatName, ::tokenOf, log, running || busy,
                        onSend = ::send, onTrade = { showTrade = true }, mod,
                    )
                }
                val board: @Composable () -> Unit = {
                    PolyBoard(side, data, s, viewPos, ::tokenOf, dice) { squareSel = it }
                }
                if (land) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        board()
                        panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        board()
                        Spacer(Modifier.height(8.dp))
                        panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }

    // ───── diálogos ─────
    val s = st
    if (r != null && s != null) {
        cardInfo?.let { (title, text) ->
            AlertDialog(
                onDismissRequest = { cardInfo = null }, title = { Text(title) },
                text = { Text(text, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { cardInfo = null }) { Text("Vale") } },
            )
        }
        squareSel?.let { i ->
            SquareDialog(i, s, data, mySeat(), ::seatName, running || busy, onSend = { code -> send(code) }, onClose = { squareSel = null })
        }
        if (showTrade) {
            TradeDialog(s, data, mySeat(), ::seatName, onClose = { showTrade = false }, onPropose = { code -> showTrade = false; send(code) })
        }
    }
    inviteList?.let { list ->
        val rr = room
        val free = list.filter { f -> rr != null && f.uid !in rr.invited && f.uid !in rr.players }
        AlertDialog(
            onDismissRequest = { inviteList = null }, title = { Text("Invitar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (free.isEmpty() || rr == null || rr.invited.size >= 3) Text("—", color = OnSurfaceDim)
                    else for (f in free) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).clickable {
                                scope.launch {
                                    try {
                                        Social.polyInvite(rr, f.uid)
                                        names[f.uid] = f.username; photos[f.uid] = f.photo
                                        room = Social.polyRoom(roomId); inviteList = null
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

private fun firstFreeToken(r: Social.PolyRoom, n: Int): Int {
    val used = r.tok.values.toSet()
    return (0 until n).firstOrNull { it !in used } ?: 0
}

@Composable
private fun PolyLobby(
    r: Social.PolyRoom, me: String, names: Map<String, String>, photos: Map<String, String>, tokenCount: Int, onToast: (String) -> Unit,
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onTok: (Int) -> Unit, onCfg: (Int, Boolean, Boolean) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
) {
    val isHost = r.host == me
    val inRoom = me in r.players
    val invitedOnly = !inRoom && me in r.invited
    val taken = r.tok.filterKeys { it in r.players && it != me }.values.toSet()
    var money by remember(r.money) { mutableStateOf(r.money.toFloat()) }
    val pending = r.invited.filter { it !in r.players }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp).padding(horizontal = 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // asientos
            for (rowIdx in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in 0..1) {
                        val i = rowIdx * 2 + k
                        val u = r.players.getOrNull(i)
                        val shape = RoundedCornerShape(14.dp)
                        val col = PolyData.colors[i]
                        Row(
                            Modifier.weight(1f).clip(shape).background(Surface1).border(2.dp, col.copy(alpha = if (u != null) 1f else 0.4f), shape).padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (u != null) {
                                TokenView(r.tok[u] ?: i, 34.dp, col)
                                Spacer(Modifier.width(8.dp))
                                Avatar(names[u] ?: "?", photos[u] ?: "", 28)
                                Spacer(Modifier.width(6.dp))
                                Text(names[u] ?: "?", color = OnSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (u == r.host) { Spacer(Modifier.width(4.dp)); PolyIcon("crown", 18.dp) }
                            } else {
                                val inv = pending.getOrNull(i - r.players.size)
                                Text(if (inv != null) (names[inv] ?: "?") + " …" else "—", color = OnSurfaceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            // fichas
            if (inRoom) {
                val mySeat = r.players.indexOf(me)
                val myTok = r.tok[me]
                for (rowIdx in 0 until (tokenCount + 3) / 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in 0..3) {
                            val t = rowIdx * 4 + k
                            if (t >= tokenCount) { Spacer(Modifier.weight(1f)); continue }
                            val shape = RoundedCornerShape(16.dp)
                            val col = PolyData.colors[mySeat]
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(shape).background(if (myTok == t) col.copy(alpha = 0.2f) else Surface1)
                                    .border(3.dp, if (myTok == t) col else Color.Transparent, shape)
                                    .alpha(if (t in taken) 0.25f else 1f)
                                    .then(if (t in taken) Modifier else Modifier.clickable { onTok(t) }),
                                contentAlignment = Alignment.Center,
                            ) { TokenView(t, 52.dp, Color.Transparent) }
                        }
                    }
                }
            }
            // reglas
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Dinero inicial", color = OnSurface)
                    Text(Poly.eur(money.toInt()), color = OnSurface, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = money, onValueChange = { money = it }, valueRange = 500f..2500f, steps = 39, enabled = isHost,
                    onValueChangeFinished = { onCfg(money.toInt(), r.goBonus, r.auction) },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("400 € al caer en la Salida", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.goBonus, { onCfg(r.money, it, r.auction) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Subastas", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.auction, { onCfg(r.money, r.goBonus, it) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
            }
            // botones
            if (invitedOnly) PolyButton("Unirse", true, true) { onJoin() }
            if (isHost) {
                PolyButton("Invitar", false, r.invited.size < 3) { onInvite() }
                PolyButton("Empezar", true, r.players.size >= 2) { onStart() }
                TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cerrar sala", color = Accent) }
            } else if (inRoom) {
                TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Salir", color = Accent) }
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = onToast
    }
}

@Composable
private fun PolyButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    if (primary) {
        Button(onClick, modifier.height(50.dp), enabled = enabled, shape = shape, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(label, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(onClick, modifier.height(50.dp), enabled = enabled, shape = shape, border = BorderStroke(1.5.dp, if (enabled) Accent else Outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)) { Text(label, fontWeight = FontWeight.SemiBold) }
    }
}

/** Jugadores (uno debajo de otro; el que tiene el turno, con un recuadro de su color), acciones y registro. */
@Composable
private fun GamePanel(
    s: Poly.State, r: Social.PolyRoom, my: Int, data: Poly.Data, seatName: (Int) -> String, tokenOf: (Int) -> Int,
    log: List<String>, locked: Boolean, onSend: (String) -> Unit, onTrade: () -> Unit, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in r.players.indices) {
            val shape = RoundedCornerShape(14.dp)
            val col = PolyData.colors[i]
            val turn = s.turn == i && s.phase != "over"
            val owned = (0 until 40).count { s.own[it] == i }
            Row(
                Modifier.fillMaxWidth().clip(shape).background(if (turn) col.copy(alpha = 0.18f).compositeOver(Surface1) else Surface1)
                    .border(if (turn) 3.dp else 0.dp, if (turn) col else Color.Transparent, shape).alpha(if (s.bk[i]) 0.4f else 1f).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenView(tokenOf(i), 38.dp, col)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(seatName(i) + if (i == my) " (tú)" else "", color = OnSurface, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val extra = if (s.bk[i]) "Quiebra" else listOfNotNull(if (s.jail[i]) "Cárcel" else null, if (s.jf[i] > 0) "Carta ×${s.jf[i]}" else null, if (s.mc[i] > 0) "Maricarmen" else null, "$owned calles").joinToString(" · ")
                    Text(extra, color = OnSurfaceDim, fontSize = 12.sp)
                }
                Text(Poly.eur(s.cash[i]), color = OnSurface, fontWeight = FontWeight.ExtraBold)
            }
        }

        // acciones según la fase
        val cur = s.turn
        @Composable
        fun Status(t: String) = Text(t, color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 2.dp))
        @Composable
        fun Btns(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
        @Composable
        fun androidx.compose.foundation.layout.RowScope.B(label: String, code: String, primary: Boolean = true, enabled: Boolean = true) =
            PolyButton(label, primary, enabled && !locked, Modifier.weight(1f)) { onSend(code) }

        val t = s.trade
        if (my < 0) Status("Espectador")
        else if (t != null && t.to == my) {
            Status("${seatName(t.from)} propone")
            fun names(list: List<Int>) = list.joinToString(", ") { data.squares[it].n }
            TradeLine("Te da", listOfNotNull(if (t.gc > 0) Poly.eur(t.gc) else null, if (t.gp.isNotEmpty()) names(t.gp) else null, if (t.gj > 0) "${t.gj} carta" else null).joinToString(" + ").ifEmpty { "—" })
            TradeLine("Te pide", listOfNotNull(if (t.rc > 0) Poly.eur(t.rc) else null, if (t.rp.isNotEmpty()) names(t.rp) else null, if (t.rj > 0) "${t.rj} carta" else null).joinToString(" + ").ifEmpty { "—" })
            Btns { B("Aceptar", "Y"); B("Rechazar", "N", false) }
        } else if (t != null && t.from == my) {
            Status("Esperando respuesta")
            Btns { B("Cancelar trato", "N", false) }
        } else if (s.phase == "over") Status("Gana ${seatName(s.winner)}")
        else if (s.phase == "debt") {
            val d = s.debts[0]
            if (d.seat == my) {
                Status("Debes ${Poly.eur(d.amt)}" + if (d.to >= 0) " a ${seatName(d.to)}" else "")
                Btns { B("Pagar", "d", true, s.cash[my] >= d.amt); if (d.rent && s.mc[my] > 0) B("Maricarmen", "k"); B("Quiebra", "q", false) }
            } else Status("${seatName(d.seat)} debe ${Poly.eur(d.amt)}")
        } else if (s.phase == "auction") {
            val au = s.auction!!
            Status("Subasta: ${data.squares[au.sq].n} · " + if (au.bidder >= 0) "${Poly.eur(au.bid)} (${seatName(au.bidder)})" else "sin pujas")
            if (au.cur == my) Btns { for (inc in listOf(10, 50, 100)) B("+$inc", "i${au.bid + inc}", true, au.bid + inc <= s.cash[my]); B("Retirarse", "f", false) }
            else Status("Puja ${seatName(au.cur)}")
        } else if (cur != my) Status("Turno de ${seatName(cur)}")
        else if (s.phase == "roll") {
            Btns {
                if (s.jail[my]) { B("Pagar ${Poly.eur(Poly.JAIL_FINE)}", "p", false, s.cash[my] >= Poly.JAIL_FINE); if (s.jf[my] > 0) B("Usar carta", "c", false) }
                B("Tirar dados", "r")
            }
        } else if (s.phase == "buy") {
            val q = data.squares[s.buy]
            Status("${q.n} · ${Poly.eur(q.p)}")
            Btns { B("Comprar", "b", true, s.cash[my] >= q.p); if (s.cfg.auction) B("Subastar", "a", false); B("No comprar", "n", false) }
        } else if (s.phase == "end") Btns { B("Terminar turno", "e") }

        if (my >= 0 && cur == my && (s.phase == "roll" || s.phase == "end") && t == null) {
            var confirmQuit by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onTrade, enabled = !locked) { Text("Tratar", color = Accent) }
                TextButton(onClick = { confirmQuit = true }, enabled = !locked) { Text("Abandonar", color = Accent) }
            }
            if (confirmQuit) AlertDialog(
                onDismissRequest = { confirmQuit = false }, title = { Text("Abandonar") }, text = { Text("") },
                confirmButton = { TextButton(onClick = { confirmQuit = false; onSend("z") }) { Text("Abandonar") } },
                dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Cancelar") } },
            )
        }
        for (line in log.takeLast(4)) Text(line, color = OnSurfaceDim, fontSize = 13.sp)
    }
}

@Composable
private fun TradeLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = OnSurfaceDim)
        Text(value, color = OnSurface, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** Ficha de la casilla: alquileres, precio, dueño y, si es tuya, construir / vender / hipotecar. */
@Composable
private fun SquareDialog(i: Int, s: Poly.State, data: Poly.Data, my: Int, seatName: (Int) -> String, locked: Boolean, onSend: (String) -> Unit, onClose: () -> Unit) {
    val q = data.squares[i]
    val o = s.own[i]
    val head = q.g?.let { hex(data.groups[it] ?: "#888888") } ?: Surface2
    val headText = if (q.g == null || q.g == "yellow" || q.g == "sky") Ink else Color.White
    fun can(code: String) = my >= 0 && Poly.act(s, my, code, true) == null
    AlertDialog(
        onDismissRequest = onClose,
        title = null,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(head).padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(q.n, color = headText, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
                @Composable
                fun Line(l: String, v: String, now: Boolean = false, color: Color = OnSurface) =
                    Row(Modifier.fillMaxWidth().then(if (now) Modifier.background(Accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)) else Modifier).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(l, color = OnSurface); Text(v, color = color, fontWeight = FontWeight.Bold)
                    }
                when (q.t) {
                    "street" -> {
                        val names = listOf("Solar", "1 casa", "2 casas", "3 casas", "4 casas", "Hotel")
                        q.r.forEachIndexed { k, v -> Line(names[k], Poly.eur(v), s.lvl[i] == k) }
                        Line("Casa / hotel", Poly.eur(s.houseCost(i)))
                    }
                    "station" -> q.r.forEachIndexed { k, v -> Line("${k + 1} estación" + if (k > 0) "es" else "", Poly.eur(v)) }
                    "utility" -> { Line("1 servicio", "4 × dados"); Line("2 servicios", "10 × dados") }
                    "tax" -> Line("Pagas", Poly.eur(q.p))
                }
                if (q.p > 0 && q.t != "tax") { Line("Precio", Poly.eur(q.p)); Line("Hipoteca", Poly.eur(q.p / 2)) }
                if (o >= 0) Line("Dueño", seatName(o) + if (s.mort[i]) " · hipotecada" else "", color = PolyData.colors[o])
                if (o == my && my >= 0 && !locked) {
                    if (can("h$i")) PolyButton("Construir ${Poly.eur(s.houseCost(i))}", true, true) { onSend("h$i") }
                    if (can("s$i")) PolyButton("Vender ${Poly.eur(s.houseCost(i) / 2)}", false, true) { onSend("s$i") }
                    if (can("m$i")) PolyButton("Hipotecar ${Poly.eur(q.p / 2)}", false, true) { onSend("m$i") }
                    if (can("u$i")) PolyButton("Deshipotecar ${Poly.eur(s.unmortgageCost(i))}", true, true) { onSend("u$i") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}

/** Proponer un trato: eliges con quién, qué das y qué pides (dinero, calles sin edificios y cartas de cárcel). */
@Composable
private fun TradeDialog(s: Poly.State, data: Poly.Data, my: Int, seatName: (Int) -> String, onClose: () -> Unit, onPropose: (String) -> Unit) {
    val others = (0 until s.n).filter { it != my && !s.bk[it] }
    if (others.isEmpty() || my < 0) { onClose(); return }
    var to by remember { mutableIntStateOf(others[0]) }
    val give = remember { mutableStateListOf<Int>() }
    val take = remember { mutableStateListOf<Int>() }
    var gc by remember { mutableIntStateOf(0) }
    var rc by remember { mutableIntStateOf(0) }
    var gj by remember { mutableIntStateOf(0) }
    var rj by remember { mutableIntStateOf(0) }
    val code = "T$to|$gc|$rc|${give.joinToString(",")}|${take.joinToString(",")}|$gj|$rj"
    val err = Poly.act(s, my, code, true)
    val empty = gc + rc + give.size + take.size + gj + rj == 0
    fun tradable(i: Int): Boolean { val g = data.squares[i].g; return g == null || s.groupOf(g).all { s.lvl[it] == 0 } }

    @Composable
    fun Col(seat: Int, set: MutableList<Int>, cash: Int, setCash: (Int) -> Unit, jc: Int, setJ: (Int) -> Unit, modifier: Modifier) {
        val colr = PolyData.colors[seat]
        Column(modifier.border(BorderStroke(0.dp, Color.Transparent)).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (seat == my) "Doy" else "Pido", color = colr, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { setCash((cash - 10).coerceAtLeast(0)) }, modifier = Modifier.size(34.dp)) { Text("−", color = OnSurface, fontSize = 20.sp) }
                OutlinedTextField(
                    value = if (cash == 0) "" else cash.toString(), onValueChange = { v -> setCash(v.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0) },
                    modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                IconButton(onClick = { setCash(cash + 10) }, modifier = Modifier.size(34.dp)) { Text("+", color = OnSurface, fontSize = 20.sp) }
            }
            if (s.jf[seat] > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Carta", color = OnSurfaceDim)
                IconButton(onClick = { setJ((jc - 1).coerceAtLeast(0)) }, modifier = Modifier.size(34.dp)) { Text("−", color = OnSurface, fontSize = 20.sp) }
                Text("$jc", color = OnSurface, fontWeight = FontWeight.Bold)
                IconButton(onClick = { setJ((jc + 1).coerceAtMost(s.jf[seat])) }, modifier = Modifier.size(34.dp)) { Text("+", color = OnSurface, fontSize = 20.sp) }
            }
            for (i in (0 until 40).filter { s.own[it] == seat }) {
                val sel = i in set
                val ok = tradable(i)
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Surface2).border(2.dp, if (sel) Accent else Color.Transparent, shape).alpha(if (ok) 1f else 0.35f)
                        .then(if (ok) Modifier.clickable { if (sel) set.remove(i) else set.add(i) } else Modifier).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).background(data.squares[i].g?.let { hex(data.groups[it] ?: "#888888") } ?: Color.Gray, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(6.dp))
                    Text(data.squares[i].n, color = OnSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onClose, title = { Text("Tratar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (others.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in others) {
                        val sel = o == to
                        val shape = RoundedCornerShape(16.dp)
                        Text(
                            seatName(o), color = if (sel) Color.White else OnSurface,
                            modifier = Modifier.clip(shape).background(if (sel) PolyData.colors[o] else Color.Transparent).border(2.dp, PolyData.colors[o], shape)
                                .clickable { to = o; take.clear(); rc = 0; rj = 0 }.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                } else Text(seatName(to), color = OnSurface, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Col(my, give, gc, { gc = it }, gj, { gj = it }, Modifier.weight(1f))
                    Col(to, take, rc, { rc = it }, rj, { rj = it }, Modifier.weight(1f))
                }
                if (err != null && !empty) Text(err, color = Color(0xFFE53935), fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onPropose(code) }, enabled = err == null) { Text("Proponer") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
    )
}
