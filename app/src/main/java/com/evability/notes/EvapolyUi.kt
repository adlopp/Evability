package com.evability.notes

import androidx.compose.foundation.layout.PaddingValues
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.core.spring
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

private class DiceView(val d1: Int, val d2: Int, val rolling: Boolean)
private class CardFx(val kind: String, val title: String, val who: String, val text: String)
private class BannerInfo(val text: String, val seat: Int)
private class Delta(val seat: Int, val text: String, val up: Boolean, val id: Long)

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
    zoomed: Boolean = false, onHold: (Boolean) -> Unit = {}, onClick: () -> Unit = {},
) {
    val c = cellOf(i)
    val zs = if (zoomed) 2.6f else 1f
    val uu = u * zs
    val fs = with(LocalDensity.current) { uu.toSp() }
    val where = sideOf(i)
    val cw = gside * (c[2] / 100f) * zs
    val ch = gside * (c[3] / 100f) * zs
    // dentro de la casilla todo se dibuja como en las de abajo; las de los lados se giran (como en el tablero de verdad)
    val sideways = where == "left" || where == "right"
    val iw = if (sideways) ch else cw
    val ih = if (sideways) cw else ch
    val upright = zoomed && sideways // al ampliarla se ve recta, como las de abajo
    val ow = if (upright) iw else cw
    val oh = if (upright) ih else ch
    val wEm = iw.value / uu.value
    val hEm = ih.value / uu.value
    val band = q.g?.let { hex(data.groups[it] ?: "#888888") }
    val special = q.t == "chance" || q.t == "chest"
    val bg = when (q.t) { "go" -> Color(0xFFF6E3B4); "gotojail" -> Color(0xFFF4C7C3); "jail" -> Color(0xFFF1E1C9); "parking" -> Color(0xFFD9E8F5); else -> Color(0xFFF3F7EE) }
    val owner = st.own[i]
    val lvl = st.lvl[i]
    val bandEm = if (band != null) 1.6f else 0f // banda de color fina, con el precio centrado
    val ownEm = if (where == "corner") 0f else 2.9f // franja del dueño grande (lleva las casas)
    val icoEm = if (q.i == null) 0f else if (where == "corner") 4.2f else if (special) 2.9f else 2.0f
    val prEm = if (q.p > 0 && band == null && (q.t != "station" || zoomed)) 1.1f else 0f
    val nameEm = if (where == "corner") 1.35f else if (special) 1f else fitName(q.n, wEm - 0.7f, maxOf(1f, hEm - bandEm - ownEm - icoEm - prEm - 0.3f))
    Box(
        (if (zoomed) Modifier.offset((gside - ow) / 2f, (gside - oh) / 2f) else Modifier.offset(gside * (c[0] / 100f), gside * (c[1] / 100f)))
            .size(ow, oh)
            .background(bg).border(if (zoomed) 2.dp else 0.7.dp, Ink.copy(alpha = 0.75f))
            .then(if (zoomed) Modifier else Modifier.pointerInput(i) { detectTapGestures(onPress = { tryAwaitRelease(); onHold(false) }, onLongPress = { onHold(true) }, onTap = { onClick() }) }),
    ) {
        Box(Modifier.align(Alignment.Center).requiredSize(iw, ih).alpha(if (st.mort[i]) 0.55f else 1f).rotate(if (upright) 0f else if (where == "left") 90f else if (where == "right") -90f else 0f)) {
            val bandPart: @Composable ColumnScope.() -> Unit = {
                if (band != null) {
                    Box(
                        Modifier.fillMaxWidth().height(uu * bandEm).background(band).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.14f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Poly.eur(q.p), color = if (lum(band) > 0.62f) Ink else Color.White, fontSize = fs * 0.95f, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    }
                }
            }
            val bodyPart: @Composable ColumnScope.() -> Unit = {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = uu * 0.3f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        PolyIcon(q.i, uu * icoEm)
                        Text(
                            q.n, color = Ink, fontSize = fs * nameEm, fontWeight = if (special) FontWeight.Medium else FontWeight.ExtraBold,
                            textAlign = TextAlign.Center, lineHeight = fs * nameEm * 1.1f,
                        )
                        if (prEm > 0f) Text(Poly.eur(q.p), color = Ink, fontSize = fs * 0.92f, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val ownPart: @Composable ColumnScope.() -> Unit = {
                if (where != "corner") Box(
                    Modifier.fillMaxWidth().height(uu * ownEm).background(if (owner >= 0) PolyData.colors[owner] else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (lvl > 0) Row(horizontalArrangement = Arrangement.spacedBy(uu * 0.25f), verticalAlignment = Alignment.CenterVertically) {
                        if (lvl == 5) Box(Modifier.size(uu * 2.3f, uu * 1.3f).background(Color(0xFFD62828), RoundedCornerShape(uu * 0.15f)).border(0.7.dp, Color(0xFF5B0D0D)))
                        else repeat(lvl) { Box(Modifier.size(uu * 1.3f).background(Color(0xFF2E9B4F), RoundedCornerShape(uu * 0.15f)).border(0.7.dp, Color(0xFF0D3B1D))) }
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (where == "top") { ownPart(); bodyPart(); bandPart() } else { bandPart(); bodyPart(); ownPart() }
            }
        }
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
    side: Dp, data: Poly.Data, st: Poly.State, viewPos: List<Int>, tokenOf: (Int) -> Int, dice: DiceView?, pathSet: Set<Int>, slowSeat: Int, police: Boolean,
    cardFx: CardFx?, banner: BannerInfo?, onCardDone: () -> Unit, onSquare: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val u = side / 62f
    val g = side * 0.97f
    val frame = RoundedCornerShape(14.dp)
    Box(
        Modifier.size(side).clip(frame)
            .background(Brush.radialGradient(listOf(Color(0xFFDCEFDD), Color(0xFFC6E1CA), Color(0xFFB3D3B8)), center = Offset.Unspecified, radius = with(density) { side.toPx() } * 0.75f))
            .border(2.dp, Ink, frame),
    ) {
        Box(Modifier.offset(side * 0.015f, side * 0.015f).size(g)) {
            var zoom by remember { mutableStateOf<Int?>(null) }
            for (i in 0 until 40) SquareView(i, data.squares[i], g, u, st, data, i in pathSet, onHold = { zoom = if (it) i else null }) { onSquare(i) }
            // centro: mazos de cartas, título con relieve, bote de La Peza y dados
            Box(Modifier.offset(g * 0.16f, g * 0.17f).size(g * 0.21f, g * 0.145f).rotate(-35f).background(Brush.linearGradient(listOf(Color(0xFFFF9F3A), Color(0xFFE5700F))), RoundedCornerShape(u * 0.9f)).border(u * 0.22f, Color.White.copy(alpha = 0.9f), RoundedCornerShape(u * 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { PolyIcon("chance", g * 0.06f); Text("SUERTE", color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (u * 1.05f).toSp() }) }
            }
            Box(Modifier.offset(g * 0.63f, g * 0.69f).size(g * 0.21f, g * 0.145f).rotate(-35f).background(Brush.linearGradient(listOf(Color(0xFF3F7BD4), Color(0xFF1B3F86))), RoundedCornerShape(u * 0.9f)).border(u * 0.22f, Color.White.copy(alpha = 0.9f), RoundedCornerShape(u * 0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { PolyIcon("chest", g * 0.06f); Text("CAJA", color = Color.White, fontWeight = FontWeight.Black, fontSize = with(density) { (u * 1.05f).toSp() }) }
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
                        Die(dice.d2, g / 9.5f, if (dice.rolling) -spin * 1.2f else 0f, if (dice.rolling) bounce else land)
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
                TokenView(tokenOf(s), tk, PolyData.colors[s], Modifier.offset(x, y))
            }
            zoom?.let { SquareView(it, data.squares[it], g, u, st, data, false, zoomed = true) }
        }
        if (police) {
            val pt = rememberInfiniteTransition(label = "policia")
            val a by pt.animateFloat(0f, 1f, infiniteRepeatable(tween(500, easing = LinearEasing)), label = "luces")
            Box(Modifier.fillMaxSize().background(if (a < 0.5f) Brush.horizontalGradient(listOf(Color(0x80E53935), Color.Transparent), endX = with(density) { side.toPx() } * 0.6f) else Brush.horizontalGradient(listOf(Color.Transparent, Color(0x801E88E5)), startX = with(density) { side.toPx() } * 0.4f)))
        }
        if (cardFx != null) CardFxView(cardFx, side, onCardDone)
        if (banner != null) BannerView(banner, side)
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
    val deltas = remember { mutableStateListOf<Delta>() }
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
        val s = Poly.initGame(data, r.players.size, Poly.Cfg(r.money, r.goBonus, r.auction, r.pot, r.dblBonus), r.seed)
        acts.clear()
        for (a in r.acts) { Poly.applyText(s, a); acts.add(a) }
        st = s
        queue.clear()
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
        lastTurn = s.turn
        lastCash = s.cash.copyOf(); shownCash = s.cash.copyOf(); shownTurn = s.turn
        pathSet = emptySet(); slowSeat = -1; police = false; cardFx = null
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
            when (e.k) {
                "dice" -> {
                    pathSet = emptySet()
                    PolySound.play("dice")
                    // los dados giran cambiando de cara y se van frenando hasta quedarse en el resultado
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 1400) {
                        dice = DiceView((1..6).random(), (1..6).random(), true)
                        delay(60L + (System.currentTimeMillis() - t0) / 9)
                    }
                    dice = DiceView(e.d1, e.d2, false)
                    delay(350)
                }
                "move" -> {
                    val dir = e.dir
                    val steps = ((e.to - viewPos[e.seat]) * dir + 40) % 40
                    val from = viewPos[e.seat]
                    pathSet = (0..steps).map { (from + dir * it + 80) % 40 }.toSet()
                    var p = from
                    repeat(steps) {
                        p = (p + dir + 40) % 40
                        viewPos[e.seat] = p
                        PolySound.play("step")
                        delay(if (e.seat == mySeat() || steps < 8) 160 else 120)
                    }
                }
                "jail" -> {
                    // sirena, luces de policía y la ficha cruza el tablero en diagonal hasta la cárcel
                    PolySound.play("siren"); police = true; slowSeat = e.seat
                    delay(60)
                    viewPos[e.seat] = e.to
                    delay(3000)
                    slowSeat = -1; police = false
                }
                "pay", "debt" -> PolySound.play("pay")
                "go", "gain" -> PolySound.play("coin")
                "buy" -> PolySound.play("buy")
                "build" -> PolySound.play("build")
                "card" -> {
                    PolySound.play(if (e.sq == 1) "chest" else "card")
                    val fx = CardFx(if (e.sq == 1) "chest" else if (e.sq == 0) "chance" else "peza", if (e.sq == 1) "Caja de comunidad" else if (e.sq == 0) "Suerte" else "La Peza", seatName(e.seat), e.text)
                    cardFx = fx
                    ver++
                    var waited = 0
                    while (cardFx === fx && waited < 5800) { delay(100); waited += 100 }
                    cardFx = null
                }
                "win" -> PolySound.play("win")
            }
            if (e.m.isNotEmpty()) { log.add(say(e.m)); if (log.size > 40) log.removeAt(0) }
            ver++
        }
        for (i in 0 until s.n) viewPos[i] = s.pos[i]
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
        if (!fullScreen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = OnSurface) }
            Text("Evapoly", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = OnSurface, modifier = Modifier.weight(1f))
            IconButton(onClick = { fullScreen = true }) { Icon(Icons.Default.Fullscreen, "Pantalla completa", tint = OnSurface) }
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
                onCfg = { money, go, auc, pot, dbl -> scope.launch { try { Social.polyCfg(r, money, go, auc, pot, dbl); room = Social.polyRoom(roomId) } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Error") } } },
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
                        onSend = ::send, onTrade = { showTrade = true }, mod,
                    )
                }
                val board: @Composable () -> Unit = {
                    PolyBoard(side, data, s, viewPos, ::tokenOf, dice, pathSet, slowSeat, police, cardFx, banner, { cardFx = null }) { squareSel = it }
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
                            panel(Modifier.width(270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 44.dp, bottom = 8.dp))
                        } else panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        board()
                        Spacer(Modifier.height(if (fullScreen) 16.dp else 8.dp))
                        panel(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                    }
                }
                if (fullScreen) {
                    IconButton(
                        onClick = { fullScreen = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(42.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                    ) { Icon(Icons.Default.FullscreenExit, "Salir de pantalla completa", tint = OnSurface) }
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
    onJoin: () -> Unit, onLeave: () -> Unit, onRemove: () -> Unit, onTok: (Int) -> Unit, onCfg: (Int, Boolean, Boolean, Boolean, Boolean) -> Unit, onStart: () -> Unit, onInvite: () -> Unit,
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
                    onValueChangeFinished = { onCfg(money.toInt(), r.goBonus, r.auction, r.pot, r.dblBonus) },
                    colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("400 € al caer en la Salida", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.goBonus, { onCfg(r.money, it, r.auction, r.pot, r.dblBonus) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Subastas", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.auction, { onCfg(r.money, r.goBonus, it, r.pot, r.dblBonus) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bote en La Peza", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.pot, { onCfg(r.money, r.goBonus, r.auction, it, r.dblBonus) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Premio por dobles (50 € y 100 €)", color = OnSurface, modifier = Modifier.weight(1f))
                    Switch(r.dblBonus, { onCfg(r.money, r.goBonus, r.auction, r.pot, it) }, enabled = isHost, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = OnAccent))
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
private fun PolyButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), cta: Boolean = false, compact: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val hgt = if (compact) 40.dp else if (cta) 58.dp else 50.dp
    if (primary) {
        Button(onClick, modifier.height(hgt), enabled = enabled, shape = shape, contentPadding = PaddingValues(horizontal = 6.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)) { Text(label, fontWeight = FontWeight.ExtraBold, fontSize = if (cta) 17.sp else if (compact) 13.sp else 14.sp, maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(onClick, modifier.height(hgt), enabled = enabled, shape = shape, contentPadding = PaddingValues(horizontal = 6.dp), border = BorderStroke(1.5.dp, if (enabled) Accent else Outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1, softWrap = false) }
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
    log: List<String>, locked: Boolean, deltas: List<Delta>, compact: Boolean, frozen: Boolean, shownCash: IntArray?, shownTurn: Int, onSend: (String) -> Unit, onTrade: () -> Unit, modifier: Modifier,
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
                        .border(if (turn) 2.dp else 1.dp, if (turn) col else OnSurface.copy(alpha = 0.1f), shape)
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
                            else listOfNotNull(if (s.jail[i]) "Cárcel" else null, if (s.jf[i] > 0) "Carta ×${s.jf[i]}" else null, if (s.mc[i] > 0) "Maricarmen" else null, "$owned calles").joinToString(" · ")
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
        } else if (s.phase == "buy") {
            val q = data.squares[s.buy]
            Status("${q.n} · ${Poly.eur(q.p)}")
            Btns { B("Comprar", "b", true, s.cash[my] >= q.p, cta = true) }
            Btns { if (s.cfg.auction) B("Subastar", "a", false); B("No comprar", "n", false) }
        } else if (s.phase == "end") Btns { B("Terminar turno", "e", cta = true) }

        if (!frozen && my >= 0 && cur == my && (s.phase == "roll" || s.phase == "end") && t == null) {
            var confirmQuit by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onTrade, enabled = !locked) { Text("Negociar", color = Accent, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { confirmQuit = true }, enabled = !locked) { Text("Abandonar", color = Accent, fontWeight = FontWeight.Bold) }
            }
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
