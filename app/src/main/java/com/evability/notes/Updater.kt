package com.evability.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones desde GitHub Releases: la etiqueta de cada release es "v<versionCode>" (p. ej. "v3") y lleva el
 * APK como archivo adjunto. Android solo instala la actualización si está firmada con la misma clave que la app instalada.
 */
object Updater {
    const val REPO = "adlopp/Evability"

    class Release(val versionCode: Int, val name: String, val notes: String, val url: String)

    fun installedVersion(ctx: Context): Pair<Int, String> {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
        return code to (pi.versionName ?: "?")
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "Evability-Updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** La release más reciente si es más nueva que la instalada; null si ya estás al día. Lanza excepción si falla la red. */
    fun check(ctx: Context): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (conn.responseCode == 404) return null // todavía no hay ninguna release
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.getString("tag_name").trimStart('v', 'V').toIntOrNull() ?: return null
            if (code <= installedVersion(ctx).first) return null
            val assets = json.getJSONArray("assets")
            var apk: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) apk = a.getString("browser_download_url")
            }
            return Release(code, json.optString("name", "Versión $code"), json.optString("body", ""), apk ?: return null)
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga el APK a la caché de la app. `onProgress` recibe valores de 0 a 1. */
    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, "Evability.apk")
        val conn = open(url)
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Abre el ajuste "Instalar apps desconocidas" de Evability. */
    fun openInstallSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Lanza el instalador del sistema (el usuario pulsa "Instalar"). */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
