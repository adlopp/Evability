package com.evability.notes

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val Es = Locale("es", "ES")
private val DayLetters = listOf("L", "M", "X", "J", "V", "S", "D")

private fun fmt(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return when {
        min >= 60 && min % 60 == 0 -> "${min / 60} h"
        min >= 60 -> "${min / 60} h ${min % 60} min"
        else -> "$min min"
    }
}

private fun dayTitle(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Hoy"
    today.minusDays(1) -> "Ayer"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Es)).replaceFirstChar { it.uppercase() }
}

/** Estadísticas del Ternurín: tiempo de estudio efectivo de cada día, con una gráfica semanal. */
@Composable
fun StudyStatsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val today = LocalDate.now()

    var data by remember { mutableStateOf(StudyLog.all(ctx)) }
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            data = StudyLog.all(ctx)
            tick = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    /** Lo guardado más el tramo que se está estudiando ahora mismo y aún no se ha guardado. */
    fun msOf(d: LocalDate): Long {
        val live = if (d == today && Pomodoro.phase == PomoPhase.STUDY) (tick - Pomodoro.studyMark).coerceAtLeast(0L) else 0L
        return (data[d] ?: 0L) + live
    }

    var weekOffset by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(today) }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    val days = List(7) { monday.plusDays(it.toLong()) }
    val values = days.map { if (it.isAfter(today)) 0L else msOf(it) }
    // Escala: al menos 1 h para que un día corto no parezca enorme.
    val top = maxOf(values.max(), 3_600_000L).toFloat()

    val weekTotal = values.sum()
    val elapsedDays = days.count { !it.isAfter(today) }.coerceAtLeast(1)
    val streak = run {
        var d = if (msOf(today) > 0) today else today.minusDays(1)
        var n = 0
        while (msOf(d) > 0) { n++; d = d.minusDays(1) }
        n
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Tu estudio", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            // Día seleccionado
            Column(
                Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(20.dp)).border(1.dp, Outline, RoundedCornerShape(20.dp)).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(dayTitle(selected, today), fontSize = 15.sp, color = OnSurfaceDim)
                Spacer(Modifier.height(4.dp))
                Text(fmt(if (selected.isAfter(today)) 0L else msOf(selected)), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Accent)
                Text("de estudio efectivo", fontSize = 13.sp, color = OnSurfaceDim)
            }

            Spacer(Modifier.height(18.dp))

            // Navegación de semanas
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekOffset--; selected = monday.minusWeeks(1) }) { Icon(Icons.Default.ChevronLeft, "Semana anterior", tint = OnSurface) }
                Text(
                    if (weekOffset == 0) "Esta semana"
                    else "${monday.format(DateTimeFormatter.ofPattern("d MMM", Es))} – ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM", Es))}",
                    modifier = Modifier.weight(1f), color = OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { weekOffset++; selected = monday.plusWeeks(1) }, enabled = weekOffset < 0) {
                    Icon(Icons.Default.ChevronRight, "Semana siguiente", tint = OnSurface.copy(alpha = if (weekOffset < 0) 1f else 0.25f))
                }
            }

            // Gráfica
            Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { i, d ->
                    val future = d.isAfter(today)
                    val frac = if (future || values[i] == 0L) 0f else (values[i] / top).coerceIn(0.03f, 1f)
                    val h by animateFloatAsState(frac, label = "bar")
                    val isSel = d == selected
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !future) { selected = d },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        // Pista de fondo
                        Box(Modifier.fillMaxWidth().fillMaxHeight().alpha(if (future) 0.35f else 1f).background(Surface1, RoundedCornerShape(12.dp)))
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(h)
                                .background(Accent.copy(alpha = if (isSel) 1f else 0.5f), RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEachIndexed { i, d ->
                    Text(
                        DayLetters[i], modifier = Modifier.weight(1f), fontSize = 14.sp,
                        fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (d == selected) Accent else OnSurfaceDim,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Semana", fmt(weekTotal), Modifier.weight(1f))
                StatCard("Media por día", fmt(weekTotal / elapsedDays), Modifier.weight(1f))
                StatCard("Racha", if (streak == 1) "1 día" else "$streak días", Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Solo cuenta el tiempo de estudio del Ternurín; los descansos no suman. El día se reinicia a las 00:00.",
                fontSize = 12.sp, color = OnSurfaceDim,
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.background(Surface1, RoundedCornerShape(16.dp)).border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = OnSurface)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 12.sp, color = OnSurfaceDim)
    }
}
