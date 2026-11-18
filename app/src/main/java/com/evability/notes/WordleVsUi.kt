package com.evability.notes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wordle versus: de 2 a 4 jugadores resuelven las mismas palabras (de 1 a 5 rondas) y gana quien más puntos junta. Letra amarilla = 1 punto,
 * verde = 2, acertar la palabra = 10 (2 × 5); en cada ronda cuenta el mejor intento. La sala vive en la nube (`wrooms/{id}`): cada jugador
 * escribe solo su avance y los demás lo ven en directo. Misma lógica que `wordlevs.js`.
 */
private const val VS_ROWS = 6
private val VS_TIMES = listOf(30, 45, 60, 90, 120, 180, 300)
private fun timeLabel(s: Int) = if (s < 60) "$s s" else if (s % 60 == 0) "${s / 60} min" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"

/** Avance de un jugador ya desmontado: palabras de cada ronda, puntos de las rondas terminadas y hora de inicio de la ronda actual. */
private class VsMine(val segs: List<List<String>>, val rs: List<Int>, val st: Long)

private fun parseVs(e: Social.WEntry?): VsMine {
    val segs = if (e == null || e.w.isEmpty()) emptyList() else e.w.split("|").map { s -> if (s.isEmpty()) emptyList() else s.split(",") }
    val rs = if (e == null || e.rs.isEmpty()) emptyList() else e.rs.split(",").mapNotNull { it.toIntOrNull() }
    return VsMine(segs, rs, e?.st ?: 0L)
}

private fun packVs(segs: List<List<String>>, rs: List<Int>, st: Long) = Social.WEntry(segs.joinToString("|") { it.joinToString(",") }, rs.joinToString(","), st)
private fun vsScore(guess: String, ans: String) = WordleGame.evaluate(guess, ans).sum()
private fun clock(left: Int) = "${left / 60}:${(left % 60).toString().padStart(2, '0')}"

/** Invitación al Wordle versus en el chat: cómo va la sala y botón para unirse o abrirla. */
@Composable
fun WvsCard(m: Social.ChatMessage, fg: Color, onOpen: (String) -> Unit) {
    val roomId = m.game.ifEmpty { m.id }
    var room by remember(roomId) { mutableStateOf<Social.WRoom?>(null) }
    LaunchedEffect(roomId) {
        while (true) {
            if (AppVisibility.foreground) {
                try { room = Social.wRoom(roomId); if (room?.status == "finished") break } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red o sin acceso */ }
            }
            delay(20_000)
        }
    }
    val r = room
    val inRoom = r?.players?.contains(Social.session?.uid) == true
    val status = when {
        r == null -> ""
        else -> (if (r.status == "lobby") "Sala · ${r.players.size}/4" else if (r.status == "playing") "En juego" else "Terminada") +
            " · ${r.rounds} ronda${if (r.rounds == 1) "" else "s"}" + (if (r.same) "" else " · palabras distintas") + (if (r.timer) " · " + timeLabel(r.secs) else "")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(fg.copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_versus), null, tint = fg, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Wordle versus", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = fg)
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = fg.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(14.dp))
        Button(
            onClick = { onOpen(roomId) },
            enabled = !(r != null && !inRoom && (r.status != "lobby" || r.players.size >= 4)),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.18f), contentColor = fg),
        ) { Text(if (r?.status == "lobby" && !inRoom) (if (r.players.size >= 4) "Completa" else "Unirse") else "Abrir") }
    }
}

@Composable
fun WordleVsScreen(roomId: String, onClose: () -> Unit) {
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

    var room by remember { mutableStateOf<Social.WRoom?>(null) }
    val names = remember { mutableStateMapOf<String, String>() }
    val photos = remember { mutableStateMapOf<String, String>() }
    var ready by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var segs by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var rs by remember { mutableStateOf<List<Int>>(emptyList()) }
    var st by remember { mutableStateOf(0L) }
    var dirty by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var msgGood by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var inviteList by remember { mutableStateOf<List<Social.Profile>?>(null) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    fun wordOf(r: Social.WRoom, round: Int, u: String = me) = WordleGame.versusWord(r.seed, round, if (r.same) 0 else r.players.indexOf(u).coerceAtLeast(0))
    fun behindOf(r: Social.WRoom, need: Int) = r.players.filter { it != me && parseVs(r.g[it]).rs.size < need }

    suspend fun push(r: Social.WRoom, over: Boolean) {
        try { Social.wProgress(r, packVs(segs, rs, st), over); dirty = false } catch (e: CancellationException) { throw e } catch (e: Exception) { dirty = true; toast(e.message ?: "Sin conexión: se reintenta") }
    }

    fun finishRound(r: Social.WRoom, timeout: Boolean) {
        val k = rs.size
        if (k >= r.rounds) return
        val words = segs.getOrNull(k).orEmpty()
        val ans = wordOf(r, k)
        val pts = words.maxOfOrNull { vsScore(it, ans) } ?: 0
        val solved = words.lastOrNull() == ans
        rs = rs + pts; st = 0L; current = ""
        msg = if (solved) "¡Acertada! +$pts puntos" else (if (timeout) "Se acabó el tiempo. " else "") + "Era ${ans.uppercase()} · +$pts puntos"
        msgGood = solved
        val over = rs.size >= r.rounds && behindOf(r, r.rounds).isEmpty()
        scope.launch { push(r, over) }
    }

    fun startRound(r: Social.WRoom) {
        val k = rs.size
        segs = segs + List(maxOf(0, k + 1 - segs.size)) { emptyList<String>() }
        st = System.currentTimeMillis(); current = ""; msg = ""
        scope.launch { push(r, false) }
    }

    fun press(r: Social.WRoom, key: String) {
        if (!ready || st <= 0L || rs.size >= r.rounds) return
        msg = ""
        val rr = rs.size
        when (key) {
            "⌫" -> current = current.dropLast(1)
            "ENTER" -> when {
                current.length < 5 -> msg = "Faltan letras"
                !WordleGame.isValid(current) -> msg = "No está en la lista"
                else -> {
                    val words = segs.getOrNull(rr).orEmpty() + current
                    segs = segs.toMutableList().also { while (it.size <= rr) it.add(emptyList()); it[rr] = words }
                    current = ""
                    if (words.last() == wordOf(r, rr) || words.size >= VS_ROWS) finishRound(r, false)
                    else scope.launch { push(r, false) }
                }
            }
            else -> if (current.length < 5) current += key.lowercase()
        }
    }

    fun forfeit(r: Social.WRoom) {
        while (rs.size < r.rounds) rs = rs + 0
        st = 0L
        val over = behindOf(r, r.rounds).isEmpty()
        scope.launch { push(r, over); onClose() }
    }

    // consulta periódica: sala de espera cada 3,5 s y partida cada 2,5 s (para ver en directo cómo va el otro)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WordleGame.load(ctx) }
        ready = true
        while (true) {
            if (AppVisibility.foreground) {
                try {
                    val r = Social.wRoom(roomId)
                    if (r == null) { toast("La sala se ha cerrado"); onClose(); return@LaunchedEffect }
                    room = r
                    val missing = (r.players + r.invited).filter { it !in names }
                    if (missing.isNotEmpty()) {
                        val p = runCatching { Social.profilesOf(missing) }.getOrDefault(emptyMap())
                        for (u in missing) { names[u] = p[u]?.username ?: "?"; photos[u] = p[u]?.photo ?: "" }
                    }
                    if (r.status != "lobby") {
                        if (!loaded) { val p = parseVs(r.g[me]); segs = p.segs; rs = p.rs; st = p.st; loaded = true }
                        else if (dirty) push(r, false)
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
            }
            delay(if (room?.status == "lobby") 3500L else 2500L)
        }
    }

    // temporizador: 1 minuto por palabra; la hora de inicio va en el avance, así que sigue contando si se cierra la pantalla
    LaunchedEffect(st, room?.timer, rs.size, loaded) {
        val r = room ?: return@LaunchedEffect
        if (st > 0L && r.timer && rs.size < r.rounds) {
            while (true) {
                now = System.currentTimeMillis()
                if (r.secs - (now - st) / 1000 <= 0) { finishRound(r, true); break }
                delay(250)
            }
        }
    }

    val r = room
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Bg).statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Wordle versus", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { dark = !dark; Prefs.gameDark = dark }) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
        }
        when {
            r == null || !ready -> Text("Cargando…", color = OnSurfaceDim, modifier = Modifier.padding(16.dp))
            r.status == "lobby" -> VsLobby(
                r, me, names, photos,
                onJoin = { scope.launch { try { Social.wJoin(r); room = Social.wRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onLeave = { scope.launch { try { Social.wLeave(r); onClose() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onRemove = { scope.launch { runCatching { Social.wRemove(roomId) }; onClose() } },
                onCfg = { n, t, s, same -> scope.launch { try { Social.wCfg(r, n, t, s, same); room = Social.wRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onStart = { scope.launch { try { Social.wStart(r, (Math.random() * 2_000_000_000).toInt()); room = Social.wRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onInvite = { scope.launch { try { inviteList = Social.overview().friends.map { it.profile } } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
            )
            me !in r.players -> Text("La partida ya ha empezado.", color = OnSurfaceDim, modifier = Modifier.padding(16.dp))
            !loaded -> Text("Cargando…", color = OnSurfaceDim, modifier = Modifier.padding(16.dp))
            else -> VsGame(
                r, me, names, photos, segs, rs, st, now, current, msg, msgGood,
                onKey = { press(r, it) }, onStartRound = { startRound(r) }, onForfeit = { forfeit(r) }, onClose = onClose,
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
                    if (free.isEmpty() || rr == null || rr.invited.size >= 3) Text("—", color = OnSurfaceDim)
                    else for (f in free) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).clickable {
                                scope.launch {
                                    try {
                                        Social.wInvite(rr, f.uid)
                                        names[f.uid] = f.username; photos[f.uid] = f.photo
                                        room = Social.wRoom(roomId); inviteList = null
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

/** Sala de espera: asientos, número de rondas, temporizador y botones (misma estética que la del Evapoly). */
@Composable
private fun VsLobby(
    r: Social.WRoom, me: String, names: Map<String, String>, photos: Map<String, String>,
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onCfg: (Int, Boolean, Int, Boolean) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
) {
    val isHost = r.host == me
    val inRoom = me in r.players
    val canJoin = !inRoom && (me in r.invited || r.group.isNotEmpty()) && r.players.size < 4 // en la sala de un grupo entra cualquier miembro hasta que se llene
    val pending = r.invited.filter { it !in r.players }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rowIdx in 0..1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in 0..1) {
                        val i = rowIdx * 2 + k
                        val u = r.players.getOrNull(i)
                        val shape = RoundedCornerShape(16.dp)
                        val col = PolyData.colors[i]
                        Row(
                            Modifier.weight(1f).height(44.dp).clip(shape).background(Surface1).border(2.dp, col.copy(alpha = if (u != null) 1f else 0.4f), shape).padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (u != null) {
                                Avatar(names[u] ?: "?", photos[u] ?: "", 28)
                                Spacer(Modifier.width(6.dp))
                                Text(if (u == me) "Tú" else names[u] ?: "?", color = OnSurface, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (u == r.host) { Spacer(Modifier.width(4.dp)); Text("♛", color = Color(0xFFE3A94A), fontSize = 18.sp) }
                            } else {
                                val inv = pending.getOrNull(i - r.players.size)
                                Text(if (inv != null) (names[inv] ?: "?") + " …" else "—", color = OnSurfaceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Surface1).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Rondas", color = OnSurface, fontWeight = FontWeight.SemiBold)
                    for (n in 1..5) {
                        val sel = r.rounds == n
                        val shape = RoundedCornerShape(14.dp)
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(shape).background(if (sel) Accent.copy(alpha = 0.2f) else Surface2)
                                .border(2.dp, if (sel) Accent else Outline, shape)
                                .then(if (isHost) Modifier.clickable { if (!sel) onCfg(n, r.timer, r.secs, r.same) } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (sel) Accent else OnSurface.copy(alpha = if (isHost) 1f else 0.6f)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Misma palabra para todos", color = OnSurface)
                        Text(if (r.same) "Todos adivináis la misma palabra en cada ronda." else "Cada uno tiene una palabra distinta en cada ronda.", color = OnSurfaceDim, fontSize = 12.sp)
                    }
                    Switch(r.same, { onCfg(r.rounds, r.timer, r.secs, it) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Temporizador por palabra", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.timer, { onCfg(r.rounds, it, r.secs, r.same) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                if (r.timer) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in VS_TIMES) {
                            val sel = r.secs == s
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier.height(34.dp).widthIn(min = 54.dp).clip(shape).background(if (sel) Accent.copy(alpha = 0.2f) else Surface2)
                                    .border(2.dp, if (sel) Accent else Outline, shape)
                                    .then(if (isHost) Modifier.clickable { if (!sel) onCfg(r.rounds, true, s, r.same) } else Modifier)
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(timeLabel(s), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = if (sel) Accent else OnSurface.copy(alpha = if (isHost) 1f else 0.6f)) }
                        }
                    }
                }
                Text(
                    "Letra amarilla: 1 punto · letra verde: 2 puntos · acertar la palabra: 10 puntos. En cada ronda cuenta tu mejor intento. Pueden jugar de 2 a 4 personas.",
                    color = OnSurfaceDim, fontSize = 11.sp, lineHeight = 13.sp,
                )
            }
            if (canJoin) PolyButton("Unirse", true, true) { onJoin() }
            if (isHost) {
                if (r.group.isEmpty()) PolyButton("Invitar", false, r.invited.size < 3) { onInvite() }
                PolyButton(if (r.players.size < 2) "Esperando jugadores…" else "Empezar", true, r.players.size >= 2) { onStart() }
                TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cerrar sala", color = Accent) }
            } else if (inRoom) {
                TextButton(onClick = onLeave, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Salir", color = Accent) }
            }
        }
    }
}

/**
 * La partida, como el Wordle normal: el tablero en el centro, el teclado debajo (todo a la vista, sin scroll) y los jugadores
 * (puntos, intento que llevan y si te esperan) en una columna a la derecha. Al terminar, los resultados sustituyen al teclado.
 */
@Composable
private fun VsGame(
    r: Social.WRoom, me: String, names: Map<String, String>, photos: Map<String, String>,
    segs: List<List<String>>, rs: List<Int>, st: Long, now: Long, current: String, msg: String, msgGood: Boolean,
    onKey: (String) -> Unit, onStartRound: () -> Unit, onForfeit: () -> Unit, onClose: () -> Unit,
) {
    val k = rs.size
    val over = k >= r.rounds
    val phase = if (over) 2 else if (st > 0L) 0 else 1 // 0 jugando, 1 entre rondas, 2 terminado
    val boardRound = when (phase) { 0 -> k; 1 -> maxOf(0, k - 1); else -> r.rounds - 1 }
    val words = segs.getOrNull(boardRound).orEmpty()
    val ans = WordleGame.versusWord(r.seed, boardRound, if (r.same) 0 else r.players.indexOf(me).coerceAtLeast(0))
    val best = HashMap<Char, Int>()
    for (g in words) WordleGame.evaluate(g, ans).forEachIndexed { i, s -> best[g[i]] = maxOf(best[g[i]] ?: -1, s) }
    fun kOf(u: String) = if (u == me) k else parseVs(r.g[u]).rs.size
    val wait = r.players.filter { it != me && kOf(it) < (if (phase == 1) k else r.rounds) }
    val allDone = r.players.all { kOf(it) >= r.rounds }
    val finishedAll = over && allDone
    val myTotal = rs.sum()
    val left = if (phase == 0 && r.timer) maxOf(0, r.secs - ((now - st) / 1000).toInt()) else 0

    @Composable
    fun Main(boardW: androidx.compose.ui.unit.Dp) {
        Column(Modifier.width(boardW), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (over) "Partida terminada" else "Ronda ${k + 1}/${r.rounds}", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = OnSurface, maxLines = 1, modifier = Modifier.weight(1f))
                if (phase == 0 && r.timer) {
                    Text(
                        clock(left), fontWeight = FontWeight.Black, fontSize = 17.sp, color = if (left <= 10) Color.White else OnSurface,
                        modifier = Modifier.background(if (left <= 10) Color(0xFFE53935) else Surface2, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                }
                Text("$myTotal pts", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Accent)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (row in 0 until VS_ROWS) {
                    val word = words.getOrNull(row) ?: if (row == words.size && phase == 0) current else ""
                    val eval = words.getOrNull(row)?.let { WordleGame.evaluate(it, ans) }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (c in 0 until 5) {
                            val status = eval?.get(c) ?: -1
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).background(statusColor(status), RoundedCornerShape(8.dp))
                                    .border(2.dp, if (status >= 0) statusColor(status) else if (c < word.length) OnSurfaceDim else Outline, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text(word.getOrNull(c)?.uppercase() ?: "", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (status >= 0) Color.White else OnSurface) }
                        }
                    }
                }
            }
            Text(
                msg.ifEmpty { if (phase == 1 && k == 0) "Todos jugáis las mismas palabras" else "" },
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (msgGood) WGreen else OnSurface, textAlign = TextAlign.Center, modifier = Modifier.heightIn(min = 20.dp),
            )
            if (phase == 1) {
                if (wait.isNotEmpty()) PolyButton("Esperando a ${wait.joinToString { names[it] ?: "?" }}…", true, false, Modifier.fillMaxWidth(), compact = true) {}
                else PolyButton("Empezar ronda ${k + 1}", true, true, Modifier.fillMaxWidth(), compact = true) { onStartRound() }
            } else if (phase == 2 && !allDone) {
                PolyButton("Esperando a ${wait.joinToString { names[it] ?: "?" }}…", true, false, Modifier.fillMaxWidth(), compact = true) {}
            }
        }
    }

    @Composable
    fun Side(modifier: Modifier, mini: androidx.compose.ui.unit.Dp) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (u in r.players) VsPlayerCard(r, me, u, names, photos, segs, rs, st, mini)
            if (r.players.size > 1 && !over) TextButton(onClick = onForfeit, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) { Text("Abandonar", color = OnSurfaceDim, fontSize = 11.sp) }
        }
    }

    @Composable
    fun Keyboard(keyH: androidx.compose.ui.unit.Dp) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(bottom = 3.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("QWERTYUIOP", "ASDFGHJKLÑ", "ZXCVBNM").forEachIndexed { rowIndex, letters ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (rowIndex == 2) KeyCap("ENTER", 1.6f, Surface2, null, keyH) { onKey("ENTER") }
                    letters.forEach { ch ->
                        val s = best[ch.lowercaseChar()] ?: -1
                        KeyCap(ch.toString(), 1f, if (s >= 0) statusColor(s) else Surface2, if (s >= 0) Color.White else null, keyH) { onKey(ch.toString()) }
                    }
                    if (rowIndex == 2) KeyCap("⌫", 1.6f, Surface2, null, keyH) { onKey("⌫") }
                }
            }
        }
    }

    if (finishedAll) {
        // al terminar solo se ven los resultados, a todo el ancho (con scroll solo si la pantalla fuese diminuta)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            VsResults(r, me, names, photos, rs, onClose)
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val keyH = (maxHeight * 0.07f).coerceIn(40.dp, 62.dp)
            val n = r.players.size
            // cuadritos de los demás: se encogen según cuántos jugadores haya para que la columna quepa sin scroll
            val sideH = maxHeight - keyH * 3 - 28.dp
            val mini = (((sideH - 5.dp * (n - 1)) / n - 50.dp) / 6).coerceIn(4.dp, 10.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    // el tablero ocupa lo que cabe: ni más ancho que su columna ni tan alto que empuje el teclado
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        Main(minOf(maxWidth, 380.dp, (maxHeight - 84.dp) * 5 / 6))
                    }
                    Side(Modifier.width(90.dp).fillMaxHeight(), mini)
                }
                Keyboard(keyH)
            }
        }
    }
}

/** Tarjeta (compacta) de un jugador: puntos, intento que lleva (en cuadritos de colores, sin letras) y si te está esperando. */
@Composable
private fun VsPlayerCard(r: Social.WRoom, me: String, u: String, names: Map<String, String>, photos: Map<String, String>, mySegs: List<List<String>>, myRs: List<Int>, mySt: Long, mini: androidx.compose.ui.unit.Dp = 9.dp) {
    val seat = r.players.indexOf(u).coerceAtLeast(0)
    val e = if (u == me) VsMine(mySegs, myRs, mySt) else parseVs(r.g[u])
    val myK = myRs.size
    val k = e.rs.size
    val showR = minOf(k, myK, r.rounds - 1)
    val words = e.segs.getOrNull(showR).orEmpty()
    val status = when {
        u == me -> if (myK >= r.rounds) "Terminaste" else if (mySt > 0L) "Intento ${minOf(VS_ROWS, mySegs.getOrNull(myK).orEmpty().size + 1)}/$VS_ROWS" else "Entre rondas"
        k >= r.rounds -> "Terminó"
        k > myK -> "⏳ Te espera"
        k < myK -> "Ronda ${k + 1}"
        e.st > 0L || words.isNotEmpty() -> "Intento ${minOf(VS_ROWS, words.size + (if (e.st > 0L) 1 else 0))}/$VS_ROWS"
        else -> "Sin empezar"
    }
    val waiting = u != me && k > myK
    val col = PolyData.colors[seat]
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Surface1).border(if (waiting) 3.dp else 2.dp, col, shape).padding(horizontal = 5.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(names[u] ?: "?", photos[u] ?: "", 18)
            Spacer(Modifier.width(4.dp))
            Text(if (u == me) "Tú" else names[u] ?: "?", color = OnSurface, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${e.rs.sum()}", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
        }
        if (u != me) {
            val ansShown = if (r.same) WordleGame.versusWord(r.seed, showR) else WordleGame.versusWord(r.seed, showR, seat)
            Column(Modifier.align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0 until VS_ROWS) {
                    val ev = words.getOrNull(row)?.let { WordleGame.evaluate(it, ansShown) }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in 0 until 5) {
                            val s = ev?.get(c) ?: -1
                            Box(Modifier.size(mini).background(if (s >= 0) statusColor(s) else Color.Transparent, RoundedCornerShape(2.dp)).border(1.dp, if (s >= 0) statusColor(s) else Outline, RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
        }
        Text(status, color = OnSurfaceDim, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VsResults(r: Social.WRoom, me: String, names: Map<String, String>, photos: Map<String, String>, myRs: List<Int>, onClose: () -> Unit) {
    class Row2(val u: String, val rs: List<Int>, val t: Int)
    val rows = r.players.map { u -> val rsU = if (u == me) myRs else parseVs(r.g[u]).rs; Row2(u, rsU, rsU.sum()) }.sortedByDescending { it.t }
    val top = rows.filter { it.t == rows.first().t }
    val title = when {
        rows.size == 1 -> "Has hecho ${rows[0].t} puntos"
        top.size > 1 -> "Empate"
        top[0].u == me -> "🏆 ¡Has ganado!"
        else -> "Gana ${names[top[0].u] ?: "?"}"
    }
    fun wordsOf(seat: Int) = (0 until r.rounds).joinToString(" · ") { WordleGame.versusWord(r.seed, it, if (r.same) 0 else seat).uppercase() }
    Column(Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(22.dp)).background(Surface1).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = OnSurface, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        var place = 0
        var prev = -1
        rows.forEachIndexed { i, row ->
            if (row.t != prev) { place = i + 1; prev = row.t }
            val col = PolyData.colors[r.players.indexOf(row.u).coerceAtLeast(0)]
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).border(2.dp, col, shape).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rows.size > 1) Text("$place", fontWeight = FontWeight.Black, color = OnSurface, modifier = Modifier.width(22.dp))
                    Avatar(names[row.u] ?: "?", photos[row.u] ?: "", 30)
                    Spacer(Modifier.width(8.dp))
                    Text(if (row.u == me) "Tú" else names[row.u] ?: "?", fontWeight = FontWeight.ExtraBold, color = OnSurface, modifier = Modifier.weight(1f))
                    Text("${row.t} pts", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Accent)
                }
                Text(row.rs.mapIndexed { i, p -> "R${i + 1}: $p" }.joinToString(" · "), fontSize = 12.sp, color = OnSurfaceDim)
            }
        }
        if (r.same) Text("Palabras: ${wordsOf(0)}", fontSize = 12.sp, color = OnSurfaceDim)
        else rows.forEach { row -> Text("${if (row.u == me) "Tú" else names[row.u] ?: "?"}: ${wordsOf(r.players.indexOf(row.u).coerceAtLeast(0))}", fontSize = 12.sp, color = OnSurfaceDim) }
        PolyButton("Cerrar", true, true) { onClose() }
    }
}
