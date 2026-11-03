package com.evability.notes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sonidos del ajedrez sintetizados (no hay archivos): un «toc» de madera al soltar la pieza, un golpe más grave al capturar,
 * un tintineo para el jaque y unas notas al acabar. Mismo diseño que en la web (chess.js).
 * pick = coger una pieza · move = soltarla · capture · castle · check · end
 */
object ChessSound {
    private const val RATE = 22050

    private fun tone(buf: FloatArray, start: Double, f1: Double, f2: Double, dur: Double, amp: Double) {
        val s0 = (start * RATE).toInt()
        val n = (dur * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = f1 * (f2 / f1).pow(t) // barrido exponencial de frecuencia
            phase += 2 * PI * f / RATE
            val env = (if (i < 0.004 * RATE) i / (0.004 * RATE) else 1.0) * exp(-5.5 * t)
            val idx = s0 + i
            if (idx in buf.indices) buf[idx] += (sin(phase) * amp * env).toFloat()
        }
    }

    private fun noise(buf: FloatArray, start: Double, dur: Double, amp: Double, smooth: Double) {
        val s0 = (start * RATE).toInt()
        val n = (dur * RATE).toInt()
        val rnd = Random(7)
        var y = 0.0
        for (i in 0 until n) {
            y += smooth * ((rnd.nextDouble() * 2 - 1) - y) // filtro paso bajo
            val idx = s0 + i
            if (idx in buf.indices) buf[idx] += (y * amp * (1 - i.toDouble() / n)).toFloat()
        }
    }

    private fun synth(kind: String): FloatArray = when (kind) {
        "pick" -> FloatArray((0.06 * RATE).toInt()).also { tone(it, 0.0, 1900.0, 1900.0, 0.03, 0.07) }
        "move" -> FloatArray((0.16 * RATE).toInt()).also { tone(it, 0.0, 210.0, 130.0, 0.09, 0.45); noise(it, 0.0, 0.018, 0.55, 0.35) }
        "capture" -> FloatArray((0.26 * RATE).toInt()).also { tone(it, 0.0, 160.0, 85.0, 0.14, 0.65); noise(it, 0.0, 0.04, 0.9, 0.3) }
        "castle" -> FloatArray((0.36 * RATE).toInt()).also {
            tone(it, 0.0, 210.0, 130.0, 0.09, 0.45); noise(it, 0.0, 0.018, 0.55, 0.35)
            tone(it, 0.13, 210.0, 130.0, 0.09, 0.45); noise(it, 0.13, 0.018, 0.55, 0.35)
        }
        "check" -> FloatArray((0.4 * RATE).toInt()).also { tone(it, 0.0, 880.0, 880.0, 0.12, 0.25); tone(it, 0.11, 1175.0, 1175.0, 0.2, 0.25) }
        else -> FloatArray((0.95 * RATE).toInt()).also { buf ->
            doubleArrayOf(523.0, 659.0, 784.0, 1047.0).forEachIndexed { i, f -> tone(buf, i * 0.14, f, f, 0.35, 0.22) }
        }
    }

    fun play(kind: String) {
        runCatching {
            val data = synth(kind).map { (it.coerceIn(-1f, 1f) * 32000).toInt().toShort() }.toShortArray()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
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
