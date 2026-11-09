package com.evability.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Si la app está en pantalla ella misma se ocupa de los documentos que llegan; fuera de ella, lo hace este servicio. */
object AppVisibility {
    @Volatile var foreground = false
}

/**
 * «Avisos al momento»: mientras hay sesión de Amigos iniciada, comprueba cada 30 s si un amigo te ha enviado un documento
 * (aunque la app esté cerrada), lo guarda en la carpeta de amigos y lo avisa con una notificación del sistema.
 * Mantiene una notificación fija y silenciosa en la barra (Android lo exige a los servicios que siguen activos).
 */
class FriendsWatchService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Pendiente de tus amigos", NotificationManager.IMPORTANCE_MIN))
        ServiceCompat.startForeground(
            this, ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (scope == null) {
            val ctx = applicationContext
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { s ->
                s.launch {
                    Prefs.init(ctx)
                    Social.load()
                    SimpleCounter.load()
                    val store = Store(ctx)
                    while (isActive) {
                        if (Social.session == null || !Prefs.quickAlerts) {
                            stopSelf()
                            break
                        }
                        if (!AppVisibility.foreground && (Pomodoro.studying || SimpleCounter.running)) {
                            try { Social.heartbeat(true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* sin red */ }
                        }
                        if (!AppVisibility.foreground) {
                            try {
                                Social.checkShares { name, from, bytes ->
                                    val replaced = store.receive(name, from, bytes).second
                                    ShareNotifier.show(ctx, name, from, replaced)
                                    replaced
                                }
                                for (a in Social.newChatAlerts()) ChatNotifier.show(ctx, a)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // sin red: se reintenta en el siguiente ciclo
                            }
                        }
                        delay(60_000)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Evability")
            .setContentText("Pendiente de los mensajes y documentos de tus amigos")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "friends_watch"
        private const val ID = 4301
    }
}

object FriendsWatch {
    fun start(ctx: Context) {
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, FriendsWatchService::class.java))
        } catch (e: Throwable) {
            // Android no deja arrancarlo desde segundo plano: se arrancará la próxima vez que se abra la app
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, FriendsWatchService::class.java))
    }
}
