package com.evability.notes

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * Piezas «Fresca» (sadsnake1, CC BY-NC-SA 4.0, colección de lichess). Están en res/raw/chess_pieces.json como trazados SVG
 * ya convertidos (relleno, borde, transformación y opacidad de cada parte) y se dibujan con el Canvas de Compose.
 */
object ChessPieces {
    private class Part(
        val path: Path, val fill: Color?, val stroke: Color?, val strokeWidth: Float,
        val cap: StrokeCap, val join: StrokeJoin, val matrix: Matrix?, val alpha: Float,
    )

    private var pieces: Map<Char, List<Part>> = emptyMap()

    private fun color(s: String): Color {
        var h = s.removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        return Color(android.graphics.Color.parseColor("#$h"))
    }

    fun load(ctx: Context) {
        if (pieces.isNotEmpty()) return
        val root = JSONObject(ctx.resources.openRawResource(R.raw.chess_pieces).bufferedReader().use { it.readText() })
        val out = HashMap<Char, List<Part>>()
        for (key in root.keys()) {
            val arr: JSONArray = root.getJSONArray(key)
            out[key[0]] = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val path = PathParser().parsePathString(o.getString("d")).toPath()
                path.fillType = if (o.optString("fr") == "evenodd") PathFillType.EvenOdd else PathFillType.NonZero
                val tf = o.optJSONArray("tf")?.let { t ->
                    // [a b c d e f] → matriz 4×4 en columnas
                    Matrix(
                        floatArrayOf(
                            t.getDouble(0).toFloat(), t.getDouble(1).toFloat(), 0f, 0f,
                            t.getDouble(2).toFloat(), t.getDouble(3).toFloat(), 0f, 0f,
                            0f, 0f, 1f, 0f,
                            t.getDouble(4).toFloat(), t.getDouble(5).toFloat(), 0f, 1f,
                        ),
                    )
                }
                Part(
                    path,
                    if (o.isNull("fill")) null else color(o.getString("fill")),
                    if (o.isNull("stroke")) null else color(o.getString("stroke")),
                    o.optDouble("sw", 1.0).toFloat(),
                    when (o.optString("cap")) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                    when (o.optString("join")) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                    tf,
                    o.optDouble("op", 1.0).toFloat(),
                )
            }
        }
        pieces = out
    }

    /** Dibuja la pieza `c` (mayúscula = blanca, minúscula = negra) en un cuadrado de lado `size` con la esquina en `topLeft`. */
    fun DrawScope.drawPiece(c: Char, topLeft: Offset, size: Float) {
        val parts = pieces[c] ?: return
        withTransform({
            translate(topLeft.x, topLeft.y)
            scale(size / 50f, size / 50f, Offset.Zero)
        }) {
            for (p in parts) {
                val draw: DrawScope.() -> Unit = {
                    if (p.fill != null) drawPath(p.path, p.fill, alpha = p.alpha)
                    if (p.stroke != null) drawPath(p.path, p.stroke, alpha = p.alpha, style = Stroke(p.strokeWidth, cap = p.cap, join = p.join))
                }
                if (p.matrix != null) withTransform({ transform(p.matrix) }) { draw() } else draw()
            }
        }
    }
}
