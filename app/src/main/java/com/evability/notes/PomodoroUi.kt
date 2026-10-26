package com.evability.notes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val RestColor = Color(0xFFA8E6CF)

/** Pantalla del método pomodoro: reloj grande, tiempos de estudio y descanso, y play/detener. */
@Composable
fun PomodoroScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var study by remember { mutableIntStateOf(Prefs.pomoStudy) }
    var rest by remember { mutableIntStateOf(Prefs.pomoRest) }
    val running = Pomodoro.phase != PomoPhase.IDLE

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }

    val totalMs = if (running) Pomodoro.phaseMs else study * 60_000L
    val remainingMs = if (running) (Pomodoro.endAt - now).coerceIn(0L, totalMs) else totalMs
    val progress = if (totalMs > 0) remainingMs / totalMs.toFloat() else 1f
    val resting = Pomodoro.phase == PomoPhase.REST
    val color = if (resting) RestColor else Accent
    val secs = ((remainingMs + 999) / 1000).toInt()

    // Desde Android 13 hay que pedir permiso para que se vea la notificación (el temporizador funciona igual sin él).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Pomodoro.start(ctx, study, rest)
    }
    fun play() {
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else Pomodoro.start(ctx, study, rest)
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Ternurín", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                val track = Outline
                Canvas(Modifier.fillMaxSize()) {
                    val w = 16.dp.toPx()
                    val inset = w / 2
                    val arcSize = Size(size.width - w, size.height - w)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
                    drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d:%02d".format(secs / 60, secs % 60), fontSize = 60.sp, fontWeight = FontWeight.Bold, color = OnSurface)
                    Text(
                        when (Pomodoro.phase) {
                            PomoPhase.STUDY -> "Estudio"
                            PomoPhase.REST -> "Descanso"
                            PomoPhase.IDLE -> "Listo"
                        },
                        fontSize = 18.sp, color = color, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            if (running) {
                Text("Ternurines completados: ${Pomodoro.completed}", fontSize = 16.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(6.dp))
                Text("Sigue funcionando aunque salgas de la app.", fontSize = 13.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { Pomodoro.stop(ctx) },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(min = 200.dp).height(56.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("  Detener", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Stepper("Estudio", study, 1, 180) { study = it }
                Spacer(Modifier.height(10.dp))
                Stepper("Descanso", rest, 1, 60) { rest = it }
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = { play() },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.widthIn(min = 200.dp).height(56.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("  Empezar", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text("Al terminar cada fase suena una alarma y empieza la siguiente hasta que pulses Detener.", fontSize = 12.sp, color = OnSurfaceDim)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(label, color = OnSurfaceDim, modifier = Modifier.widthIn(min = 82.dp))
        TextButton(onClick = { onChange((value - 5).coerceAtLeast(min)) }) { Text("−5") }
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−1") }
        Text("$value min", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.widthIn(min = 84.dp))
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+1") }
        TextButton(onClick = { onChange((value + 5).coerceAtMost(max)) }) { Text("+5") }
    }
}
