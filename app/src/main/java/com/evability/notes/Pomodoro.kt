package com.evability.notes

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

enum class PomoPhase { IDLE, STUDY, REST }

/** Estado del pomodoro que ve la pantalla. Lo actualiza [PomodoroService], que sigue corriendo aunque se cierre la app. */
object Pomodoro {
    var phase by mutableStateOf(PomoPhase.IDLE)

    /** Fin de la fase actual, en [SystemClock.elapsedRealtime] (no le afectan los cambios de hora). */
    var endAt by mutableLongStateOf(0L)
    var phaseMs by mutableLongStateOf(0L)
    var completed by mutableIntStateOf(0)

    fun start(ctx: Context, studyMin: Int, restMin: Int) {
        Prefs.pomoStudy = studyMin
        Prefs.pomoRest = restMin
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, PomodoroService::class.java).putExtra("study", studyMin).putExtra("rest", restMin),
        )
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, PomodoroService::class.java))
    }
}

/**
 * Cuenta atrás en un servicio en primer plano (con notificación) para que no se pare al salir de la app.
 * Al acabar cada fase suena una alarma y empieza la siguiente (estudio ↔ descanso) hasta que se detiene.
 */
class PomodoroService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var studyMs = 0L
    private var restMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private val advance = Runnable { nextPhase() }
    private val stopRing = Runnable { ringtone?.stop() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        studyMs = intent.getIntExtra("study", 25).coerceAtLeast(1) * 60_000L
        restMs = intent.getIntExtra("rest", 5).coerceAtLeast(1) * 60_000L
        createChannels()
        Pomodoro.completed = 0
        begin(PomoPhase.STUDY)
        ServiceCompat.startForeground(
            this, RUNNING_ID, runningNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (wakeLock == null) {
            // Sin esto la CPU podría dormirse con la pantalla apagada y la alarma sonaría tarde.
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "evability:pomodoro")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun begin(phase: PomoPhase) {
        val ms = if (phase == PomoPhase.STUDY) studyMs else restMs
        Pomodoro.phase = phase
        Pomodoro.phaseMs = ms
        Pomodoro.endAt = SystemClock.elapsedRealtime() + ms
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, ms)
    }

    @SuppressLint("MissingPermission")
    private fun nextPhase() {
        val wasStudy = Pomodoro.phase == PomoPhase.STUDY
        if (wasStudy) Pomodoro.completed++
        begin(if (wasStudy) PomoPhase.REST else PomoPhase.STUDY)
        ring()
        val nm = NotificationManagerCompat.from(this)
        nm.notify(RUNNING_ID, runningNotification())
        val mins = (Pomodoro.phaseMs / 60_000).toInt()
        nm.notify(
            ALERT_ID,
            NotificationCompat.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (wasStudy) "¡A descansar!" else "¡A estudiar!")
                .setContentText(if (wasStudy) "Descansa $mins min" else "Nuevo ternurín de $mins min")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    private fun ring() {
        handler.removeCallbacks(stopRing)
        ringtone?.stop()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri)?.also {
            it.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            it.play()
        }
        handler.postDelayed(stopRing, 8_000)
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun runningNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PomodoroService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val studying = Pomodoro.phase == PomoPhase.STUDY
        return NotificationCompat.Builder(this, CH_RUNNING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (studying) "Estudiando" else "Descanso")
            .setContentText("Ternurines completados: ${Pomodoro.completed}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + (Pomodoro.endAt - SystemClock.elapsedRealtime()))
            .setContentIntent(openApp())
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "Ternurín en marcha", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Aviso de cambio de fase", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // el sonido lo reproduce el servicio como alarma
                enableVibration(false)
            }
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ringtone?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        Pomodoro.phase = PomoPhase.IDLE
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.evability.notes.POMODORO_STOP"
        private const val CH_RUNNING = "pomodoro_running"
        private const val CH_ALERT = "pomodoro_alert"
        private const val RUNNING_ID = 4101
        private const val ALERT_ID = 4102
    }
}
