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
import androidx.compose.foundation.gestures.detectTapGestures
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

private fun DrawScope.drawShip(i: Int, s: Fleet.Ship, cs: Float, gray: Boolean, originX: Float = (s.cell % 10) * cs, originY: Float = (s.cell / 10) * cs) {
    val vertical = s.dir == 'v'
    fun p(x: Float, y: Float) = if (vertical) Offset(originX + (1f - y) * cs, originY + x * cs) else Offset(originX + x * cs, originY + y * cs)
    fun col(c: Long): Color = Color(c).let { if (gray) lerp(it, Color(0xFF2A2A2A), 0.7f) else it }
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
    Canvas(modifier.size(cell * Fleet.LENS[i], cell)) { drawShip(i, Fleet.Ship(0, 'h'), size.height, gray, 0f, 0f) }
}

/** Tablero de 10 × 10 con letras y números. `marks`: 'w' agua, 'h' tocado, 's' hundido, 'p' disparo sin responder. */
@Composable
private fun FleetBoard(
    ships: List<Fleet.Ship?>, sunk: Set<Int>, marks: Map<Int, Char>, bs: Dp, glow: Boolean = false, onCell: ((Int) -> Unit)? = null,
) {
    val cell = bs / 10
    Column {
        Row(Modifier.padding(start = 18.dp)) { for (c in 0 until 10) Text(('A' + c).toString(), Modifier.width(cell), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnSurfaceDim, textAlign = TextAlign.Center) }
        Row {
            Column(Modifier.width(18.dp)) { for (r in 1..10) Box(Modifier.height(cell).width(18.dp), contentAlignment = Alignment.Center) { Text("$r", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnSurfaceDim) } }
            val shape = RoundedCornerShape(12.dp)
            Canvas(
                Modifier.size(bs).clip(shape).then(if (glow) Modifier.border(3.dp, Accent, shape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape))
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
                ships.forEachIndexed { i, s -> if (s != null) drawShip(i, s, cs, i in sunk) }
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
        r.status == "lobby" -> "Sala · ${r.players.size}/2"
        r.status == "playing" -> "En juego"
        else -> "Terminada"
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
                    val rp = Fleet.replay(r.players, r.seed, r.again, r.s, r.a)
                    if (rp.pending && rp.cur != me) {
                        val mineAns = r.a[me] ?: ""
                        val n = rp.log.count { it.by == rp.cur }
                        if (mineAns.length == n) {
                            val next = mineAns + Fleet.answers(m.ships, Fleet.shotCells(r.s[rp.cur]).take(n + 1))[n]
                            val lost = next.count { it >= '2' } >= Fleet.LENS.size
                            Social.fAnswer(r, next, if (lost) m.reveal() else null)
                            wrote = true
                        }
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
                onCfg = { adj, again -> scope.launch { try { Social.fCfg(r, adj, again); pollOnce() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
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
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onCfg: (Boolean, Boolean) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
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
                    Switch(r.adj, { onCfg(it, r.again) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Repetir turno al acertar", color = OnSurface)
                        Text("Quien acierta (tocado o hundido) vuelve a disparar.", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                    Switch(r.again, { onCfg(r.adj, it) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Colocar la flota: se elige un barco, se gira si hace falta y se toca el mar; tocar un barco colocado lo recoge. */
@Composable
private fun FleetPlace(adj: Boolean, busy: Boolean, onReady: (List<Fleet.Ship>) -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(List<Fleet.Ship?>(Fleet.LENS.size) { null }) }
    var sel by remember { mutableStateOf<Int?>(0) }
    var dir by remember { mutableStateOf('h') }
    val placed = draft.all { it != null }
    val bs = 320.dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when { sel == null && placed -> "Flota lista. Toca un barco para moverlo o pulsa «Listo»."; sel == null -> "Elige un barco abajo."; else -> "Toca el mar para colocar el ${Fleet.NAMES[sel!!].lowercase()} (${Fleet.LENS[sel!!]})." },
            color = OnSurface, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        FleetBoard(draft, emptySet(), emptyMap(), bs) { c ->
            val at = draft.indices.firstOrNull { i -> draft[i]?.let { Fleet.cellsOf(it, i).contains(c) } == true }
            val s0 = sel
            if (s0 == null && at != null) {
                dir = draft[at]!!.dir; sel = at; draft = draft.toMutableList().also { it[at] = null }
            } else if (s0 != null) {
                var row = c / 10; var col = c % 10
                if (dir == 'h') col = minOf(col, 10 - Fleet.LENS[s0]) else row = minOf(row, 10 - Fleet.LENS[s0])
                val s = Fleet.Ship(row * 10 + col, dir)
                if (Fleet.canPlace(draft, s0, s, adj)) {
                    draft = draft.toMutableList().also { it[s0] = s }
                    sel = draft.indices.firstOrNull { draft[it] == null }
                } else Toast.makeText(ctx, if (adj) "Ahí no cabe" else "Ahí no cabe: entre barcos tiene que quedar una casilla de agua", Toast.LENGTH_SHORT).show()
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in Fleet.LENS.indices) {
                val shape = RoundedCornerShape(14.dp)
                val on = sel == i
                Column(
                    Modifier.clip(shape).background(if (on) Accent.copy(alpha = 0.18f) else Surface1).border(2.dp, if (on) Accent else Outline, shape)
                        .clickable { if (draft[i] != null) { dir = draft[i]!!.dir; draft = draft.toMutableList().also { it[i] = null } }; sel = i }
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

/** La batalla y el resultado. */
@Composable
private fun FleetBattle(
    r: Social.FRoom, me: String, names: Map<String, String>, photos: Map<String, String>, mine: MineFleet?, verifyMsg: String,
    onShoot: (Int) -> Unit, onForfeit: () -> Unit, onClose: () -> Unit,
) {
    val opp = r.players.firstOrNull { it != me } ?: ""
    val rp = Fleet.replay(r.players, r.seed, r.again, r.s, r.a)
    val finished = r.status == "finished"
    val winner = if (r.ff.isNotEmpty()) (if (r.ff == me) opp else me) else rp.winner
    val nameOpp = names[opp] ?: "?"
    val haptic = LocalHapticFeedback.current
    var banner by remember { mutableStateOf("") }
    var bannerHit by remember { mutableStateOf(false) }
    var lastLog by remember { mutableStateOf(-1) }
    var confirmForfeit by remember { mutableStateOf(false) }

    // aviso de cada disparo nuevo (el primer vistazo solo toma nota de lo que ya había)
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
            if (e.res != '0') haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(2400)
            banner = ""
        }
    }

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

    val turnText = if (finished) {
        (if (winner == me) "¡Has ganado! 🎉" else "Gana ${names[winner] ?: "?"}") + (if (r.ff.isNotEmpty()) (if (r.ff == me) " · te has rendido" else " · $nameOpp se ha rendido") else "")
    } else if (rp.cur == me) (if (rp.pending) "Esperando la respuesta de $nameOpp…" else "¡Tu turno! Dispara al mar enemigo")
    else (if (rp.pending) "$nameOpp ha disparado…" else "Turno de $nameOpp")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            turnText, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            color = if (myTurn) OnAccent else if (finished) Color.White else OnSurface,
            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth()
                .background(if (myTurn) Accent else if (finished) Color(0xFF2E7D6B) else Surface2, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Text("Flota de $nameOpp", fontWeight = FontWeight.Bold, color = OnSurface, fontSize = 15.sp)
        Box(contentAlignment = Alignment.Center) {
            FleetBoard(oppShips ?: emptyList(), emptySet(), eMarks, 310.dp, glow = myTurn, onCell = if (myTurn) { c -> if (!eMarks.containsKey(c)) onShoot(c) } else null)
            if (banner.isNotEmpty()) {
                Text(
                    banner, color = Color.White, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(if (bannerHit) Color(0xFFE53935) else Color(0xFF2F6FA8), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        // barcos enemigos que quedan, según lo que he hundido
        val left = Fleet.LENS.toMutableList().also { l -> eSunk.forEach { s -> l.remove(s) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in Fleet.LENS.indices) {
                val alive = left.remove(Fleet.LENS[i])
                ShipIcon(i, 14.dp, gray = !alive, modifier = Modifier.then(if (alive) Modifier else Modifier.background(Color.Transparent)))
            }
        }
        Text("Tu flota", fontWeight = FontWeight.Bold, color = OnSurface, fontSize = 15.sp)
        FleetBoard(mine?.ships ?: emptyList(), mySunk, oMarks, 230.dp)
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
        Spacer(Modifier.height(16.dp))
    }
    if (confirmForfeit) {
        AlertDialog(
            onDismissRequest = { confirmForfeit = false }, title = { Text("Rendirse") }, text = { Text("Pierdes la partida y gana $nameOpp.") },
            confirmButton = { TextButton(onClick = { confirmForfeit = false; onForfeit() }) { Text("Rendirme") } },
            dismissButton = { TextButton(onClick = { confirmForfeit = false }) { Text("Cancelar") } },
        )
    }
}
