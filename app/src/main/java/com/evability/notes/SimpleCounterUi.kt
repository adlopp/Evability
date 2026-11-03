package com.evability.notes

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Contador simple: cuenta hacia arriba desde cero hasta que lo detengas. El tiempo cuenta como estudio en las estadísticas.
 * A las 0:00 vuelve a cero (sigue en marcha) y el tiempo de cada día se apunta en su día.
 * Se guarda la hora de inicio, así que sigue bien aunque salgas de la pantalla o cierres la app.
 */
object SimpleCounter {
    var running by androidx.compose.runtime.mutableStateOf(false)
        private set

    private const val MAX_GAP = 2 * 3_600_000L

    fun load() {
        running = Prefs.simpleStart > 0L
    }

    fun start() {
        val now = System.currentTimeMillis()
        Prefs.simpleStart = now
        Prefs.simpleMark = now
        running = true
    }

    /** Guarda en [StudyLog] el tiempo contado desde la última vez. */
    fun flush(ctx: Context) {
        if (!running) return
        val now = System.currentTimeMillis()
        // Si pasaron más de 2 h sin tocar la app (contador olvidado), solo se apuntan las primeras 2 h.
        StudyLog.add(ctx, Prefs.simpleMark, minOf(now, Prefs.simpleMark + MAX_GAP))
        Prefs.simpleMark = now
    }

    fun stop(ctx: Context) {
        flush(ctx)
        Prefs.simpleStart = 0L
        Prefs.simpleMark = 0L
        running = false
    }

    private fun startOfDay(t: Long): Long =
        ZoneId.systemDefault().let { z -> Instant.ofEpochMilli(t).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli() }

    /** Tiempo que marca el contador ahora: a las 0:00 vuelve a cero aunque siga en marcha. */
    fun elapsed(now: Long): Long = if (running) (now - maxOf(Prefs.simpleStart, startOfDay(now))).coerceAtLeast(0L) else 0L

    /** Tiempo contado que todavía no está guardado en [StudyLog] (para que las estadísticas lo vean al momento). */
    fun unflushed(now: Long): Long = if (running) (now - Prefs.simpleMark).coerceAtLeast(0L) else 0L
}

/** Al pulsar «Tiempo» sin nada en marcha: elegir entre el método Ternurín y el contador simple. */
@Composable
fun TimeChooserDialog(onTernurin: () -> Unit, onSimple: () -> Unit, onDismiss: () -> Unit) {
    @Composable
    fun Option(title: String, subtitle: String, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, Accent),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, color = OnSurfaceDim)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tiempo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Cómo quieres medir tu tiempo?", color = OnSurfaceDim)
                Option("Método Ternurín", "Estudio y descanso con alarma", onTernurin)
                Option("Contador Simple", "Cuenta hacia arriba hasta que lo detengas", onSimple)
                Text("Mientras uno esté en marcha no se puede abrir el otro.", fontSize = 12.sp, color = OnSurfaceDim)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Pantalla del contador simple: el mismo reloj que el Ternurín, con iniciar/detener y el medidor de estudio arriba a la derecha. */
@Composable
fun SimpleCounterScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val running = SimpleCounter.running
    var showStats by remember { mutableStateOf(false) }
    var wall by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(running) {
        var lastFlush = 0L
        while (running) {
            wall = System.currentTimeMillis()
            if (wall - lastFlush > 10_000) {
                SimpleCounter.flush(ctx)
                lastFlush = wall
            }
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { SimpleCounter.flush(ctx) } }

    // Mientras cuenta, la pantalla no se apaga.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    if (showStats) {
        StudyStatsScreen { showStats = false }
        return
    }

    val elapsed = SimpleCounter.elapsed(wall)
    val secsTotal = (elapsed / 1000).toInt()
    val progress = if (running) (elapsed % 3_600_000L) / 3_600_000f else 0f // una vuelta al anillo por hora

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Contador simple", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { showStats = true },
                modifier = Modifier.padding(end = 4.dp).size(46.dp).clip(CircleShape).background(Surface1).border(1.dp, Outline, CircleShape),
            ) { Icon(Icons.Default.BarChart, contentDescription = "Tu estudio", tint = Accent) }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                val track = Outline
                val color = Accent
                Canvas(Modifier.fillMaxSize()) {
                    val w = 16.dp.toPx()
                    val inset = w / 2
                    val arcSize = Size(size.width - w, size.height - w)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
                    drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%02d:%02d:%02d".format(secsTotal / 3600, secsTotal / 60 % 60, secsTotal % 60),
                        fontSize = 48.sp, fontWeight = FontWeight.Bold, color = OnSurface,
                    )
                    Text(if (running) "Contando" else "Listo", fontSize = 18.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(28.dp))

            Button(
                onClick = { if (running) SimpleCounter.stop(ctx) else SimpleCounter.start() },
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(min = 200.dp).height(56.dp),
            ) {
                Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                Text(if (running) "  Detener" else "  Iniciar", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (running) "Sigue contando aunque salgas de esta pantalla. Hasta que no lo detengas no puedes abrir el método Ternurín."
                else "Cuenta hacia arriba hasta que lo detengas. A las 0:00 vuelve a cero y el tiempo cuenta en tus estadísticas de estudio.",
                fontSize = 12.sp, color = OnSurfaceDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}
