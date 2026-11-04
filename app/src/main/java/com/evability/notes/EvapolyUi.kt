package com.evability.notes

import androidx.compose.foundation.layout.PaddingValues
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.spring
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
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

/** Posición de la casilla `i` en la cuadrícula, en % del lado: [izquierda, arriba, ancho, alto]. */
private fun cellOf(i: Int): FloatArray {
    val k = 12f
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

private class DiceView(val d1: Int, val d2: Int, val rolling: Boolean, val d3: Int = 0)
/** Efecto visual sobre el tablero: coordenadas en % del tablero (x0,y0 → x1,y1). */
private class Fx(val id: Long, val kind: String, val x0: Float, val y0: Float, val x1: Float = x0, val y1: Float = y0, val n: Int = 5, val color: Color = Color.White, val ms: Int = 900)

private fun bounceOut(t: Float): Float = when {
    t < 1f / 2.75f -> 7.5625f * t * t
    t < 2f / 2.75f -> { val u = t - 1.5f / 2.75f; 7.5625f * u * u + 0.75f }
    t < 2.5f / 2.75f -> { val u = t - 2.25f / 2.75f; 7.5625f * u * u + 0.9375f }
    else -> { val u = t - 2.625f / 2.75f; 7.5625f * u * u + 0.984375f }
}

@Composable
private fun FxView(f: Fx, g: Dp) {
    val t = remember(f.id) { Animatable(0f) }
    LaunchedEffect(f.id) { t.animateTo(1f, tween(f.ms, easing = LinearEasing)) }
    val p = t.value
    val rs = remember(f.id) { val r = java.util.Random(f.id); FloatArray(f.n * 4 + 4) { r.nextFloat() } }
    fun lerp(a: Float, b: Float, q: Float) = a + (b - a) * q
    when (f.kind) {
        "train" -> {
            val q = FastOutSlowInEasing.transform(p)
            Canvas(Modifier.size(g)) {
                for (j in 0..9) {
                    val at = j / 10f * 0.9f
                    val age = p - at
                    if (age in 0f..0.28f) {
                        val a = age / 0.28f
                        drawCircle(Color.White.copy(alpha = 0.8f * (1 - a)), size.minDimension * (0.012f + 0.045f * a), Offset(size.width * lerp(f.x0, f.x1, FastOutSlowInEasing.transform(at)) / 100f, size.height * (lerp(f.y0, f.y1, FastOutSlowInEasing.transform(at)) - 3f - a * 4f) / 100f))
                    }
                }
            }
            val sz = g * 0.09f
            PolyIcon("train", sz, Modifier.offset(g * (lerp(f.x0, f.x1, q) / 100f) - sz / 2, g * (lerp(f.y0, f.y1, q) / 100f) - sz / 2).graphicsLayer { alpha = minOf(1f, p / 0.12f, (1f - p) / 0.12f).coerceIn(0f, 1f) })
        }
        "plunger" -> {
            val sz = g * 0.09f
            val q = FastOutSlowInEasing.transform(if (p < 0.45f) p / 0.45f else 1f - (p - 0.45f) / 0.55f)
            PolyIcon("plunger", sz, Modifier.offset(g * (lerp(f.x0, f.x1, q) / 100f) - sz / 2, g * (lerp(f.y0, f.y1, q) / 100f) - sz / 2).graphicsLayer { alpha = minOf(1f, p / 0.12f, (1f - p) / 0.2f).coerceIn(0f, 1f); rotationZ = -20f * q })
        }
        "hammer" -> {
            val sz = g * 0.22f
            val ang = when { p < 0.33f -> -60f + 72f * (p / 0.33f) * (p / 0.33f); p < 0.42f -> 12f - 18f * ((p - 0.33f) / 0.09f); p < 0.55f -> -6f + 10f * ((p - 0.42f) / 0.13f); else -> 4f * (1f - (p - 0.55f) / 0.45f) }
            PolyIcon("hammer", sz, Modifier.offset(g * 0.5f - sz / 2, g * 0.46f - sz / 2).graphicsLayer {
                rotationZ = ang; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.8f, 0.85f)
                alpha = minOf(1f, p / 0.15f, (1f - p) / 0.2f).coerceIn(0f, 1f)
            })
        }
        "drop" -> {
            val sz = g * 0.075f
            val q = bounceOut((p / 0.6f).coerceIn(0f, 1f))
            PolyIcon(if (f.n == 5) "hotel" else "house", sz, Modifier.offset(g * (f.x0 / 100f) - sz / 2, g * ((f.y0 - 14f * (1f - q)) / 100f) - sz / 2).graphicsLayer { alpha = if (p < 0.7f) 1f else ((1f - p) / 0.3f).coerceIn(0f, 1f) })
        }
        else -> Canvas(Modifier.size(g)) {
            val m = size.minDimension
            fun px(v: Float) = m * v / 100f
            when (f.kind) {
                "puff" -> for (k in 0 until f.n) {
                    val dx = (rs[k * 4] - 0.5f) * 7f; val dy = -rs[k * 4 + 1] * 4f
                    val q = ((p - k * 0.05f) / (1f - k * 0.05f)).coerceIn(0f, 1f)
                    if (q > 0f) drawCircle(Color(0xFFEDE6DB).copy(alpha = 0.9f * (1f - q)), px(2f * (0.3f + 1.5f * q)), Offset(px(f.x0 + dx * q), px(f.y0 + dy * q - 2.5f * q)))
                }
                "spark" -> for (k in 0 until f.n) {
                    val a = k.toFloat() / f.n * 2f * PI.toFloat()
                    drawCircle(f.color.copy(alpha = 1f - p), px(0.8f * (1f - p) + 0.2f), Offset(px(f.x0 + kotlin.math.cos(a) * 5f * p), px(f.y0 + sin(a) * 5f * p)))
                }
                "cracks" -> {
                    val reveal = (p / 0.25f).coerceIn(0f, 1f)
                    val fade = if (p > 0.72f) 1f - (p - 0.72f) / 0.28f else 1f
                    for (c in 0 until 4) {
                        val rr = java.util.Random(f.id * 31 + c)
                        var x = 15f + rr.nextFloat() * 70f
                        var y = 15f + rr.nextFloat() * 70f
                        val pts = arrayListOf(Offset(px(x), px(y)))
                        repeat(8) { x += (rr.nextFloat() - 0.5f) * 12f; y += (rr.nextFloat() - 0.2f) * 12f; pts.add(Offset(px(x), px(y))) }
                        val shown = reveal * 8f
                        for (k in 0 until 8) {
                            val part = (shown - k).coerceIn(0f, 1f)
                            if (part <= 0f) break
                            val a = pts[k]; val b = pts[k + 1]
                            drawLine(Color(0xFF2B1D12).copy(alpha = fade), a, Offset(a.x + (b.x - a.x) * part, a.y + (b.y - a.y) * part), strokeWidth = px(0.4f), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
                "ghost" -> drawCircle(f.color.copy(alpha = 0.45f * (1f - p)), px(1.7f * (1f - 0.7f * p)), Offset(px(f.x0), px(f.y0)))
                "coins" -> for (k in 0 until f.n) {
                    val q = ((p - k * 0.1f) / 0.6f).coerceIn(0f, 1f)
                    if (q > 0f && q < 1f) {
                        val c = Offset(px(lerp(f.x0, f.x1, q)), px(lerp(f.y0, f.y1, q) - 8f * sin(PI.toFloat() * q)))
                        drawCircle(Color(0xFFB8860B), px(1.4f), c)
                        drawCircle(Color(0xFFF4B400), px(1.15f), c)
                        drawCircle(Color(0xFFFFF3B0), px(0.45f), c + Offset(-px(0.3f), -px(0.3f)))
                    }
                }
                "confetti" -> {
                    val cols = listOf(Color(0xFFE0483F), Color(0xFFF4B400), Color(0xFF3AA8E8), Color(0xFF4CC06A), Color(0xFFB25BD8), Color(0xFFFF8A1F))
                    for (k in 0 until f.n) {
                        val q = ((p - rs[k * 4 + 2] * 0.25f) / 0.75f).coerceIn(0f, 1f)
                        if (q > 0f && q < 1f) {
                            val x = rs[k * 4] * 100f + (rs[k * 4 + 1] - 0.5f) * 24f * q
                            val y = -4f + 108f * q
                            withTransform({ rotate(360f * q * (1f + rs[k * 4 + 3]), Offset(px(x), px(y))) }) { drawRect(cols[k % cols.size], Offset(px(x) - px(0.7f), px(y) - px(1.2f)), androidx.compose.ui.geometry.Size(px(1.4f), px(2.4f))) }
                        }
                    }
                }
            }
        }
    }
}

private class MoneyPop(val text: String, val seat: Int, val kind: Int, val id: Long)
private class CardFx(val kind: String, val title: String, val who: String, val text: String)
private class BannerInfo(val text: String, val seat: Int)
private class Delta(val seat: Int, val text: String, val up: Boolean, val id: Long)

// ───── eventos aleatorios ─────
private class EvCard(val kind: Int, val start: Boolean, val peek: Boolean = false, val hold: Boolean = false)
private val EV_IDS = listOf("dog", "snow", "rain", "quake", "crisis", "balance")
private val EV_NAMES = listOf("Perro sarnoso", "Nieve", "Lluvia", "Terremoto", "Crisis económica", "Equilibrio")
private val EV_DESC = listOf(
    "Durante 5 turnos de cada jugador, un perro avanza 4 casillas por turno. Si cae en tu casilla, o tú caes en la suya, te muerde y te quita 150 €.",
    "Está nevando, así que te mueves más lento: durante 5 turnos de cada jugador solo se tira un dado en lugar de dos.",
    "Está lloviendo, el suelo está mojado y te resbalas: durante 5 turnos de cada jugador cada dado saca 4 o más, y llegas más lejos.",
    "Durante 5 turnos de cada jugador, al acabar cada turno hay un 15 % de que tiemble la tierra y se derrumbe una casa de cada jugador. Los hoteles se salvan, y quien no tenga casas se libra.",
    "Durante 5 turnos de cada jugador, todas las propiedades suben 50 € para comprarlas (Calle Pollo pasa de 220 € a 270 €) y, al caer en una de otro jugador, pagas 25 € extra de alquiler.",
    "El jugador con menos patrimonio (dinero, propiedades y construcciones) recibe 500 €. Si hay empate, cobran todos los empatados.",
)

/** Nieve o lluvia suaves sobre el tablero: el mapa se vuelve invernal o mojado. */
@Composable
private fun WeatherView(kind: String) {
    val tr = rememberInfiniteTransition(label = "clima")
    val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(if (kind == "snow") 9000 else 1100, easing = LinearEasing)), label = "tiempo")
    val fade = remember(kind) { Animatable(0f) }
    LaunchedEffect(kind) { fade.animateTo(1f, tween(1400)) }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val w = size.width; val h = size.height
        if (kind == "snow") {
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.18f), Color.Transparent), center = Offset(w / 2f, h / 2f), radius = w * 0.56f))
            drawRect(Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.55f), 0.14f to Color.Transparent, 0.86f to Color.Transparent, 1f to Color.White.copy(alpha = 0.55f)))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.5f), 0.12f to Color.Transparent, 0.88f to Color.Transparent, 1f to Color.White.copy(alpha = 0.5f)))
            for (i in 0 until 38) {
                val sp = 1 + i % 3
                val y = (t * sp + (i * 0.37f) % 1f) % 1f
                val x = w * ((i * 0.618f) % 1f) + sin((y + i) * 6.2832f) * w * 0.02f
                drawCircle(Color.White.copy(alpha = 0.92f), w * (0.004f + 0.003f * (i % 3)), Offset(x, y * h))
            }
        } else {
            drawRect(Color(0x1A4669A0))
            val sheen = ((t * 0.9f) % 1f) * 2.4f - 0.7f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.15f), Color.Transparent), start = Offset(w * (sheen - 0.25f), 0f), end = Offset(w * (sheen + 0.25f), h)))
            for (i in 0 until 30) {
                val y = (t + (i * 0.37f) % 1f) % 1f
                val x = w * ((i * 0.618f) % 1f)
                drawLine(Color(0xCCAACDF5), Offset(x, y * h), Offset(x - w * 0.008f, y * h + h * (0.035f + 0.02f * (i % 3))), strokeWidth = w * 0.0022f)
            }
        }
    }
}

/** Cartel de «Evento activado» (o de fin del evento) en el centro del tablero; se cierra al tocarlo. */
@Composable
private fun EventCardView(c: EvCard, side: Dp, onDone: () -> Unit) {
    val t = remember(c) { Animatable(0f) }
    LaunchedEffect(c) { t.animateTo(1f, spring(0.55f, 300f)); delay(if (c.hold) 60000L else if (c.start) 4800L else 2000L); onDone() }
    val shape = RoundedCornerShape(side * 0.05f)
    val fg = if (c.start) Color.White else Color(0xFF3B2A00)
    val fsz = with(LocalDensity.current) { (side / 31f).toSp() }
    Box(Modifier.fillMaxSize().clickable { onDone() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.74f).graphicsLayer { scaleX = t.value; scaleY = t.value; alpha = t.value.coerceIn(0f, 1f) }
                .background(Brush.linearGradient(if (c.start) listOf(Color(0xFF3FBF74), Color(0xFF1B7F45)) else listOf(Color(0xFFFFC94A), Color(0xFFE8970E))), shape)
                .border(side * 0.007f, Color.White.copy(alpha = 0.4f), shape).padding(side * 0.04f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PolyIcon(if (c.start) EV_IDS[c.kind] else if (c.kind == 0) "dog" else if (c.kind == 3) "quake" else if (c.kind == 4) "crisis" else "sun", side * 0.17f)
            Text(if (c.peek) "EVENTO ACTIVO" else if (c.start) "EVENTO ACTIVADO" else "EVENTO TERMINADO", color = fg.copy(alpha = 0.92f), fontSize = fsz * 0.9f, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(if (c.start) EV_NAMES[c.kind] else if (c.kind == 0) "El perro ha desaparecido" else if (c.kind == 3) "La tierra ha dejado de temblar" else if (c.kind == 4) "La crisis económica ha terminado" else "Ha salido el sol", color = fg, fontSize = fsz * 1.55f, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = fsz * 1.7f)
            if (c.start) Text(EV_DESC[c.kind], color = fg, fontSize = fsz * 0.95f, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = fsz * 1.3f, modifier = Modifier.padding(top = side * 0.01f))
        }
    }
}

/** Tamaño de letra (en «em» del tablero) con el que el nombre cabe entero en `w` × `h` em, partido en líneas. */
private fun fitName(text: String, w: Float, h: Float): Float {
    val words = text.split(" ")
    val lw = words.maxOf { it.length }
    var fs = 1.12f
    while (fs >= 0.4f) {
        val per = maxOf(1, (w / (fs * 0.58f)).toInt())
        if (lw <= per) {
            var lines = 1
            var cur = 0
            for (wd in words) {
                val add = if (cur > 0) cur + 1 + wd.length else wd.length
                if (add <= per) cur = add else { lines++; cur = wd.length }
            }
            if (lines * fs * 1.1f <= h) return fs
        }
        fs -= 0.03f
    }
    return 0.4f
}

private fun lum(c: Color) = c.red * 0.3f + c.green * 0.59f + c.blue * 0.11f

private fun backOut(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1
    val t = x - 1
    return 1 + c3 * t * t * t + c1 * t * t
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParts(parts: List<PolyData.TokPart>) {
    for (p in parts) {
        p.fill?.let { drawPath(p.path, it, alpha = p.alpha) }
        p.stroke?.let { drawPath(p.path, it, alpha = p.alpha, style = Stroke(width = p.width, cap = PolyData.cap, join = PolyData.join)) }
    }
}

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
private fun Die(n: Int, size: Dp, rot: Float, scale: Float = 1f) {
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rot; scaleX = scale; scaleY = scale }) {
        val r = androidx.compose.ui.geometry.CornerRadius(this.size.width * 0.16f)
        drawRoundRect(Color.Black.copy(alpha = 0.25f), topLeft = Offset(0f, this.size.width * 0.05f), cornerRadius = r)
        drawRoundRect(Brush.linearGradient(listOf(Color.White, Color(0xFFE6E6EA))), cornerRadius = r)
        val cell = this.size.width / 3f
        for (idx in PIPS[n.coerceIn(1, 6)].orEmpty()) {
            drawCircle(Ink, radius = cell * 0.17f, center = Offset((idx % 3 + 0.5f) * cell, (idx / 3 + 0.5f) * cell))
        }
    }
}

@Composable
private fun SquareView(
    i: Int, q: Poly.Sq, gside: Dp, u: Dp, st: Poly.State, data: Poly.Data, hl: Boolean,
    zoomed: Boolean = false, onHold: (Boolean) -> Unit = {}, onClick: () -> Unit = {}, inlineW: Dp? = null, focusSeat: Int = -1,
) {
    val c = cellOf(i)
    val where = sideOf(i)
    // dentro de la casilla todo se dibuja como en las de abajo; las de los lados se giran (como en el tablero de verdad)
    val sideways = where == "left" || where == "right"
    val cwEm = c[2] * 0.97f * 0.62f
    val chEm = c[3] * 0.97f * 0.62f
    val zs = if (zoomed) 2.6f else 1f
    val uu = if (inlineW != null) inlineW / (if (sideways) chEm else cwEm) else u * zs
    val fs = with(LocalDensity.current) { uu.toSp() }
    val cw = if (inlineW != null) uu * cwEm else gside * (c[2] / 100f) * zs
    val ch = if (inlineW != null) uu * chEm else gside * (c[3] / 100f) * zs
    val iw = if (sideways) ch else cw
    val ih = if (sideways) cw else ch
    val upright = (zoomed || inlineW != null) && sideways // al ampliarla (o en la tira) se ve recta, como las de abajo
    val ow = if (upright) iw else cw
    val oh = if (upright) ih else ch
    val wEm = iw.value / uu.value
    val hEm = ih.value / uu.value
    val band = q.g?.let { hex(data.groups[it] ?: "#888888") }
    val special = q.t == "chance" || q.t == "chest"
    val bg = when (q.t) { "go" -> Color(0xFFF6E3B4); "gotojail" -> Color(0xFFF4C7C3); "jail" -> Color(0xFFF1E1C9); "parking" -> Color(0xFFD9E8F5); else -> Color(0xFFF3F7EE) }
    val owner = st.own[i]
    val lvl = st.lvl[i]
    val ownable = q.t == "street" || q.t == "station" || q.t == "utility"
    // foco: mías con brillo, las de otros apagadas y, en los colores en los que tengo alguna, las que me faltan con contorno punteado
    val fOn = focusSeat >= 0 && !zoomed && inlineW == null
    val fIsMine = fOn && ownable && owner == focusSeat
    val fDim = fOn && q.t != "go" && q.t != "jail" && q.t != "parking" && q.t != "gotojail" && !fIsMine
    val bandEm = if (band != null) 1.5f else 0f // banda de color fina, con el precio centrado
    val ownEm = if (ownable) 2.4f else 0f // franja del dueño (lleva las casas); Suerte, Caja, impuestos y esquinas no la tienen y su contenido queda centrado
    val icoEm = if (q.i == null) 0f else if (where == "corner") 4.2f else if (special) 2.9f else 2.0f
    val prEm = if (q.p > 0 && band == null && (q.t != "station" || zoomed)) 1.1f else 0f
    val oneWord = !q.n.contains(' ') && where != "corner" && !special // una sola palabra: una sola línea, con la letra justa
    val nameEm = if (where == "corner") 1.35f else if (special) 1f else fitName(q.n, wEm - 0.7f, maxOf(1f, hEm - bandEm - ownEm - icoEm - prEm - 0.3f)).let { f -> if (oneWord) minOf(f, (wEm - 0.7f) / (q.n.length * 0.66f)) else f }
    Box(
        (if (inlineW != null) Modifier else if (zoomed) Modifier.offset((gside - ow) / 2f, (gside - oh) / 2f) else Modifier.offset(gside * (c[0] / 100f), gside * (c[1] / 100f)))
            .size(ow, oh).alpha(if (fDim) 0.4f else 1f)
            .background(bg).border(if (zoomed) 2.dp else 0.7.dp, Ink.copy(alpha = 0.75f))
            .then(if (inlineW != null) Modifier.clickable { onClick() } else if (zoomed) Modifier else Modifier.pointerInput(i) { detectTapGestures(onPress = { tryAwaitRelease(); onHold(false) }, onLongPress = { onHold(true) }, onTap = { onClick() }) }),
    ) {
    val ma = if (st.mort[i]) 0.55f else 1f
        val bandPart: @Composable ColumnScope.() -> Unit = {
            if (band != null) {
                Box(
                    Modifier.fillMaxWidth().height(uu * bandEm).alpha(ma).background(band).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.14f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        Poly.eur(if (ownable) st.buyPrice(i) else q.p), color = if (lum(band) > 0.62f) Ink else Color.White, fontSize = fs * 0.9f, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false), lineHeight = fs * 0.9f,
                            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both),
                        ),
                    )
                }
            }
        }
        val bodyPart: @Composable ColumnScope.() -> Unit = {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = uu * 0.3f).alpha(ma), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    PolyIcon(q.i, uu * icoEm)
                    Text(
                        q.n, color = Ink, fontSize = fs * nameEm, fontWeight = if (special) FontWeight.Medium else FontWeight.ExtraBold,
                        textAlign = TextAlign.Center, lineHeight = fs * nameEm * 1.1f, maxLines = if (oneWord) 1 else Int.MAX_VALUE, softWrap = !oneWord,
                    )
                    if (prEm > 0f) Text(Poly.eur(if (ownable) st.buyPrice(i) else q.p), color = if (ownable && st.evk == "crisis") Color(0xFFC62828) else Ink, fontSize = fs * 0.92f, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val ownPart: @Composable ColumnScope.() -> Unit = {
            if (ownable) Box(
                Modifier.fillMaxWidth().height(uu * ownEm).background(if (owner >= 0) PolyData.colors[owner] else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (lvl > 0) Row(horizontalArrangement = Arrangement.spacedBy(uu * 0.15f), verticalAlignment = Alignment.CenterVertically) {
                    if (lvl == 5) PolyIcon("hotel", uu * 1.5f)
                    else repeat(lvl) { PolyIcon("house", uu * 1.15f) }
                }
            }
        }

    // hipotecada: la franja del dueño (con sus casas) se dibuja aparte, encima del tono rojo, para que no se apague
    val ownSlot: @Composable ColumnScope.() -> Unit = { if (st.mort[i]) { if (ownable) Spacer(Modifier.fillMaxWidth().height(uu * ownEm)) } else ownPart() }
    Box(Modifier.align(Alignment.Center).requiredSize(iw, ih).rotate(if (upright) 0f else if (where == "left") 90f else if (where == "right") -90f else 0f)) {
        Column(Modifier.fillMaxSize()) {
            if (where == "top") { ownSlot(); bodyPart(); bandPart() } else { bandPart(); bodyPart(); ownSlot() }
        }
    }
        if (st.mort[i]) Box(Modifier.fillMaxSize().background(Color(0x99E02020)))
        if (st.mort[i] && ownable) Box(Modifier.align(Alignment.Center).requiredSize(iw, ih).rotate(if (upright) 0f else if (where == "left") 90f else if (where == "right") -90f else 0f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = if (where == "top") Arrangement.Top else Arrangement.Bottom) { ownPart() }
        }
        if (st.mort[i]) Canvas(Modifier.fillMaxSize()) {
            val w = uu.toPx() * 0.28f
            val m = w * 1.2f
            drawLine(Color(0xFF8E0000).copy(alpha = 0.9f), Offset(m, m), Offset(size.width - m, size.height - m), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFF8E0000).copy(alpha = 0.9f), Offset(size.width - m, m), Offset(m, size.height - m), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (fIsMine) {
            val ft = rememberInfiniteTransition(label = "foco")
            val fp by ft.animateFloat(0f, 1f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "brillo")
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.1f + 0.1f * fp)))
        }
        if (ownable) Box(Modifier.fillMaxSize().border(uu * (if (owner >= 0) 0.34f else 0.2f), band ?: Color(0xFF7D8791)))
        if (st.fence[i] >= 0) PolyIcon("fence", minOf(ow, oh) * 0.85f, Modifier.align(Alignment.Center))
        if (hl) {
            val pulse = rememberInfiniteTransition(label = "ruta")
            val a by pulse.animateFloat(0.32f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ruta")
            Box(Modifier.fillMaxSize().background(Color(0xFFFFD600).copy(alpha = a)).border(uu * 0.28f, Color(0xFFFFC400)))
        }
    }
}

/** Animación de Suerte, Caja o La Peza: el cofre se abre (o gira la «?») y sale la carta con lo que ha pasado. La ven todos. */
@Composable
private fun CardFxView(fx: CardFx, side: Dp, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val t = remember(fx) { Animatable(0f) }
    LaunchedEffect(fx) { t.animateTo(1f, tween(5400, easing = LinearEasing)); onDone() }
    val tv = t.value
    fun seg(a: Float, b: Float) = ((tv - a) / (b - a)).coerceIn(0f, 1f)
    val bg = minOf(seg(0f, 0.04f), 1f - seg(0.94f, 1f))
    val pop = backOut(seg(0f, 0.12f))
    val artFade = 1f - seg(0.42f, 0.5f)
    val card = backOut(seg(0.26f, 0.44f))
    val accent = when (fx.kind) { "chest" -> Color(0xFF1D4E9E); "chance" -> Color(0xFFF08A24); else -> Color(0xFFD9A21B) }
    val icons = remember { PolyData.icons(ctx) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f * bg)).clickable { onDone() }, contentAlignment = Alignment.Center) {
        if (artFade > 0.01f) {
            val chest = icons["chest"].orEmpty()
            val other = icons[if (fx.kind == "chance") "chance" else "tax"].orEmpty()
            Canvas(
                Modifier.size(side * 0.38f).graphicsLayer {
                    val sc = pop * (0.7f + 0.3f * artFade)
                    scaleX = sc; scaleY = sc; alpha = artFade * bg
                    rotationY = if (fx.kind == "chance") 720f * (1f - (1f - seg(0f, 0.22f)).let { it * it }) else 0f
                    cameraDistance = 14f * density.density
                },
            ) {
                val s = this.size.minDimension / 100f
                withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                    if (fx.kind == "chest" && chest.size >= 6) {
                        drawParts(listOf(chest[0], chest[2], chest[3], chest[4]))
                        val open = backOut(seg(0.12f, 0.27f))
                        withTransform({ translate(0f, -14f * open); rotate(-32f * open, pivot = Offset(85f, 100f)) }) { drawParts(listOf(chest[1], chest[5])) }
                    } else drawParts(other)
                }
            }
        }
        val fsz = with(density) { (side / 33f).toSp() }
        Column(
            Modifier.fillMaxWidth(0.62f).graphicsLayer { scaleX = card; scaleY = card; alpha = seg(0.26f, 0.34f); translationY = (1f - card) * 80f }
                .clip(RoundedCornerShape(side * 0.035f)).background(Color.White),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(side * 0.017f).background(accent))
            Column(Modifier.padding(horizontal = side * 0.035f, vertical = side * 0.04f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fx.title, color = accent, fontSize = fsz * 1.35f, fontWeight = FontWeight.ExtraBold)
                Text(fx.who, color = Ink.copy(alpha = 0.6f), fontSize = fsz * 0.85f)
                Spacer(Modifier.height(side * 0.015f))
                Text(fx.text, color = Ink, fontSize = fsz * 1.05f, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = fsz * 1.3f)
            }
        }
    }
}

@Composable
private fun BannerView(b: BannerInfo, side: Dp) {
    val t = remember(b) { Animatable(0f) }
    LaunchedEffect(b) { t.animateTo(1f, tween(1700, easing = LinearEasing)) }
    val tv = t.value
    val a = minOf((tv / 0.15f).coerceIn(0f, 1f), 1f - ((tv - 0.8f) / 0.2f).coerceIn(0f, 1f))
    val sc = 0.6f + 0.48f * backOut((tv / 0.2f).coerceIn(0f, 1f)).coerceAtMost(1.08f)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            b.text.uppercase(), color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
            fontSize = with(LocalDensity.current) { (side / 22f).toSp() },
            modifier = Modifier.graphicsLayer { alpha = a; scaleX = sc; scaleY = sc; translationY = -tv * 40f }
                .background(PolyData.colors[b.seat], RoundedCornerShape(50)).padding(horizontal = side * 0.045f, vertical = side * 0.014f),
        )
    }
}

@Composable
private fun PolyBoard(
    side: Dp, data: Poly.Data, st: Poly.State, viewPos: List<Int>, tokenOf: (Int) -> Int, dice: DiceView?, pathSet: Set<Int>, slowSeat: Int, police: Boolean, landing: Int?,
    cardFx: CardFx?, banner: BannerInfo?, pops: List<MoneyPop>, fxs: List<Fx>, hopSeat: Int, poof: List<Int>, shakeKey: Int, focusSeat: Int, evk: String, dogAt: Int, evCard: EvCard?, onEvDone: () -> Unit, onEvPeek: (Int) -> Unit, onCardDone: () -> Unit, onSquare: (Int) -> Unit,
) {
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(shakeKey) { if (shakeKey > 0) { for (v in listOf(7f, -7f, 5f, -4f, 2f, 0f)) shakeX.animateTo(v, tween(55)) } }
    val density = LocalDensity.current
    val u = side / 62f
    val g = side * 0.97f
    val frame = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(side).graphicsLayer { translationX = shakeX.value }.clip(frame)
            .background(Brush.radialGradient(listOf(Color(0xFFDCEFDD), Color(0xFFC6E1CA), Color(0xFFB3D3B8)), center = Offset.Unspecified, radius = with(density) { side.toPx() } * 0.75f))
            .border(2.dp, Ink, frame),
    ) {
        Box(Modifier.offset(side * 0.015f, side * 0.015f).size(g)) {
            var zoom by remember { mutableStateOf<Int?>(null) }
            for (i in 0 until 40) SquareView(i, data.squares[i], g, u, st, data, i in pathSet, onHold = { zoom = if (it) i else null }, onClick = { onSquare(i) }, focusSeat = focusSeat)
            // centro: mazos de cartas, título con relieve, bote de La Peza y dados
            Box(Modifier.offset(g * 0.16f, g * 0.17f).size(g * 0.21f, g * 0.145f).rotate(-35f).background(Brush.linearGradient(listOf(Color(0xFFFF9F3A), Color(0xFFE5700F))), RoundedCornerShape(u * 0.9f)).border(u * 0.22f, Color.White.copy(alpha = 0.9f), RoundedCornerShape(u * 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { PolyIcon("chance", g * 0.06f); Text("SUERTE", color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (u * 1.05f).toSp() }) }
            }
            Box(Modifier.offset(g * 0.63f, g * 0.69f).size(g * 0.21f, g * 0.145f).rotate(-35f).background(Brush.linearGradient(listOf(Color(0xFF3F7BD4), Color(0xFF1B3F86))), RoundedCornerShape(u * 0.9f)).border(u * 0.22f, Color.White.copy(alpha = 0.9f), RoundedCornerShape(u * 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { PolyIcon("chest", g * 0.06f); Text("CAJA", color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (u * 1.05f).toSp() }) }
            }
            if (evk.isNotEmpty()) {
                val es = remember(evk) { Animatable(0f) }
                LaunchedEffect(evk) { es.animateTo(1f, spring(0.5f, 200f)) }
                val eshape = RoundedCornerShape(u * 0.9f)
                Box(Modifier.offset(g * 0.63f, g * 0.17f).size(g * 0.21f, g * 0.145f).graphicsLayer { scaleX = es.value; scaleY = es.value; alpha = es.value.coerceIn(0f, 1f); rotationZ = 35f }.pointerInput(evk) { detectTapGestures(onPress = { tryAwaitRelease(); onEvPeek(3) }, onLongPress = { onEvPeek(2) }, onTap = { onEvPeek(1) }) }.background(Brush.linearGradient(listOf(Color(0xFF3FBF74), Color(0xFF1B7F45))), eshape).border(u * 0.22f, Color.White.copy(alpha = 0.9f), eshape), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { PolyIcon(evk, g * 0.06f); Text("EVENTO", color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (u * 1.05f).toSp() }) }
                }
            }
            Column(Modifier.offset(g * 0.12f, g * 0.12f).size(g * 0.76f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    "EVAPOLY", modifier = Modifier.rotate(-6f),
                    style = TextStyle(color = Color(0xFFD43A3A), fontWeight = FontWeight.Black, letterSpacing = 4.sp, fontSize = with(density) { (g / 10.5f).toSp() }, shadow = Shadow(Color(0xFF7A1919), Offset(0f, 9f), 0f)),
                )
                if (st.cfg.pot && st.pot > 0) {
                    Spacer(Modifier.height(g * 0.01f))
                    Text(
                        "La Peza · ${Poly.eur(st.pot)}", color = Color(0xFF7A4D0B), fontWeight = FontWeight.Black, fontSize = with(density) { (g / 28f).toSp() },
                        modifier = Modifier.background(Brush.verticalGradient(listOf(Color(0xFFFFF8DC), Color(0xFFF6E2A0))), RoundedCornerShape(50)).padding(horizontal = g * 0.02f, vertical = g * 0.004f),
                    )
                }
                if (dice != null) {
                    Spacer(Modifier.height(g * 0.04f))
                    val tr = rememberInfiniteTransition(label = "dados")
                    val spin by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(560, easing = LinearEasing)), label = "giro")
                    val bounce by tr.animateFloat(1f, 1.12f, infiniteRepeatable(tween(280), RepeatMode.Reverse), label = "bote")
                    val land by animateFloatAsState(if (dice.rolling) 1f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "caer")
                    Row(horizontalArrangement = Arrangement.spacedBy(g * 0.045f)) {
                        Die(dice.d1, g / 9.5f, if (dice.rolling) spin else 0f, if (dice.rolling) bounce else land)
                        if (dice.d2 > 0) Die(dice.d2, g / 9.5f, if (dice.rolling) -spin * 1.2f else 0f, if (dice.rolling) bounce else land)
                        if (dice.d3 > 0) Die(dice.d3, g / 9.5f, if (dice.rolling) spin * 0.8f else 0f, if (dice.rolling) bounce else land)
                    }
                }
            }
            val tk = g * 0.054f
            for (s in 0 until st.n) {
                if (st.bk[s]) continue
                val ci = cellOf(viewPos[s])
                val dx = (if (s % 2 == 1) 1f else -1f) * 1.6f
                val dy = (if (s < 2) -1f else 1f) * 1.6f
                val ms = if (slowSeat == s) 2600 else 150
                val x by animateDpAsState(g * ((ci[0] + ci[2] / 2f + dx) / 100f) - tk / 2f, tween(ms, easing = if (slowSeat == s) FastOutSlowInEasing else LinearEasing), label = "x$s")
                val y by animateDpAsState(g * ((ci[1] + ci[3] / 2f + dy) / 100f) - tk / 2f, tween(ms, easing = if (slowSeat == s) FastOutSlowInEasing else LinearEasing), label = "y$s")
                // saltito en cada paso, desaparecer y reaparecer al cambiar de sitio, halo del turno y campeón
                val hy = remember(s) { Animatable(0f) }
                LaunchedEffect(viewPos[s]) { if (hopSeat == s) { hy.snapTo(0f); hy.animateTo(1f, tween(150)); hy.snapTo(0f) } }
                val poofed = s in poof
                val sc by animateFloatAsState(if (poofed) 0.05f else 1f, if (poofed) tween(380) else spring(0.45f, 300f), label = "poof$s")
                val champ = st.phase == "over" && st.winner == s
                val ptr = rememberInfiniteTransition(label = "halo$s")
                val pulse by ptr.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "pulso")
                val cs by ptr.animateFloat(1.6f, 2f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "campeon")
                if (st.turn == s && st.phase != "order" && st.phase != "over") {
                    val rs2 = tk * (0.8f + 1.1f * pulse)
                    Box(Modifier.offset(x + tk / 2f - rs2 / 2f, y + tk / 2f - rs2 / 2f).size(rs2).border(tk * 0.07f, PolyData.colors[s].copy(alpha = 0.85f * (1f - pulse)), CircleShape))
                }
                val scale = sc * (if (champ) cs else 1f)
                TokenView(
                    tokenOf(s), tk, PolyData.colors[s],
                    Modifier.offset(x, y - tk * 0.55f * sin(PI.toFloat() * hy.value)).graphicsLayer { scaleX = scale; scaleY = scale; alpha = sc.coerceIn(0f, 1f); rotationZ = (1f - sc) * 180f },
                )
            }
            if (evk == "dog" && dogAt >= 0) {
                val dc = cellOf(dogAt)
                val ds = g * 0.075f
                val dx by animateDpAsState(g * ((dc[0] + dc[2] / 2f) / 100f) - ds / 2f, tween(950, easing = FastOutSlowInEasing), label = "perroX")
                val dy by animateDpAsState(g * ((dc[1] + dc[3] / 2f) / 100f) - ds / 2f, tween(950, easing = FastOutSlowInEasing), label = "perroY")
                val trot = rememberInfiniteTransition(label = "trote")
                val tb by trot.animateFloat(0f, 1f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "trote")
                PolyIcon("dog", ds, Modifier.offset(dx, dy - ds * 0.12f * tb).rotate(-4f + 8f * tb))
            }
            for (f in fxs) FxView(f, g)
            (zoom ?: landing)?.let { SquareView(it, data.squares[it], g, u, st, data, false, zoomed = true) }
        }
        if (evk == "snow" || evk == "rain") WeatherView(evk)
        if (police) {
            val pt = rememberInfiniteTransition(label = "policia")
            val a by pt.animateFloat(0f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing)), label = "luces")
            Box(Modifier.fillMaxSize().background(if (a < 0.5f) Brush.horizontalGradient(listOf(Color(0x80E53935), Color.Transparent), endX = with(density) { side.toPx() } * 0.6f) else Brush.horizontalGradient(listOf(Color.Transparent, Color(0x801E88E5)), startX = with(density) { side.toPx() } * 0.4f)))
        }
        if (cardFx != null) CardFxView(cardFx, side, onCardDone)
        if (pops.isNotEmpty()) Column(Modifier.align(Alignment.BottomCenter).offset(y = -side * 0.2f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(side * 0.008f)) {
            for (m in pops) {
                val a = remember(m.id) { Animatable(0f) }
                LaunchedEffect(m.id) { a.animateTo(1f, tween(220)); delay(1700); a.animateTo(0f, tween(380)) }
                Text(
                    m.text, color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (side * 0.026f).toSp() },
                    modifier = Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 20f }
                        .background(if (m.kind == 0) Color(0xFF1F9D55) else if (m.kind == 1) Color(0xFFD33A31) else Color(0xFF3A3AC9), RoundedCornerShape(50))
                        .padding(horizontal = side * 0.03f, vertical = side * 0.008f),
                )
            }
        }
        if (banner != null) BannerView(banner, side)
        if (evCard != null) EventCardView(evCard, side, onEvDone)
    }
}

/** Invitación al Evapoly en el chat: cómo va la sala y botón para unirse o abrirla. */
@Composable
fun PolyCard(m: Social.ChatMessage, fg: Color, onOpen: (String) -> Unit) {
    val roomId = m.game.ifEmpty { m.id }
    var room by remember(roomId) { mutableStateOf<Social.PolyRoom?>(null) }
    LaunchedEffect(roomId) {
        while (true) {
            if (AppVisibility.foreground) {
                try { room = Social.polyRoom(roomId); if (room?.status == "finished") break } catch (e: CancellationException) { throw e } catch (e: Exception) { /* sin red o sin acceso */ }
            }
            delay(20_000)
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
    var fullScreen by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(Prefs.gameDark) }
    // fondo oscuro como en el Evadrez: mientras está activo todo el tema pasa a negro
    DisposableEffect(dark) {
        val prev = Bg
        if (dark) Bg = Color.Black
        onDispose { Bg = prev }
    }
    BackHandler(onBack = { if (fullScreen) fullScreen = false else onClose() })
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val kick = remember { BooleanArray(1) } // se activa al jugar para mirar pronto la respuesta
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
    var cardFx by remember { mutableStateOf<CardFx?>(null) }
    var banner by remember { mutableStateOf<BannerInfo?>(null) }
    var pathSet by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var slowSeat by remember { mutableIntStateOf(-1) }
    var police by remember { mutableStateOf(false) }
    var landing by remember { mutableStateOf<Int?>(null) }
    val deltas = remember { mutableStateListOf<Delta>() }
    val pops = remember { mutableStateListOf<MoneyPop>() }
    val fxs = remember { mutableStateListOf<Fx>() }
    val poof = remember { mutableStateListOf<Int>() }
    var hopSeat by remember { mutableStateOf(-1) }
    var shakeKey by remember { mutableStateOf(0) }
    var viewEvk by remember { mutableStateOf("") }
    var viewDog by remember { mutableIntStateOf(-1) }
    var evCard by remember { mutableStateOf<EvCard?>(null) }
    var peekHold by remember { mutableStateOf(false) }
    var focusOn by remember { mutableStateOf(Prefs.polyFocus) }
    var hiSeat by remember { mutableIntStateOf(-1) }
    fun pct(i: Int): FloatArray = cellOf(i).let { floatArrayOf(it[0] + it[2] / 2f, it[1] + it[3] / 2f) }
    fun fxAt(kind: String, from: FloatArray, to: FloatArray = from, n: Int = 5, color: Color = Color.White, ms: Int = 900) {
        val f = Fx(System.nanoTime() + fxs.size, kind, from[0], from[1], to[0], to[1], n, color, ms)
        fxs.add(f)
        scope.launch { delay(ms + 400L); fxs.remove(f) }
    }
    fun fx(kind: String, sq: Int, n: Int = 5, color: Color = Color.White, ms: Int = 900) = fxAt(kind, pct(sq), pct(sq), n, color, ms)
    var lastCash by remember { mutableStateOf<IntArray?>(null) }
    var shownCash by remember { mutableStateOf<IntArray?>(null) }
    var shownTurn by remember { mutableIntStateOf(0) }
    val view = LocalView.current
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
        val s = Poly.initGame(data, r.players.size, Poly.Cfg(r.money, r.goBonus, r.auction, r.pot, r.dblBonus, r.rails, r.ownerFree, r.shop, r.events), r.seed)
        acts.clear()
        for (a in r.acts) { Poly.applyText(s, a); acts.add(a) }
        st = s
        queue.clear()
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
        lastTurn = s.turn
        lastCash = s.cash.copyOf(); shownCash = s.cash.copyOf(); shownTurn = s.turn
        pathSet = emptySet(); slowSeat = -1; police = false; cardFx = null
        viewEvk = s.evk; viewDog = s.dog; evCard = null
        ver++
    }

    /** Variación de dinero de cada jugador (+50, −200…): sube y se desvanece junto a su nombre. */
    fun noteCash(s: Poly.State) {
        val lc = lastCash
        if (lc != null) for (i in 0 until s.n) {
            val d = s.cash[i] - lc[i]
            if (d != 0) {
                val dl = Delta(i, (if (d > 0) "+" else "−") + Poly.eur(kotlin.math.abs(d)), d > 0, System.nanoTime())
                deltas.add(dl)
                scope.launch { delay(1600); deltas.remove(dl) }
            }
        }
        lastCash = s.cash.copyOf()
    }

    suspend fun play() {
        if (running) return
        running = true
        ver++
        val s = st ?: run { running = false; return }
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            // pagos y cobros: salen un momento en medio del tablero
            if (e.amt != 0 && e.seat >= 0 && e.k in setOf("pay", "gain", "go", "buy", "build", "mort", "rail", "free", "bite")) {
                val amt = Poly.eur(kotlin.math.abs(e.amt))
                val incoming = e.k == "gain" || e.k == "go" || ((e.k == "build" || e.k == "mort") && e.amt > 0)
                val xfer = e.k == "pay" && e.to >= 0
                val mp = MoneyPop(if (xfer) "${seatName(e.seat)} → ${seatName(e.to)} · $amt" else if (incoming) "${seatName(e.seat)} +$amt" else "${seatName(e.seat)} −$amt", e.seat, if (xfer) 2 else if (incoming) 0 else 1, System.nanoTime())
                pops.add(mp); while (pops.size > 4) pops.removeAt(0)
                val nc = if (kotlin.math.abs(e.amt) >= 300) 6 else if (kotlin.math.abs(e.amt) >= 100) 4 else 3
                val mid = floatArrayOf(50f, 50f)
                if (xfer) fxAt("coins", pct(viewPos[e.seat]), pct(viewPos[e.to]), nc, ms = 1200) else if (incoming) fxAt("coins", mid, pct(viewPos[e.seat]), nc, ms = 1200) else fxAt("coins", pct(viewPos[e.seat]), mid, nc, ms = 1200)
                scope.launch { delay(2300); pops.remove(mp) }
            }
            when (e.k) {
                "dice" -> {
                    landing = null
                    pathSet = emptySet()
                    PolySound.play("dice")
                    // los dados giran cambiando de cara y se van frenando hasta quedarse en el resultado
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 1400) {
                        dice = DiceView((1..6).random(), if (e.d2 == 0) 0 else (1..6).random(), true, if (e.d3 > 0) (1..6).random() else 0)
                        delay(60L + (System.currentTimeMillis() - t0) / 9)
                    }
                    dice = DiceView(e.d1, e.d2, false, e.d3)
                    delay(350)
                }
                "move" -> {
                    val dir = e.dir
                    val steps = ((e.to - viewPos[e.seat]) * dir + 40) % 40
                    val from = viewPos[e.seat]
                    pathSet = (0..steps).map { (from + dir * it + 80) % 40 }.toSet()
                    var p = from
                    hopSeat = e.seat
                    repeat(steps) {
                        fx("ghost", p, color = PolyData.colors[e.seat], ms = 650)
                        p = (p + dir + 40) % 40
                        viewPos[e.seat] = p
                        PolySound.play("step")
                        delay(if (e.seat == mySeat() || steps < 8) 160 else 120)
                    }
                    hopSeat = -1
                    pathSet = emptySet()
                    if (e.sq >= 0) {
                        // choca con unas obras: polvo, golpe y sacudida
                        fx("puff", e.to, 6); PolySound.play("bounce"); shakeKey++
                        delay(250)
                    } else {
                        // al caer, la casilla se ve ampliada en el centro unos segundos (la ven todos)
                        landing = e.to
                        val shown = e.to
                        scope.launch { delay(2400); if (landing == shown) landing = null }
                    }
                }
                "swap" -> {
                    landing = null
                    PolySound.play("swap")
                    fx("spark", e.from, 10, Color(0xFFB25BD8)); fx("spark", e.sq, 10, Color(0xFFB25BD8))
                    poof.add(e.seat); poof.add(e.to)
                    delay(400)
                    viewPos[e.seat] = e.sq; viewPos[e.to] = e.from
                    poof.clear()
                    fx("spark", e.from, 10, Color(0xFFFFD84A)); fx("spark", e.sq, 10, Color(0xFFFFD84A))
                    delay(700)
                    landing = e.sq
                    val shown = e.sq
                    scope.launch { delay(2400); if (landing == shown) landing = null }
                }
                "evt" -> {
                    landing = null
                    if (e.amt != 0) { if (e.sq < 5) { viewEvk = EV_IDS[e.sq]; viewDog = if (e.sq == 0) 0 else -1 }; PolySound.play("card") }
                    else { viewEvk = ""; viewDog = -1; PolySound.play("turn") }
                    val c = EvCard(e.sq, e.amt != 0)
                    evCard = c
                    ver++
                    var waited = 0
                    while (evCard === c && waited < 6000) { delay(100); waited += 100 }
                    evCard = null
                }
                "dog" -> { viewDog = e.to; PolySound.play("step"); delay(1000) }
                "quake" -> {
                    landing = null
                    PolySound.play("bounce")
                    fxAt("cracks", floatArrayOf(50f, 50f), n = 4, ms = 3200)
                    repeat(5) { shakeKey++; delay(250) }
                    delay(250)
                }
                "crumble" -> { ver++; fx("puff", e.sq, 8); PolySound.play("bounce"); delay(500) }
                "bite" -> { fx("puff", e.sq, 5); PolySound.play("bounce"); shakeKey++ }
                "pull" -> {
                    landing = null
                    PolySound.play("swap")
                    fxAt("plunger", pct(e.sq), pct(e.from), ms = 1100)
                    delay(480)
                    poof.add(e.to)
                    delay(320)
                    poof.clear(); viewPos[e.to] = e.sq
                    fx("spark", e.sq, 8, Color(0xFFE0483F))
                    delay(500)
                }
                "fence" -> {
                    PolySound.play("fence")
                    fx("puff", e.sq, 6); fx("spark", e.sq, 6, Color(0xFFFF8A1F))
                    ver++
                    landing = e.sq
                    val shown = e.sq
                    scope.launch { delay(2400); if (landing == shown) landing = null }
                    delay(1200)
                }
                "rail" -> {
                    landing = null
                    fxAt("train", pct(e.from), pct(e.to), ms = 1500)
                    PolySound.play("train")
                    delay(1500)
                    viewPos[e.seat] = e.to
                    fx("puff", e.to, 4)
                    delay(300)
                    landing = e.to
                    val shown = e.to
                    scope.launch { delay(2400); if (landing == shown) landing = null }
                }
                "jail" -> {
                    // sirena, luces de policía y la ficha cruza el tablero en diagonal hasta la cárcel
                    landing = null
                    PolySound.play("siren"); police = true; slowSeat = e.seat
                    delay(60)
                    viewPos[e.seat] = e.to
                    delay(3000)
                    slowSeat = -1; police = false
                }
                "pay", "debt" -> PolySound.play("pay")
                "go", "gain" -> PolySound.play("coin")
                "buy" -> {
                    PolySound.play("buy")
                    if (e.dir == 0) {
                        // subasta cerrada: golpe de martillo
                        ver++
                        fxAt("hammer", floatArrayOf(50f, 46f), ms = 1000)
                        delay(330)
                        PolySound.play("hammer"); fx("puff", e.sq, 5); fx("spark", e.sq, 8, Color(0xFFFFD84A))
                        delay(650)
                    }
                }
                "build" -> {
                    PolySound.play("build")
                    if (e.sq >= 0 && e.amt < 0) { fx("drop", e.sq, if (s.lvl[e.sq] == 5) 5 else 1, ms = 800); fx("puff", e.sq, 3) }
                }
                "card" -> {
                    landing = null
                    PolySound.play(if (e.sq == 1) "chest" else "card")
                    val fx = CardFx(if (e.sq == 1) "chest" else if (e.sq == 0) "chance" else "peza", if (e.sq == 1) "Caja de comunidad" else if (e.sq == 0) "Suerte" else "La Peza", seatName(e.seat), e.text)
                    cardFx = fx
                    ver++
                    scope.launch { delay(900); if (e.amt > 0) { fxAt("confetti", floatArrayOf(50f, 50f), n = 36, ms = 2800); PolySound.play("confetti") } else if (e.amt < 0) shakeKey++ }
                    var waited = 0
                    while (cardFx === fx && waited < 5800) { delay(100); waited += 100 }
                    cardFx = null
                }
                "win" -> {
                    PolySound.play("win")
                    fxAt("confetti", floatArrayOf(50f, 50f), n = 70, ms = 3400)
                    scope.launch { delay(1500); fxAt("confetti", floatArrayOf(50f, 50f), n = 60, ms = 3400); delay(1500); fxAt("confetti", floatArrayOf(50f, 50f), n = 60, ms = 3400) }
                }
            }
            if (e.m.isNotEmpty()) { log.add(say(e.m)); if (log.size > 40) log.removeAt(0) }
            ver++
        }
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
        viewEvk = s.evk; viewDog = s.dog
        running = false
        noteCash(s); shownCash = s.cash.copyOf(); shownTurn = s.turn
        if (s.turn != lastTurn) {
            lastTurn = s.turn
            if (s.turn == mySeat() && s.phase != "over" && s.phase != "order") {
                PolySound.play("turn")
                val b = BannerInfo("Te toca", mySeat()); banner = b
                scope.launch { delay(1800); if (banner === b) banner = null }
            }
        }
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
        kick[0] = true
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

    LaunchedEffect(Unit) { PolySound.init(ctx); PolyMusic.start(ctx) }
    LaunchedEffect(Unit) {
        var idle = 0
        var lastSig = ""
        while (true) {
            if (!AppVisibility.foreground) { delay(2_500); continue } // con la app en segundo plano no se consulta nada
            try {
                val r = Social.polyRoom(roomId)
                if (r == null) { toast("La sala se ha cerrado"); onClose(); return@LaunchedEffect }
                val wasLobby = room == null || room?.status == "lobby"
                room = r
                loadError = ""
                loadNames(r)
                if (r.status != "lobby") { if (st == null || wasLobby) buildState(r) else syncActs(r) }
                val sig = "${r.status}${r.acts.size}${r.players.size}${r.tok}${r.invited.size}${r.money}"
                idle = if (sig != lastSig) 0 else idle + 1
                lastSig = sig
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: e.javaClass.simpleName // sin red o sin permiso: se reintenta y se enseña el motivo
                idle++
            }
            // sala de espera: cada 3 s; en la partida, cada 6 s cuando te toca a ti (nadie más va a actuar) y cada 2,2 s si esperas a otro
            val s2 = st
            val myTurn = s2 != null && s2.phase != "over" && s2.turn == mySeat() && s2.trade == null && s2.phase !in listOf("debt", "auction", "order")
            val base = if (room?.status == "lobby" || s2 == null) 4_000L else if (myTurn) 10_000L else 3_000L
            val ms = (base * (1 + minOf(3, idle / 12) * 0.5)).toLong().coerceAtMost(20_000L)
            var waited = 0L
            while (waited < ms && !kick[0]) { delay(250); waited += 250 }
            kick[0] = false
        }
    }
    DisposableEffect(Unit) { onDispose { PolyMusic.stop() } }
    // pantalla completa: se ocultan las barras del sistema (se vuelven a ver deslizando desde el borde)
    DisposableEffect(fullScreen) {
        val window = (ctx as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullScreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    @Suppress("UNUSED_VARIABLE") val redraw = ver // las acciones cambian el estado del motor sin que Compose se entere: `ver` obliga a redibujar

    val r = room
    Column(Modifier.fillMaxSize().background(Bg).then(if (fullScreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
        if (!fullScreen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Evapoly", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { focusOn = !focusOn; hiSeat = -1; Prefs.polyFocus = focusOn }, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) { Icon(Icons.Default.Visibility, "Foco en mis calles", tint = if (focusOn) Color(0xFFF0B400) else OnSurface) }
            IconButton(onClick = { dark = !dark; Prefs.gameDark = dark }, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
            IconButton(onClick = { fullScreen = true }, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) { Icon(Icons.Default.Fullscreen, "Pantalla completa", tint = OnSurface) }
            IconButton(onClick = {
                musicOn = !musicOn; Prefs.polyMusic = musicOn
                if (musicOn) PolyMusic.start(ctx) else PolyMusic.stop()
            }, modifier = Modifier.size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape)) { Icon(if (musicOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Música", tint = OnSurface) }
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
                onCfg = { cfg -> scope.launch { try { Social.polyCfg(r, cfg); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onStart = { scope.launch { try { Social.polyStart(r, kotlin.random.Random.nextInt(Int.MAX_VALUE)); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
                onInvite = { scope.launch { try { inviteList = Social.overview().friends.map { it.profile } } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
            )
        } else {
            val s = st!!
            val my = mySeat()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val land = maxWidth > maxHeight * 1.15f
                val side = when {
                    fullScreen && land -> minOf(maxHeight - 8.dp, maxWidth - 296.dp)
                    fullScreen -> minOf(maxWidth, maxHeight - 190.dp)
                    land -> minOf(maxHeight - 8.dp, maxWidth * 0.6f) // 60 % tablero, 40 % información
                    else -> minOf(maxWidth, maxHeight - 290.dp)
                }.coerceAtLeast(260.dp)
                val panel: @Composable (Modifier) -> Unit = { mod ->
                    GamePanel(
                        s, r, my, data, ::seatName, ::tokenOf, log, running || busy, deltas, fullScreen, running, shownCash, shownTurn,
                        onSend = ::send, onTrade = { showTrade = true }, hiSeat = hiSeat, onPick = { hiSeat = if (hiSeat == it) -1 else it }, modifier = mod,
                    )
                }
                val strip: @Composable () -> Unit = {
                    PositionStrip(s, my, data, if (my >= 0) viewPos[my] else 0, ::tokenOf, fullScreen, { squareSel = it })
                }
                val board: @Composable () -> Unit = {
                    PolyBoard(side, data, s, viewPos, ::tokenOf, dice, pathSet, slowSeat, police, landing, cardFx, banner, pops, fxs, hopSeat, poof, shakeKey, if (hiSeat >= 0 && !s.bk[hiSeat]) hiSeat else if (focusOn) my else -1, viewEvk, viewDog, evCard, { evCard = null }, { m ->
                        val k = EV_IDS.indexOf(viewEvk)
                        if (m == 3) { if (peekHold) { peekHold = false; evCard = null } }
                        else if (k >= 0 && evCard == null && !running) { peekHold = m == 2; evCard = EvCard(k, true, peek = true, hold = m == 2) }
                    }, { cardFx = null }) { squareSel = it }
                }
                if (land) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        horizontalArrangement = if (fullScreen) Arrangement.Center else Arrangement.spacedBy(14.dp),
                        verticalAlignment = if (fullScreen) Alignment.CenterVertically else Alignment.Top,
                    ) {
                        board()
                        if (fullScreen) {
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.width(270.dp).fillMaxHeight().padding(start = 16.dp, top = 44.dp, bottom = 8.dp)) {
                                panel(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                                strip()
                            }
                        } else Column(Modifier.weight(1f).fillMaxHeight()) {
                            panel(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                            strip()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        board()
                        Spacer(Modifier.height(if (fullScreen) 16.dp else 8.dp))
                        panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                        strip()
                    }
                }
                if (fullScreen) {
                    IconButton(
                        onClick = { fullScreen = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) { Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = OnSurface) }
                    IconButton(
                        onClick = { dark = !dark; Prefs.gameDark = dark },
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 62.dp).size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) { Icon(Icons.Default.DarkMode, "Fondo oscuro", tint = if (dark) Accent else OnSurface) }
                    IconButton(
                        onClick = { focusOn = !focusOn; hiSeat = -1; Prefs.polyFocus = focusOn },
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 118.dp).size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) { Icon(Icons.Default.Visibility, "Foco en mis calles", tint = if (focusOn) Color(0xFFF0B400) else OnSurface) }
                }
            }
        }
    }

    // ───── diálogos ─────
    val s = st
    if (r != null && s != null) {
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
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onTok: (Int) -> Unit, onCfg: (Social.RoomCfg) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
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
                fun cfg(money: Int = r.money, goBonus: Boolean = r.goBonus, auction: Boolean = r.auction, pot: Boolean = r.pot, dbl: Boolean = r.dblBonus, rails: Boolean = r.rails, ownerFree: Boolean = r.ownerFree, shop: Boolean = r.shop, events: Boolean = r.events) =
                    Social.RoomCfg(money, goBonus, auction, pot, dbl, rails, ownerFree, shop, events)
                Slider(
                    value = money, onValueChange = { money = it }, valueRange = 500f..2500f, steps = 39, enabled = isHost,
                    onValueChangeFinished = { onCfg(cfg(money = money.toInt())) },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                @Composable
                fun Opt(label: String, on: Boolean, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(on, change, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Opt("400 € al caer en la Salida", r.goBonus) { onCfg(cfg(goBonus = it)) }
                Opt("Subastas", r.auction) { onCfg(cfg(auction = it)) }
                Opt("Bote en La Peza", r.pot) { onCfg(cfg(pot = it)) }
                Opt("Premio por dobles (50 € y 100 €)", r.dblBonus) { onCfg(cfg(dbl = it)) }
                Opt("Viaje en tren entre estaciones (50 € y 150 €)", r.rails) { onCfg(cfg(rails = it)) }
                Opt("Sin alquiler si el dueño está en la casilla", r.ownerFree) { onCfg(cfg(ownerFree = it)) }
                Opt("Tienda en La Peza", r.shop) { onCfg(cfg(shop = it)) }
                Opt("Eventos aleatorios", r.events) { onCfg(cfg(events = it)) }
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
private fun PolyButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), cta: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val hgt = if (compact) 40.dp else if (cta) 58.dp else 50.dp
    if (primary) {
        Button(onClick, modifier.height(hgt), enabled = enabled, shape = shape, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(label, fontWeight = FontWeight.ExtraBold, fontSize = if (cta) 17.sp else if (compact) 13.sp else 14.sp, maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(onClick, modifier.height(hgt), enabled = enabled, shape = shape, contentPadding = PaddingValues(horizontal = 6.dp), border = BorderStroke(1.5.dp, if (enabled) Accent else Outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1, softWrap = false) }
    }
}

/** Tira lineal con tu posición, siempre en línea aunque el tablero gire: la casilla de delante a la izquierda, la tuya en medio y la de detrás a la derecha. */
@Composable
private fun PositionStrip(s: Poly.State, my: Int, data: Poly.Data, myPos: Int, tokenOf: (Int) -> Int, compact: Boolean, onSquare: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (my < 0 || s.bk[my]) return
    val col = PolyData.colors[my]
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp)) {
        val cardW = ((maxWidth - 6.dp * 2 - 12.dp) / 3).coerceAtMost(if (compact) 80.dp else 96.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            for (k in 0 until 3) {
                val idx = (myPos + 1 - k + 40) % 40
                val now = k == 1
                Box(contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.graphicsLayer { scaleX = if (now) 1.16f else 1f; scaleY = if (now) 1.16f else 1f; alpha = if (now) 1f else 0.88f }.then(if (now) Modifier.border(3.dp, col, RoundedCornerShape(6.dp)) else Modifier)) {
                        SquareView(idx, data.squares[idx], 0.dp, 1.dp, s, data, false, inlineW = cardW, onClick = { onSquare(idx) })
                    }
                    if (now) TokenView(tokenOf(my), 32.dp, col, Modifier.offset(y = (-22).dp))
                }
            }
        }
    }
}

/** Cifra que sube y se desvanece junto al nombre cuando cambia el dinero. */
@Composable
private fun DeltaText(d: Delta, modifier: Modifier) {
    val t = remember(d.id) { Animatable(0f) }
    LaunchedEffect(d.id) { t.animateTo(1f, tween(1500, easing = LinearEasing)) }
    val a = if (t.value < 0.15f) t.value / 0.15f else 1f - (t.value - 0.15f) / 0.85f
    Text(
        d.text, color = if (d.up) Color(0xFF5CE08A) else Color(0xFFFF7A72), fontWeight = FontWeight.Black, fontSize = 15.sp,
        modifier = modifier.padding(end = 14.dp).graphicsLayer { alpha = a.coerceIn(0f, 1f); translationY = -t.value * 70f },
    )
}

/** Jugadores (uno debajo de otro; el que tiene el turno, con un recuadro de su color), acciones y registro. */
@Composable
private fun GamePanel(
    s: Poly.State, r: Social.PolyRoom, my: Int, data: Poly.Data, seatName: (Int) -> String, tokenOf: (Int) -> Int,
    log: List<String>, locked: Boolean, deltas: List<Delta>, compact: Boolean, frozen: Boolean, shownCash: IntArray?, shownTurn: Int,
    onSend: (String) -> Unit, onTrade: () -> Unit, hiSeat: Int, onPick: (Int) -> Unit, modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
        for (i in r.players.indices) {
            val shape = RoundedCornerShape(if (compact) 12.dp else 18.dp)
            val col = PolyData.colors[i]
            val turn = if (s.phase == "order") s.ordCand[i] && s.ordRolls[i] == 0 else (if (frozen) shownTurn else s.turn) == i && s.phase != "over"
            val owned = (0 until 40).count { s.own[it] == i }
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(
                            if (turn) Brush.verticalGradient(listOf(col.copy(alpha = 0.3f), col.copy(alpha = 0.08f)))
                            else Brush.verticalGradient(listOf(OnSurface.copy(alpha = 0.08f), OnSurface.copy(alpha = 0.03f))),
                        )
                        .border(if (hiSeat == i) 2.dp else if (turn) 2.dp else 1.dp, if (hiSeat == i) Color(0xFFF0B400) else if (turn) col else OnSurface.copy(alpha = 0.1f), shape)
                        .clickable(enabled = !s.bk[i]) { onPick(i) }
                        .alpha(if (s.bk[i]) 0.4f else 1f).padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TokenView(tokenOf(i), if (compact) 28.dp else 38.dp, col)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(seatName(i) + if (i == my) " (tú)" else "", color = OnSurface, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val mine = (0 until 40).filter { s.own[it] == i }
                        if (!compact && mine.isNotEmpty()) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            for (sq in mine) Box(
                                Modifier.size(if (compact) 7.dp else 9.dp).alpha(if (s.mort[sq]) 0.35f else 1f)
                                    .background(data.squares[sq].g?.let { hex(data.groups[it] ?: "#888888") } ?: Color(0xFF8A929B), RoundedCornerShape(2.dp)).border(0.7.dp, Color.Black.copy(alpha = 0.45f), RoundedCornerShape(2.dp)),
                            )
                        }
                        if (compact) {
                            Text(if (s.bk[i]) "Quiebra" else listOfNotNull(if (s.jail[i]) "Cárcel" else null, "$owned calles").joinToString(" · "), color = OnSurfaceDim, fontSize = 11.sp)
                        } else {
                            val extra = if (s.bk[i]) "Quiebra"
                            else if (s.phase == "order") (if (s.ordRolls[i] > 0) "Sacó ${s.ordRolls[i]}" else "")
                            else listOfNotNull(if (s.jail[i]) "Cárcel" else null, if (s.jf[i] > 0) "Carta ×${s.jf[i]}" else null, if (s.mc[i] > 0) "Maricarmen" + (if (s.mc[i] > 1) " ×${s.mc[i]}" else "") else null, if (s.tri[i] > 0) "Dado triple" + (if (s.tri[i] > 1) " ×${s.tri[i]}" else "") else null, if (s.dj[i] > 0) "Doble condena" else null, if (s.tk[i] > 0) "Billete de tren" else null, if (s.ob[i] > 0) "Obras" else null, if (s.sw[i] > 0) "Intercambio" else null, if (s.pl[i] > 0) "Desatascador" else null, "$owned calles").joinToString(" · ")
                            Text(extra, color = OnSurfaceDim, fontSize = 12.sp)
                        }
                    }
                    Text(Poly.eur(if (frozen && shownCash != null) shownCash[i] else s.cash[i]), color = OnSurface, fontWeight = FontWeight.Black, fontSize = if (compact) 14.sp else 17.sp)
                }
                for (d in deltas) if (d.seat == i) DeltaText(d, Modifier.align(Alignment.TopEnd))
            }
        }

        // acciones según la fase
        val cur = s.turn
        @Composable
        fun Status(t: String) = Text(t, color = OnSurface, fontWeight = FontWeight.Bold, fontSize = if (compact) 13.sp else 15.sp, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
        @Composable
        fun Btns(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
        @Composable
        fun androidx.compose.foundation.layout.RowScope.B(label: String, code: String, primary: Boolean = true, enabled: Boolean = true, cta: Boolean = false) =
            PolyButton(label, primary, enabled && !locked, Modifier.weight(1f), cta, compact) { onSend(code) }

        var pick by remember { mutableStateOf("") } // "X" intercambio, "V" desatascador
        if (pick.isNotEmpty() && my >= 0) AlertDialog(
            onDismissRequest = { pick = "" }, title = { Text(if (pick == "X") "Intercambio" else "Desatascador") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in 0 until s.n) if (p != my && !s.bk[p])
                        PolyButton("${seatName(p)} · ${data.squares[s.pos[p]].n}", true, Poly.act(s, my, "$pick$p", true) == null && !locked) { val op = pick; pick = ""; onSend("$op$p") }
                }
            },
            confirmButton = { TextButton(onClick = { pick = "" }) { Text("Cerrar") } },
        )
        val t = s.trade
        if (frozen) Spacer(Modifier.height(if (compact) 40.dp else 58.dp))
        else if (my < 0) Status("Espectador")
        else if (t != null && t.to == my) {
            Status("${seatName(t.from)} propone")
            fun names(list: List<Int>) = list.joinToString(", ") { data.squares[it].n }
            TradeLine("Te da", listOfNotNull(if (t.gc > 0) Poly.eur(t.gc) else null, if (t.gp.isNotEmpty()) names(t.gp) else null, if (t.gj > 0) "${t.gj} carta" else null).joinToString(" + ").ifEmpty { "—" })
            TradeLine("Te pide", listOfNotNull(if (t.rc > 0) Poly.eur(t.rc) else null, if (t.rp.isNotEmpty()) names(t.rp) else null, if (t.rj > 0) "${t.rj} carta" else null).joinToString(" + ").ifEmpty { "—" })
            Btns { B("Aceptar", "Y"); B("Rechazar", "N", false) }
        } else if (t != null && t.from == my) {
            Status("Esperando respuesta")
            Btns { B("Cancelar trato", "N", false) }
        } else if (s.phase == "order") {
            if (s.ordCand[my] && s.ordRolls[my] == 0) Btns { B("Tirar dados", "r", cta = true) }
            else Status(if (s.ordRolls[my] > 0) "Sacaste ${s.ordRolls[my]}" else "Esperando")
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
                B("Tirar dados", "r", cta = true)
            }
            val canTicket = Poly.act(s, my, "B", true) == null
            val canSwap = s.sw[my] > 0 && !s.jail[my] && !s.again && s.dbl == 0
            val canPlunge = s.pl[my] > 0 && !s.jail[my]
            if ((s.tri[my] > 0 && !s.jail[my]) || (s.jail[my] && s.dj[my] > 0 && s.jmax[my] == 3) || canTicket || canSwap || canPlunge) Btns {
                if (s.tri[my] > 0 && !s.jail[my]) B("Dado triple", "R", false)
                if (canTicket) B("Billete de tren", "B", false)
                if (canSwap) PolyButton("Intercambio", false, !locked, Modifier.weight(1f), false, compact) { pick = "X" }
                if (canPlunge) PolyButton("Desatascador", false, !locked, Modifier.weight(1f), false, compact) { pick = "V" }
                if (s.jail[my] && s.dj[my] > 0 && s.jmax[my] == 3) B("Doble condena", "w", false)
            }
            // regla opcional: viajar en tren a otra estación tuya en vez de tirar
            if (s.cfg.rails && !s.jail[my]) {
                val st = listOf(5, 15, 25, 35)
                val trips = st.filter { Poly.act(s, my, "x$it", true) == null }
                if (trips.isNotEmpty()) Btns {
                    for (i in trips) B("Tren a ${data.squares[i].n.removePrefix("Estación ")} · ${if ((st.indexOf(i) - st.indexOf(s.pos[my]) + 4) % 4 == 1) 50 else 150} €", "x$i", false)
                }
            }
        } else if (s.phase == "buy") {
            val q = data.squares[s.buy]
            Status("${q.n} · ${Poly.eur(s.buyPrice(s.buy))}")
            Btns { B("Comprar", "b", true, s.cash[my] >= s.buyPrice(s.buy), cta = true) }
            Btns { if (s.cfg.auction) B("Subastar", "a", false); B("No comprar", "n", false) }
        } else if (s.phase == "end") Btns { B("Terminar turno", "e", cta = true) }

        if (!frozen && my >= 0 && cur == my && (s.phase == "roll" || s.phase == "end") && t == null) {
            var confirmQuit by remember { mutableStateOf(false) }
            var showShop by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                if (s.cfg.shop && s.pos[my] == 20 && !s.jail[my]) TextButton(onClick = { showShop = true }, enabled = !locked) { Text("Tienda", color = Accent, fontWeight = FontWeight.Bold) }
                TextButton(onClick = onTrade, enabled = !locked) { Text("Negociar", color = Accent, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { confirmQuit = true }, enabled = !locked) { Text("Abandonar", color = Accent, fontWeight = FontWeight.Bold) }
            }
            if (showShop) AlertDialog(
                onDismissRequest = { showShop = false }, title = { Text("Tienda") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val names = mapOf(1 to "Maricarmen", 2 to "Dado triple", 3 to "Doble condena", 4 to "Billete de tren", 5 to "Obras", 6 to "Intercambio", 7 to "Desatascador")
                        for ((k, it) in Poly.SHOP) {
                            val have = when (k) { 1 -> s.mc[my]; 2 -> s.tri[my]; 3 -> s.dj[my]; 4 -> s.tk[my]; 5 -> s.ob[my]; 6 -> s.sw[my]; else -> s.pl[my] } > 0 || (s.sb[my] shr k) and 1 == 1
                            PolyButton("${names[k]} · " + (if (have) "Ya lo has comprado" else Poly.eur(it.second)), true, s.cash[my] >= it.second && s.sh[my] == 0 && !have && !locked) { showShop = false; onSend("o$k") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showShop = false }) { Text("Cerrar") } },
            )
            if (confirmQuit) AlertDialog(
                onDismissRequest = { confirmQuit = false }, title = { Text("Abandonar") }, text = { Text("") },
                confirmButton = { TextButton(onClick = { confirmQuit = false; onSend("z") }) { Text("Abandonar") } },
                dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Cancelar") } },
            )
        }
        val lines = log.takeLast(if (compact) 2 else 4)
        lines.forEachIndexed { idx, line ->
            Text(line, color = OnSurfaceDim.copy(alpha = if (idx == lines.lastIndex) 1f else 0.6f), fontSize = if (compact) 11.sp else 13.sp, fontWeight = if (idx == lines.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
        }
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
                if (q.p > 0 && q.t != "tax") { Line("Precio", Poly.eur(if (q.t == "street" || q.t == "station" || q.t == "utility") s.buyPrice(i) else q.p)); Line("Hipoteca", Poly.eur(q.p / 2)) }
                if (o >= 0) Line("Dueño", seatName(o) + if (s.mort[i]) " · hipotecada" else "", color = PolyData.colors[o])
                if (o == my && my >= 0 && !locked) {
                    if (can("h$i")) PolyButton("Construir ${Poly.eur(s.houseCost(i))}", true, true) { onSend("h$i") }
                    if (can("s$i")) PolyButton("Vender ${Poly.eur(s.houseCost(i) / 2)}", false, true) { onSend("s$i") }
                    if (can("m$i")) PolyButton("Hipotecar ${Poly.eur(q.p / 2)}", false, true) { onSend("m$i") }
                    if (can("u$i")) PolyButton("Deshipotecar ${Poly.eur(s.unmortgageCost(i))}", true, true) { onSend("u$i") }
                }
                if (my >= 0 && !locked && can("O$i")) PolyButton("Poner obras aquí", false, true) { onSend("O$i") }
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
        onDismissRequest = onClose, title = { Text("Negociar") },
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
