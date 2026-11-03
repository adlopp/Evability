package com.evability.notes

import androidx.compose.material.icons.filled.DarkMode
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Colores del tablero (verde clásico) y de las casillas marcadas.
private val LightSq = Color(0xFFEEEED2)
private val DarkSq = Color(0xFF769656)
private val LightHi = Color(0xFFF5F682)
private val DarkHi = Color(0xFFBBCB2B)

private val Reasons = mapOf(
    "mate" to "Jaque mate", "resign" to "Rendición", "timeout" to "Tiempo agotado", "stalemate" to "Rey ahogado",
    "insufficient" to "Material insuficiente", "repetition" to "Triple repetición", "50moves" to "Regla de los 50 movimientos", "agreement" to "Acuerdo",
)
private val PieceValue = mapOf('q' to 9, 'r' to 5, 'b' to 3, 'n' to 3, 'p' to 1)

private fun mmss(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun PieceIcon(c: Char, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    remember { ChessPieces.load(ctx); true }
    Canvas(modifier.size(size)) { with(ChessPieces) { drawPiece(c, Offset.Zero, this@Canvas.size.minDimension) } }
}

/** Elegir cuánto tiempo tiene cada jugador. */
@Composable
fun ChessTimeDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tiempo por jugador") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Sin tiempo", 3 to "3 minutos", 5 to "5 minutos", 10 to "10 minutos", 15 to "15 minutos", 30 to "30 minutos").forEach { (mins, label) ->
                    OutlinedButton(
                        onClick = { onPick(mins) }, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, Accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Invitación a una partida de ajedrez dentro del chat: muestra cómo va y deja unirse o abrirla. */
@Composable
fun ChessCard(m: Social.ChatMessage, friend: Social.Profile, fg: Color, onOpen: () -> Unit) {
    var game by remember(m.id) { mutableStateOf<Social.ChessGame?>(null) }
    LaunchedEffect(m.id) {
        while (true) {
            if (AppVisibility.foreground) {
                try { game = Social.chessGame(friend.uid, m.id); if (game?.status == "finished") break } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red */ }
            }
            delay(20_000)
        }
    }
    val g = game
    val status = when {
        g == null -> ""
        g.status == "waiting" -> if (m.mine) "Esperando rival" else "Te invitan a jugar"
        g.status == "playing" -> "En juego"
        g.result == "d" -> "Tablas"
        else -> "Gana ${if ((if (g.result == "w") g.white else g.black) == Social.session?.uid) (Social.session?.username ?: "") else friend.username}"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(DarkSq, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { PieceIcon('N', 40.dp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Evadrez", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = fg)
            Text(if (m.mins > 0) "${m.mins} min" else "Sin tiempo", fontSize = 12.sp, color = fg.copy(alpha = 0.8f))
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = fg.copy(alpha = 0.8f))
        }
        Spacer(Modifier.width(14.dp))
        Button(
            onClick = onOpen, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.18f), contentColor = fg),
        ) { Text(if (g?.status == "waiting" && !m.mine) "Unirse" else "Abrir") }
    }
}

/** La partida: tablero, piezas, reloj, jaque, tablas y rendición. Las reglas están en [Chess]; la partida vive en la nube y se consulta cada poco. */
@Composable
fun ChessScreen(friend: Social.Profile, gameId: String, onClose: () -> Unit) {
    var fullScreen by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (fullScreen) fullScreen = false else onClose() })
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    remember { ChessPieces.load(ctx); true }
    val me = Social.session?.uid ?: ""
    val myName = Social.session?.username ?: ""

    var g by remember { mutableStateOf<Social.ChessGame?>(null) }
    var pos by remember { mutableStateOf<ChessPos?>(null) }
    var moves by remember { mutableStateOf<List<String>>(emptyList()) }
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var history by remember { mutableStateOf<List<ChessPlayed>>(emptyList()) }
    var result by remember { mutableStateOf<Pair<Char, String>?>(null) }
    var sel by remember { mutableIntStateOf(-1) }
    var targets by remember { mutableStateOf<List<ChessMove>>(emptyList()) }
    var lastMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pending by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var promoFor by remember { mutableStateOf<List<ChessMove>?>(null) }
    var showResign by remember { mutableStateOf(false) }
    var myPhoto by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val view = LocalView.current
    // Pantalla completa: se ocultan las barras del sistema (se vuelven a ver deslizando desde el borde) y el tablero se agranda.
    DisposableEffect(fullScreen) {
        val window = (ctx as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var now by remember { mutableLongStateOf(Social.serverNow()) }
    val anim = remember { Animatable(1f) }
    var animFrom by remember { mutableIntStateOf(-1) }
    var animTo by remember { mutableIntStateOf(-1) }
    var animTick by remember { mutableIntStateOf(0) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()

    val game = g
    val myColor: Char = if (game != null && game.white == me) 'w' else if (game != null && game.black == me) 'b' else ' '
    fun playing() = g?.status == "playing" && result == null
    fun myTurn() = playing() && myColor != ' ' && pos?.turn == myColor

    fun moveSound(o: ChessOutcome, flag: String, captured: Boolean) =
        ChessSound.play(if (o.over) "end" else if (o.check) "check" else if (flag == "castle") "castle" else if (captured) "capture" else "move")

    fun finish(res: Char, reason: String) {
        if (ending) return
        ending = true
        scope.launch {
            try {
                Social.chessEnd(friend.uid, gameId, res.toString(), reason)
                result = res to reason
                ChessSound.play("end")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "No se pudo terminar la partida"
            }
            ending = false
        }
    }

    fun applyRemote(remote: Social.ChessGame) {
        val before = moves.size
        g = remote
        if (remote.moves.size > moves.size || pos == null) {
            val r = Chess.replay(remote.moves)
            if (r == null) { message = "La partida tiene una jugada no válida"; return }
            pos = r.p
            keys = r.keys
            history = r.history
            moves = remote.moves
            val last = r.history.lastOrNull()
            lastMove = last?.let { it.m.from to it.m.to }
            sel = -1
            targets = emptyList()
            if (before > 0 && last != null) { // jugada del rival
                moveSound(Chess.outcome(r.p, r.keys), last.m.flag, last.captured != ' ')
                animFrom = last.m.from
                animTo = last.m.to
                animTick++
            }
        }
        if (remote.status == "finished" && result == null) {
            result = (remote.result.firstOrNull() ?: 'd') to remote.reason
            ChessSound.play("end")
        }
        // si alguien dejó la partida acabada en el tablero pero sin cerrar en la nube, se cierra
        if (remote.status == "playing") pos?.let { p -> val o = Chess.outcome(p, keys); if (o.over && !ending) finish(o.result, o.reason) }
    }

    suspend fun poll() {
        if (pending) return
        var remote = Social.chessGame(friend.uid, gameId) ?: return
        if (remote.status == "waiting" && remote.creator != me) {
            Social.joinChess(friend.uid, remote)
            remote = Social.chessGame(friend.uid, gameId) ?: return
        }
        if (remote.moves.size >= moves.size || remote.status == "finished") applyRemote(remote)
    }

    fun commit(m: ChessMove) {
        val p = pos ?: return
        val cur = g ?: return
        if (pending) return
        val mover = p.turn
        val capture = p.b[m.to] != ' ' || m.flag == "ep"
        // reloj: al que mueve se le descuenta lo que ha tardado
        var wms = cur.wms
        var bms = cur.bms
        if (cur.mins > 0) {
            val el = maxOf(0L, Social.serverNow() - cur.lastAt)
            if (mover == 'w') wms = maxOf(0L, wms - el) else bms = maxOf(0L, bms - el)
        }
        val taken = if (m.flag == "ep") (if (mover == 'w') 'p' else 'P') else p.b[m.to]
        history = history + ChessPlayed(m, taken, p.b[m.from])
        val np = Chess.applyMove(p, m)
        pos = np
        val sent = moves + Chess.moveString(m)
        moves = sent
        keys = keys + Chess.positionKey(np)
        lastMove = m.from to m.to
        sel = -1
        targets = emptyList()
        val o = Chess.outcome(np, keys)
        moveSound(o, m.flag, capture)
        animFrom = m.from
        animTo = m.to
        animTick++
        g = cur.copy(moves = sent, wms = wms, bms = bms, lastAt = Social.serverNow(), drawOffer = "")
        if (o.over) result = o.result to o.reason
        pending = true
        scope.launch {
            try {
                Social.chessMove(friend.uid, gameId, sent, wms, bms, if (o.over) o.result.toString() else null, if (o.over) o.reason else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "No se pudo enviar la jugada"
                result = null
                pos = null
                moves = emptyList()
            }
            pending = false
            runCatching { poll() }
        }
    }

    fun select(sq: Int) {
        val p = pos ?: return
        sel = sq
        targets = Chess.legalMoves(p).filter { it.from == sq }
        ChessSound.play("pick")
    }

    fun tryMove(from: Int, to: Int): Boolean {
        val cands = targets.filter { it.from == from && it.to == to }
        if (cands.isEmpty()) return false
        if (cands.size > 1) promoFor = cands else commit(cands[0]) // promoción: elegir pieza
        return true
    }

    // ───── sincronización con la nube y relojes ─────
    LaunchedEffect(Unit) { runCatching { myPhoto = Social.myProfile().photo } }
    LaunchedEffect(Unit) {
        var idle = 0
        while (true) {
            if (!AppVisibility.foreground) { delay(2_500); continue }
            val before = moves.size
            try { poll() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red: se reintenta */ }
            idle = if (moves.size != before) 0 else idle + 1
            // cada 2,5 s mientras mueve el rival y cada 6 s cuando te toca a ti (nadie va a mover antes)
            val base = if (myTurn()) 6_000L else 2_500L
            delay((base * (1 + minOf(3, idle / 12) * 0.5)).toLong().coerceAtMost(12_000L))
        }
    }
    LaunchedEffect(animTick) {
        if (animTick > 0) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(170))
        }
    }
    fun clocks(): Pair<Long, Long>? {
        val cur = g ?: return null
        if (cur.mins <= 0) return null
        var w = cur.wms
        var b = cur.bms
        if (playing()) {
            val el = maxOf(0L, now - cur.lastAt)
            if (moves.size % 2 == 0) w -= el else b -= el
        }
        return maxOf(0L, w) to maxOf(0L, b)
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = Social.serverNow()
            val c = clocks()
            if (c != null && playing() && !ending && (c.first <= 0 || c.second <= 0)) finish(if (c.first <= 0) 'b' else 'w', "timeout")
            delay(250)
        }
    }

    val flip = myColor == 'b'
    val outcomeNow = pos?.let { Chess.outcome(it, keys) }
    val inCheckNow = playing() && outcomeNow?.check == true

    // piezas capturadas por cada bando y ventaja de material
    val byWhite = ArrayList<Char>()
    val byBlack = ArrayList<Char>()
    for (h in history) if (h.captured != ' ') (if (Chess.colorOf(h.captured) == 'b') byWhite else byBlack).add(h.captured.lowercaseChar())
    byWhite.sortByDescending { PieceValue[it] ?: 0 }
    byBlack.sortByDescending { PieceValue[it] ?: 0 }
    val diff = byWhite.sumOf { PieceValue[it] ?: 0 } - byBlack.sumOf { PieceValue[it] ?: 0 }
    val clk = clocks()

    val opp = if (myColor == 'w') 'b' else 'w'
    val mine = if (myColor == 'b') 'b' else 'w'

    var dark by remember { mutableStateOf(Prefs.gameDark) }
    // Fondo oscuro: mientras está activo todo el tema pasa a negro (también los iconos de las barras del sistema, que dependen de él).
    DisposableEffect(dark) {
        val prev = Bg
        if (dark) Bg = Color.Black
        onDispose { Bg = prev }
    }

    @Composable
    fun Header() {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Evadrez", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { dark = !dark; Prefs.gameDark = dark }) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
            IconButton(
                onClick = { fullScreen = true },
                modifier = Modifier.background(if (dark) Color.White else Color.Transparent, CircleShape),
            ) { Icon(Icons.Default.Fullscreen, "Pantalla completa", tint = if (dark) Color.Black else OnSurface) }
        }
    }

    @Composable
    fun ExitFullScreen() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                onClick = { dark = !dark; Prefs.gameDark = dark },
                modifier = Modifier.size(44.dp).background(if (dark) Color.White else Surface2, CircleShape),
            ) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Color.Black else OnSurface) }
            IconButton(
                onClick = { fullScreen = false },
                modifier = Modifier.size(44.dp).background(if (dark) Color.White else Surface2, CircleShape),
            ) { Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = if (dark) Color.Black else OnSurface) }
        }
    }

    @Composable
    fun Bar(color: Char, name: String, photo: String, modifier: Modifier) {
        val turn = playing() && pos?.turn == color
        val took = if (color == 'w') byWhite else byBlack
        val lead = if (color == 'w') diff else -diff
        Row(
            modifier.background(if (turn) Accent.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(name, photo, 34)
            Spacer(Modifier.width(10.dp))
            Text(name, fontWeight = FontWeight.SemiBold, color = OnSurface)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(10.dp).background(if (color == 'w') Color.White else Color(0xFF222222), CircleShape).border(1.5.dp, OnSurfaceDim, CircleShape))
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                took.forEach { PieceIcon(if (color == 'w') it else it.uppercaseChar(), 16.dp) }
                if (lead > 0) Text(" +$lead", fontSize = 13.sp, color = OnSurfaceDim)
            }
            if (clk != null) {
                val ms = if (color == 'w') clk.first else clk.second
                Text(
                    mmss(ms), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = if (ms < 10_000) Color.White else if (turn) OnAccent else OnSurface,
                    modifier = Modifier.background(if (ms < 10_000) Color(0xFFC62828) else if (turn) Accent else Surface2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Composable
    fun Board(side: Dp) {
        val cell = with(density) { side.toPx() } / 8f
            // ───── tablero ─────
            Box(Modifier.size(side).clip(RoundedCornerShape(10.dp))) {
                fun colRow(sq: Int): Pair<Int, Int> = if (flip) (7 - (sq and 7)) to (7 - (sq shr 3)) else (sq and 7) to (sq shr 3)
                fun origin(sq: Int): Offset = colRow(sq).let { Offset(it.first * cell, it.second * cell) }
                fun squareAt(p: Offset): Int {
                    val c = (p.x / cell).toInt()
                    val r = (p.y / cell).toInt()
                    if (p.x < 0 || p.y < 0 || c !in 0..7 || r !in 0..7) return -1
                    return if (flip) (7 - r) * 8 + (7 - c) else r * 8 + c
                }

                Canvas(
                    Modifier.size(side).pointerInput(flip, myColor, cell) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val p0 = pos
                            val sq = squareAt(down.position)
                            if (promoFor != null || p0 == null || sq < 0 || !myTurn() || pending) return@awaitEachGesture
                            if (sel >= 0 && targets.any { it.to == sq }) {
                                tryMove(sel, sq)
                                return@awaitEachGesture
                            }
                            val piece = p0.b[sq]
                            if (piece == ' ' || Chess.colorOf(piece) != myColor) {
                                sel = -1
                                targets = emptyList()
                                return@awaitEachGesture
                            }
                            select(sq)
                            var dragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    if (dragging) {
                                        val to = squareAt(ch.position)
                                        dragFrom = -1
                                        if (to >= 0 && to != sq && targets.any { it.to == to }) tryMove(sq, to)
                                    }
                                    break
                                }
                                if (!dragging && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    dragFrom = sq
                                }
                                if (dragging) {
                                    dragPos = ch.position
                                    ch.consume()
                                }
                            }
                            dragFrom = -1
                        }
                    },
                ) {
                    val kingSq = if (inCheckNow) pos?.let { Chess.kingSquare(it, it.turn) } ?: -1 else -1
                    // casillas
                    for (sq in 0 until 64) {
                        val (c, r) = colRow(sq)
                        val light = (c + r) % 2 == 0
                        val marked = sq == sel || (lastMove?.let { sq == it.first || sq == it.second } == true)
                        drawRect(if (marked) (if (light) LightHi else DarkHi) else if (light) LightSq else DarkSq, Offset(c * cell, r * cell), Size(cell, cell))
                    }
                    // brillo rojo del rey en jaque
                    if (kingSq >= 0) {
                        val o = origin(kingSq) + Offset(cell / 2, cell / 2)
                        drawCircle(
                            Brush.radialGradient(listOf(Color(0xFFFF0000), Color(0xFFE70000), Color(0x00A90000)), center = o, radius = cell * 0.78f),
                            radius = cell * 0.78f, center = o,
                        )
                    }
                    // coordenadas (números en la columna de la izquierda, letras en la fila de abajo)
                    for (sq in 0 until 64) {
                        val (c, r) = colRow(sq)
                        val light = (c + r) % 2 == 0
                        val col = if (light) DarkSq else LightSq
                        val style = TextStyle(color = col, fontSize = (cell * 0.2f).toSp(), fontWeight = FontWeight.Bold)
                        if (c == 0) drawText(textMeasurer, "${8 - (sq shr 3)}", Offset(c * cell + cell * 0.05f, r * cell + cell * 0.02f), style)
                        if (r == 7) {
                            val layout = textMeasurer.measure("abcdefgh"[sq and 7].toString(), style)
                            drawText(layout, topLeft = Offset(c * cell + cell - layout.size.width - cell * 0.05f, r * cell + cell - layout.size.height - cell * 0.02f))
                        }
                    }
                    // movimientos posibles: punto en casilla vacía, anillo si hay captura
                    val pp = pos
                    for (t in targets) {
                        val o = origin(t.to) + Offset(cell / 2, cell / 2)
                        if (pp != null && (pp.b[t.to] != ' ' || t.flag == "ep")) drawCircle(Color(0x26000000), radius = cell * 0.46f, center = o, style = Stroke(cell * 0.1f))
                        else drawCircle(Color(0x26000000), radius = cell * 0.17f, center = o)
                    }
                    // piezas (la que se mueve se desliza desde su casilla anterior; la que se arrastra sigue al dedo)
                    if (pp != null) {
                        for (sq in 0 until 64) {
                            val piece = pp.b[sq]
                            if (piece == ' ' || sq == dragFrom) continue
                            var tl = origin(sq)
                            if (sq == animTo && animFrom >= 0 && anim.value < 1f) tl = origin(animFrom) + (tl - origin(animFrom)) * anim.value
                            with(ChessPieces) { drawPiece(piece, tl, cell) }
                        }
                        if (dragFrom >= 0) {
                            val piece = pp.b[dragFrom]
                            if (piece != ' ') with(ChessPieces) { drawPiece(piece, dragPos - Offset(cell * 0.575f, cell * 0.65f), cell * 1.15f) }
                        }
                    }
                }

                // fin de la partida
                val res = result
                if (res != null) {
                    Box(Modifier.size(side).background(Color(0x38000000)), contentAlignment = Alignment.Center) {
                        Column(
                            Modifier.background(Surface1, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(horizontal = 30.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(if (res.first == 'd') "Tablas" else "Gana ${if (res.first == myColor) myName.ifEmpty { "Tú" } else friend.username}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                            Text(Reasons[res.second] ?: "", color = OnSurfaceDim)
                            Button(onClick = onClose, shape = RoundedCornerShape(14.dp)) { Text("Cerrar", fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }

    }

    @Composable
    fun StatusPanel() {
        // estado, tablas y rendición
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val cur = g
            if (cur != null) {
                if (cur.status == "waiting") {
                    Text(if (cur.creator == me) "Esperando rival…" else "Uniéndose…", fontWeight = FontWeight.SemiBold, color = OnSurface)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (playing() && inCheckNow) {
                            Surface(color = Color(0xFFC62828), shape = RoundedCornerShape(50)) { Text("Jaque", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }
                        } else if (playing()) {
                            Text(if (myTurn()) "Tu turno" else "Turno de ${friend.username}", fontWeight = FontWeight.SemiBold, color = OnSurface)
                        }
                        if (playing() && cur.drawOffer.isNotEmpty() && cur.drawOffer != me) {
                            Text("Propone tablas", color = OnSurface)
                            Button(onClick = { finish('d', "agreement") }, shape = RoundedCornerShape(12.dp)) { Text("Aceptar") }
                            OutlinedButton(onClick = { scope.launch { runCatching { Social.chessOffer(friend.uid, gameId, "") }; runCatching { poll() } } }, shape = RoundedCornerShape(12.dp)) { Text("Rechazar") }
                        }
                    }
                    if (playing() && myColor != ' ') {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                enabled = cur.drawOffer != me, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.5.dp, Accent),
                                onClick = { scope.launch { runCatching { Social.chessOffer(friend.uid, gameId, me) }; runCatching { poll() } } },
                            ) { Text(if (cur.drawOffer == me) "Tablas propuestas" else "Tablas") }
                            OutlinedButton(onClick = { showResign = true }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.5.dp, Accent)) { Text("Rendirse") }
                        }
                    }
                }
            }
            if (message.isNotEmpty()) Text(message, color = Color(0xFFFF6B6B), fontSize = 14.sp, textAlign = TextAlign.Center)
        }

    }

    // El tablero ocupa casi toda la pantalla. En horizontal (tablet) va a la izquierda a toda altura y los jugadores, el estado y
    // los botones quedan a su derecha; en vertical, jugadores arriba y abajo y los botones debajo.
    // En pantalla completa el tablero va en el centro de la pantalla, el rival arriba a la izquierda, tú abajo a la derecha y el botón
    // de salir arriba a la derecha.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (dark) Color.Black else Bg).then(if (fullScreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
    ) {
        if (fullScreen) {
            val land = maxWidth > maxHeight * 1.2f
            val side = (if (land) minOf(maxHeight - 8.dp, maxWidth - 560.dp) else minOf(maxWidth, maxHeight - 130.dp)).coerceAtLeast(200.dp)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center)) { Board(side) }
                Bar(opp, friend.username, friend.photo, Modifier.align(Alignment.TopStart).padding(8.dp).width(260.dp))
                Bar(mine, myName.ifEmpty { "Tú" }, myPhoto, Modifier.align(Alignment.BottomEnd).padding(8.dp).width(260.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { ExitFullScreen() }
            }
        } else if (maxWidth > maxHeight * 1.2f) {
            val side = minOf(maxHeight - 16.dp, maxWidth * 0.64f).coerceAtLeast(200.dp)
            Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Board(side)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Header()
                        Bar(opp, friend.username, friend.photo, Modifier.fillMaxWidth())
                    }
                    StatusPanel()
                    Bar(mine, myName.ifEmpty { "Tú" }, myPhoto, Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Header()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth - 8.dp, maxHeight - 104.dp, 900.dp).coerceAtLeast(200.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Bar(opp, friend.username, friend.photo, Modifier.width(side))
                        Spacer(Modifier.height(4.dp))
                        Board(side)
                        Spacer(Modifier.height(4.dp))
                        Bar(mine, myName.ifEmpty { "Tú" }, myPhoto, Modifier.width(side))
                    }
                }
                StatusPanel()
            }
        }
    }

    if (showResign) {
        AlertDialog(
            onDismissRequest = { showResign = false },
            title = { Text("Rendirse") },
            text = { Text("¿Seguro que quieres rendirte?") },
            confirmButton = { TextButton(onClick = { showResign = false; finish(if (myColor == 'w') 'b' else 'w', "resign") }) { Text("Rendirme") } },
            dismissButton = { TextButton(onClick = { showResign = false }) { Text("Cancelar") } },
        )
    }

    promoFor?.let { options ->
        AlertDialog(
            onDismissRequest = { promoFor = null },
            title = { Text("Coronar") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in charArrayOf('q', 'r', 'b', 'n')) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(LightSq).border(1.5.dp, Accent, RoundedCornerShape(12.dp))
                                .clickable { val m = options.firstOrNull { it.promo == p }; promoFor = null; if (m != null) commit(m) },
                            contentAlignment = Alignment.Center,
                        ) { PieceIcon(if (myColor == 'w') p.uppercaseChar() else p, 58.dp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { promoFor = null }) { Text("Cancelar") } },
        )
    }
}
