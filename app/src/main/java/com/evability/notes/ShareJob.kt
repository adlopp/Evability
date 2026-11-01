package com.evability.notes

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Con la app cerrada o en segundo plano, Android la despierta cada ~15 minutos (el mínimo que permite) para ver si un amigo
 * te ha enviado un documento: si es así lo descarga, lo guarda en la carpeta de amigos y avisa con una notificación.
 * Un aviso instantáneo exigiría un servidor de notificaciones push, que no tiene el plan gratuito sin tarjeta.
 */
class ShareCheckService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        work = CoroutineScope(Dispatchers.IO).launch {
            try {
                Prefs.init(ctx)
                Social.load()
                if (Social.session != null && !AppVisibility.foreground) {
                    val store = Store(ctx)
                    Social.checkShares { name, from, bytes ->
                        val replaced = store.receive(name, from, bytes).second
                        ShareNotifier.show(ctx, name, from, replaced)
                        replaced
                    }
                }
            } catch (e: Exception) {
                // sin red o sin sesión: se reintenta en el próximo ciclo
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}

object ShareJob {
    private const val ID = 4201

    /** Programa la comprobación periódica (sin reiniciar el reloj si ya estaba programada). */
    fun schedule(ctx: Context) {
        try {
            val scheduler = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(ctx, ShareCheckService::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        } catch (e: Exception) {
            // los avisos en segundo plano son un extra: si el sistema los rechaza, la app debe abrir igualmente
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java)?.cancel(ID)
    }
}

object ShareNotifier {
    private const val CHANNEL = "shares"

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, name: String, from: String, replaced: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Documentos de amigos", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            name.hashCode() xor from.hashCode(),
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (replaced) "$from te ha enviado una versión nueva" else "$from te ha enviado un documento")
                .setContentText("«$name» está en la carpeta ${Prefs.friendsName}")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
