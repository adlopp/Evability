package com.evability.notes

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Graba un audio corto (AAC mono a 24 kbps, unos 3 KB por segundo) para mandarlo por el chat. */
object VoiceRecorder {
    /** Un audio puede durar como máximo 2 minutos. */
    const val MAX_MS = 120_000L

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun start(ctx: Context): Boolean {
        cancel()
        return try {
            val target = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(24_000)
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    /** Volumen del micrófono ahora mismo, de 0 a 1 (para la onda que sube mientras hablas). */
    fun level(): Float = runCatching { Math.sqrt((recorder?.maxAmplitude ?: 0) / 32768.0).toFloat().coerceIn(0f, 1f) }.getOrDefault(0f)

    fun elapsed(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Termina la grabación. Devuelve el archivo y su duración, o null si no hay nada (p. ej. un toque demasiado corto). */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val dur = elapsed()
        val f = file
        recorder = null
        file = null
        try {
            r.stop()
        } catch (e: RuntimeException) {
            f?.delete()
            return null
        } finally {
            runCatching { r.release() }
        }
        if (f == null || dur < 600) {
            f?.delete()
            return null
        }
        return f to dur
    }

    fun cancel() {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        file?.delete()
        file = null
    }
}

/** Reproduce un audio a la vez; la pantalla mira `playingId` y `position` para pintar el botón y la barra. */
object VoicePlayer {
    var playingId by mutableStateOf<String?>(null)
        private set
    var position by mutableLongStateOf(0L)
        private set

    private var player: MediaPlayer? = null

    fun toggle(file: File, id: String) {
        val wasPlaying = playingId == id
        stop()
        if (wasPlaying) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playingId = id
        } catch (e: Exception) {
            stop()
        }
    }

    /** Actualiza la posición (la pantalla lo llama cada poco mientras suena). */
    fun tick() {
        position = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        playingId = null
        position = 0L
    }
}
