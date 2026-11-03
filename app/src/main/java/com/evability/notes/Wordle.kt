package com.evability.notes

import androidx.compose.material.icons.filled.DarkMode
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Wordle en español. La palabra del día sale de una lista guardada en la app según la fecha, así que es la misma para todos
 * (móvil, tablet y web) y no hace falta conexión. Las tildes no cuentan: «árbol» se escribe «arbol».
 */
object WordleGame {
    const val ROWS = 6
    private var answers: List<String> = emptyList()
    private var valid: Set<String> = emptySet()

    fun load(ctx: Context) {
        if (answers.isNotEmpty()) return
        fun read(res: Int) = ctx.resources.openRawResource(res).bufferedReader().readLines().map { it.trim() }.filter { it.length == 5 }
        answers = read(R.raw.wordle_answers)
        valid = read(R.raw.wordle_valid).toHashSet()
    }

    /** Número de días desde 1970 (igual en todas las plataformas). */
    fun today(): Long = LocalDate.now().toEpochDay()

    /** Palabra del día: 7919 es primo y no divide el tamaño de la lista, así que recorre todas sin repetir hasta que se acaban. */
    fun answerFor(day: Long): String = answers[((day * 7919) % answers.size).toInt()]

    /** Wordle nº 1 = 1 de enero de 2024. */
    fun number(day: Long): Long = day - 19723 + 1

    fun isValid(word: String) = word in valid

    /** 2 = en su sitio, 1 = está en otro sitio, 0 = no está. */
    fun evaluate(guess: String, answer: String): IntArray {
        val res = IntArray(5)
        val left = HashMap<Char, Int>()
        for (i in 0 until 5) {
            if (guess[i] == answer[i]) res[i] = 2 else left[answer[i]] = (left[answer[i]] ?: 0) + 1
        }
        for (i in 0 until 5) {
            if (res[i] == 2) continue
            val c = guess[i]
            if ((left[c] ?: 0) > 0) {
                res[i] = 1
                left[c] = left.getValue(c) - 1
            }
        }
        return res
    }

    // ───── partida del día (se guarda para no poder repetirla) ─────

    fun loadGuesses(day: Long): List<String> {
        val s = Prefs.wordleState
        val (d, rest) = s.split(":", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        return if (d == day.toString()) rest.split(",").filter { it.length == 5 } else emptyList()
    }

    fun saveGuesses(day: Long, guesses: List<String>) {
        Prefs.wordleState = "$day:" + guesses.joinToString(",")
    }

    fun wasPosted(day: Long, uid: String): Boolean {
        val (d, uids) = Prefs.wordlePosted.split("|", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        return d == day.toString() && uid in uids.split(",")
    }

    fun markPosted(day: Long, uid: String) {
        val (d, uids) = Prefs.wordlePosted.split("|", limit = 2).let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val list = if (d == day.toString()) uids.split(",").filter { it.isNotEmpty() } else emptyList()
        Prefs.wordlePosted = "$day|" + (list + uid).distinct().joinToString(",")
    }

    /** Lo que ve el amigo en el chat: el número de intentos y los cuadros de colores, sin letras (no desvela la palabra). */
    fun shareText(day: Long, guesses: List<String>, answer: String): String {
        val solved = guesses.lastOrNull() == answer
        val head = "Wordle #${number(day)} ${if (solved) guesses.size.toString() else "X"}/$ROWS"
        val grid = guesses.joinToString("\n") { g -> evaluate(g, answer).joinToString("") { if (it == 2) "🟩" else if (it == 1) "🟨" else "⬛" } }
        return "$head\n$grid"
    }
}

private val WGreen = Color(0xFF538D4E)
private val WYellow = Color(0xFFB59F3B)
private val WGray = Color(0xFF3A3A3C)

private fun statusColor(s: Int) = when (s) {
    2 -> WGreen
    1 -> WYellow
    0 -> WGray
    else -> Color.Transparent
}

/** Lista de minijuegos del clip del chat: Wordle y Evadrez (ajedrez) disponibles; los demás salen bloqueados. */
@Composable
fun MiniGamesDialog(onWordle: () -> Unit, onChess: () -> Unit, onDismiss: () -> Unit) {
    @Composable
    fun GameRow(name: String, subtitle: String?, enabled: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(Surface2.copy(alpha = if (enabled) 1f else 0.5f))
                .border(1.dp, if (enabled) Accent else Outline, shape)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (enabled) Icons.Default.GridView else Icons.Default.Lock, null, tint = if (enabled) Accent else OnSurfaceDim)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) OnSurface else OnSurfaceDim)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = OnSurfaceDim)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minijuegos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GameRow("Wordle", null, true, onWordle)
                GameRow("Evadrez", null, true, onChess)
                GameRow("Evapoly", "Próximamente", false) {}
                GameRow("???", "Próximamente", false) {}
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/** Pantalla del Wordle: 6 intentos para una palabra de 5 letras. Al acabar, el resultado se manda solo al chat desde el que se abrió. */
@Composable
fun WordleScreen(friend: Social.Profile?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val day = remember { WordleGame.today() }
    var ready by remember { mutableStateOf(false) }
    val guesses = remember { mutableStateListOf<String>() }
    var current by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { WordleGame.load(ctx) }
        guesses.addAll(WordleGame.loadGuesses(day))
        ready = true
    }
    val answer = if (ready) WordleGame.answerFor(day) else ""
    val solved = ready && guesses.lastOrNull() == answer
    val finished = ready && (solved || guesses.size >= WordleGame.ROWS)

    // Resultado al chat (una sola vez por chat y día).
    LaunchedEffect(finished) {
        val f = friend ?: return@LaunchedEffect
        if (!finished || WordleGame.wasPosted(day, f.uid)) return@LaunchedEffect
        try {
            Social.sendMessage(f.uid, WordleGame.shareText(day, guesses, answer))
            WordleGame.markPosted(day, f.uid)
        } catch (e: Exception) {
            message = e.message ?: "No se pudo enviar"
        }
    }

    fun press(k: String) {
        if (!ready || finished) return
        message = ""
        when (k) {
            "⌫" -> current = current.dropLast(1)
            "ENTER" -> when {
                current.length < 5 -> message = "Faltan letras"
                !WordleGame.isValid(current) -> message = "No está en la lista"
                else -> {
                    guesses.add(current)
                    WordleGame.saveGuesses(day, guesses)
                    current = ""
                }
            }
            else -> if (current.length < 5) current += k.lowercase()
        }
    }

    // Mejor estado conocido de cada letra, para colorear el teclado.
    val keyState = HashMap<Char, Int>()
    if (ready) for (g in guesses) WordleGame.evaluate(g, answer).forEachIndexed { i, s -> keyState[g[i]] = maxOf(keyState[g[i]] ?: -1, s) }

    var dark by remember { mutableStateOf(Prefs.gameDark) }
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Bg).statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Wordle", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { dark = !dark; Prefs.gameDark = dark }) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
        }

        Spacer(Modifier.height(8.dp))
        Column(Modifier.widthIn(max = 330.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until WordleGame.ROWS) {
                val word = guesses.getOrNull(r) ?: if (r == guesses.size && !finished) current else ""
                val eval = guesses.getOrNull(r)?.let { WordleGame.evaluate(it, answer) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until 5) {
                        val status = eval?.get(c) ?: -1
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .background(statusColor(status), RoundedCornerShape(8.dp))
                                .border(2.dp, if (status >= 0) statusColor(status) else if (c < word.length) OnSurfaceDim else Outline, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                word.getOrNull(c)?.uppercase() ?: "", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                color = if (status >= 0) Color.White else OnSurface,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            when {
                message.isNotEmpty() -> message
                solved -> "¡Resuelto en ${guesses.size}!"
                finished -> "Era: ${answer.uppercase()}"
                else -> ""
            },
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (solved) WGreen else OnSurface, textAlign = TextAlign.Center,
            modifier = Modifier.height(24.dp),
        )

        Spacer(Modifier.weight(1f))
        Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 6.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("QWERTYUIOP", "ASDFGHJKLÑ", "ZXCVBNM").forEachIndexed { rowIndex, letters ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (rowIndex == 2) KeyCap("ENTER", 1.6f, Surface2, null) { press("ENTER") }
                    letters.forEach { ch ->
                        val st = keyState[ch.lowercaseChar()] ?: -1
                        KeyCap(ch.toString(), 1f, if (st >= 0) statusColor(st) else Surface2, if (st >= 0) Color.White else null) { press(ch.toString()) }
                    }
                    if (rowIndex == 2) KeyCap("⌫", 1.6f, Surface2, null) { press("⌫") }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(label: String, weight: Float, bg: Color, fg: Color?, onClick: () -> Unit) {
    Box(
        Modifier.weight(weight).height(52.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label == "⌫") Icon(Icons.AutoMirrored.Filled.Backspace, "Borrar", tint = fg ?: OnSurface)
        else Text(label, fontSize = if (label.length > 1) 12.sp else 17.sp, fontWeight = FontWeight.Bold, color = fg ?: OnSurface)
    }
}
