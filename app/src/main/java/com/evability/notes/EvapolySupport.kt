package com.evability.notes

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.PathParser
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Tablero y fichas del Evapoly: salen de docs/data (la misma copia que usa la web) y se cargan una sola vez. */
object PolyData {
    class TokPart(val path: Path, val fill: Color?, val stroke: Color?, val width: Float, val alpha: Float)

    val colors = listOf(Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00))
    private var board: Poly.Data? = null
    private var tokens: List<Pair<String, List<TokPart>>> = emptyList()

    fun board(ctx: Context): Poly.Data {
        board?.let { return it }
        val d = Poly.load(ctx.assets.open("evapoly.json").bufferedReader().use { it.readText() })
        board = d
        return d
    }

    /** Acepta «#rgb», «#rrggbb» y «#aarrggbb» (Android no entiende el de 3 dígitos). */
    private fun color(s: String): Color {
        var h = s.removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        return runCatching { Color(android.graphics.Color.parseColor("#$h")) }.getOrDefault(Color.Gray)
    }

    private fun parts(ps: JSONArray): List<TokPart> = (0 until ps.length()).mapNotNull { k ->
        runCatching {
            val p = ps.getJSONObject(k)
            TokPart(
                PathParser().parsePathString(p.getString("d")).toPath(),
                if (p.isNull("f")) null else color(p.getString("f")),
                if (p.isNull("s")) null else color(p.getString("s")),
                p.optDouble("w", 0.0).toFloat(), p.optDouble("o", 1.0).toFloat(),
            )
        }.getOrNull()
    }

    fun tokens(ctx: Context): List<Pair<String, List<TokPart>>> {
        if (tokens.isNotEmpty()) return tokens
        val arr = JSONArray(ctx.assets.open("evapoly-tokens.json").bufferedReader().use { it.readText() })
        tokens = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { t -> t.getString("n") to parts(t.getJSONArray("p")) } }
        return tokens
    }

    private var icons: Map<String, List<TokPart>> = emptyMap()

    /** Iconos de las casillas (data/evapoly-icons.json): suerte, caja, estación, bombilla, grifo, impuestos, salida, cárcel, coche, corona, dados. */
    fun icons(ctx: Context): Map<String, List<TokPart>> {
        if (icons.isNotEmpty()) return icons
        val o = org.json.JSONObject(ctx.assets.open("evapoly-icons.json").bufferedReader().use { it.readText() })
        icons = o.keys().asSequence().associateWith { parts(o.getJSONArray(it)) }
        return icons
    }

    val cap = StrokeCap.Round
    val join = StrokeJoin.Round
}

/** Sonidos del Evapoly sintetizados (mismo diseño que en la web): dados, pasos, monedas, compra, cárcel, cartas, turno y victoria. */
object PolySound {
    private const val RATE = 22050
    private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** Reproduce un sonido grabado (assets) una vez. */
    private fun clip(file: String, vol: Float) {
        val c = app ?: return
        runCatching {
            val fd = c.assets.openFd(file)
            val mp = MediaPlayer()
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setVolume(vol, vol)
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { m, _, _ -> runCatching { m.release() }; true }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        }
    }

    private fun tone(buf: FloatArray, start: Double, f1: Double, f2: Double, dur: Double, amp: Double, square: Boolean = false) {
        val s0 = (start * RATE).toInt()
        val n = (dur * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = f1 * (f2 / f1).pow(t)
            phase += 2 * PI * f / RATE
            val env = (if (i < 0.004 * RATE) i / (0.004 * RATE) else 1.0) * exp(-5.0 * t)
            val w = if (square) (if (sin(phase) >= 0) 1.0 else -1.0) * 0.5 else sin(phase)
            val idx = s0 + i
            if (idx in buf.indices) buf[idx] += (w * amp * env).toFloat()
        }
    }

    private fun synth(kind: String): FloatArray = when (kind) {
        "chest" -> FloatArray((1.6 * RATE).toInt()).also { tone(it, 0.0, 180.0, 180.0, 0.1, 0.3, true); tone(it, 0.45, 520.0, 900.0, 0.5, 0.3); tone(it, 1.1, 784.0, 784.0, 0.35, 0.35) }
        "step" -> FloatArray((0.06 * RATE).toInt()).also { tone(it, 0.0, 520.0, 520.0, 0.04, 0.2) }
        "coin" -> FloatArray((0.35 * RATE).toInt()).also { tone(it, 0.0, 988.0, 988.0, 0.09, 0.45); tone(it, 0.09, 1319.0, 1319.0, 0.22, 0.45) }
        "pay" -> FloatArray((0.3 * RATE).toInt()).also { tone(it, 0.0, 440.0, 440.0, 0.1, 0.45); tone(it, 0.1, 330.0, 330.0, 0.18, 0.45) }
        "buy" -> FloatArray((0.55 * RATE).toInt()).also { tone(it, 0.0, 523.0, 523.0, 0.12, 0.4); tone(it, 0.1, 659.0, 659.0, 0.12, 0.4); tone(it, 0.2, 784.0, 784.0, 0.3, 0.4) }
        "build" -> FloatArray((0.2 * RATE).toInt()).also { tone(it, 0.0, 200.0, 200.0, 0.07, 0.3, true); tone(it, 0.09, 300.0, 300.0, 0.07, 0.3, true) }
        "jail" -> FloatArray((0.55 * RATE).toInt()).also { tone(it, 0.0, 160.0, 70.0, 0.5, 0.35, true) }
        "card" -> FloatArray((0.25 * RATE).toInt()).also { tone(it, 0.0, 700.0, 700.0, 0.08, 0.4); tone(it, 0.08, 1050.0, 1050.0, 0.14, 0.4) }
        "turn" -> FloatArray((0.4 * RATE).toInt()).also { tone(it, 0.0, 660.0, 660.0, 0.12, 0.4); tone(it, 0.12, 880.0, 880.0, 0.22, 0.4) }
        else -> FloatArray((0.95 * RATE).toInt()).also { b -> doubleArrayOf(523.0, 659.0, 784.0, 1047.0).forEachIndexed { i, f -> tone(b, i * 0.14, f, f, 0.35, 0.4) } }
    }

    fun play(kind: String) {
        if (kind == "dice") { clip("evapoly-dados.mp3", 0.9f); return }
        if (kind == "siren") { clip("evapoly-policia.mp3", 0.8f); return }
        runCatching {
            val data = synth(kind).map { (it.coerceIn(-1f, 1f) * 32000).toInt().toShort() }.toShortArray()
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.setNotificationMarkerPosition(data.size - 1)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) { runCatching { t.release() } }
                    override fun onPeriodicNotification(t: AudioTrack) {}
                },
                Handler(Looper.getMainLooper()),
            )
            track.play()
        }
    }
}

/** Música de fondo de las salas del Evapoly (assets/evapoly.mp3): en bucle y más bajita que los sonidos. */
object PolyMusic {
    private var player: MediaPlayer? = null
    private const val VOLUME = 0.25f

    fun start(ctx: Context) {
        if (!Prefs.polyMusic || player != null) return
        runCatching {
            val fd = ctx.assets.openFd("evapoly.mp3")
            val p = MediaPlayer()
            player = p
            p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            p.isLooping = true
            p.setVolume(VOLUME, VOLUME)
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            // se prepara en segundo plano: así abrir la sala no espera a que cargue la canción
            p.setOnPreparedListener { if (player === it) it.start() }
            p.setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; if (player === mp) player = null; true }
            p.prepareAsync()
        }.onFailure { player = null }
    }

    fun stop() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
