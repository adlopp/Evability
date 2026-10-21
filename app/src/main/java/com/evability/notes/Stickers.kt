package com.evability.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Pegatinas: `src` es "e:<emoji>" (catálogo incluido) o "f:<archivo>" (imagen importada por el usuario,
 * guardada en filesDir/stickers).
 */
object StickerBitmaps {
    private lateinit var dir: File
    private lateinit var appContext: Context

    /** Un pack incluido en la app: los archivos de assets/stickers que empiezan por `prefix`. */
    class Pack(val title: String, val prefix: String, val cover: String)

    val PACKS = listOf(
        Pack("Gatitos", "cat_", "cat_tabby_sit"),
        Pack("Hamsters", "ham_", "ham_golden_seed"),
        Pack("Pollitos y pájaros", "bir_", "bir_chick_seed"),
    )

    private val packCache = HashMap<String, List<String>>()

    /** Nombres (sin extensión) de las pegatinas del pack, con la portada primero. */
    fun items(pack: Pack): List<String> = synchronized(packCache) {
        packCache.getOrPut(pack.prefix) {
            (appContext.assets.list("stickers") ?: emptyArray())
                .filter { it.startsWith(pack.prefix) && it.endsWith(".png") }
                .map { it.removeSuffix(".png") }
                .sorted()
                .sortedBy { if (it == pack.cover) 0 else 1 }
        }
    }

    val EMOJI = listOf(
        "🐱", "😺", "😻", "🐈", "🐾", "🐶", "🐰", "🐻", "🧸", "🐼", "🐨", "🦊", "🐹", "🐥", "🐣", "🦄", "🐷", "🐸", "🦋", "🐝",
        "🎀", "💖", "💕", "💗", "✨", "⭐", "🌟", "🌸", "🌷", "🌼", "🍓", "🍒", "🍰", "🧁", "🍭", "☁️", "🌈", "🌙", "💌", "📌", "✅", "❗", "🔖",
        // Otoño
        "🍂", "🍁", "🍄", "🎃", "🌰", "🍎", "🍐", "🍇", "🌾", "🧣", "🧤", "☕", "🍵", "🥧", "🍯", "🕯️", "🦔", "🐿️", "🦉", "👻",
    )

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(context.filesDir, "stickers").apply { mkdirs() }
    }

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val sources = HashMap<String, Bitmap?>()

    private fun bucket(px: Int) = ((px.coerceIn(24, 1200) + 31) / 32) * 32

    /** Bitmap cuadrado de `px` píxeles (aprox.) con la pegatina centrada. */
    fun get(src: String, px: Int): Bitmap? {
        val p = bucket(px)
        val key = "$src@$p"
        cache.get(key)?.let { return it }
        val bmp = when {
            src.startsWith("e:") -> renderEmoji(src.substring(2), p)
            src.startsWith("a:") -> renderFile(src, p)
            else -> renderFile(src, p)
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun renderEmoji(emoji: String, p: Int): Bitmap {
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = p * 0.78f
            textAlign = Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        Canvas(bmp).drawText(emoji, p / 2f, p / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }

    @Synchronized
    private fun renderFile(key: String, p: Int): Bitmap? {
        val name = key.substring(2)
        val src = sources.getOrPut(key) {
            if (key.startsWith("a:")) {
                appContext.assets.open("stickers/$name.png").use { BitmapFactory.decodeStream(it) }
            } else {
                BitmapFactory.decodeFile(File(dir, name).path)
            }
        } ?: return null
        val bmp = Bitmap.createBitmap(p, p, Bitmap.Config.ARGB_8888)
        val s = min(p / src.width.toFloat(), p / src.height.toFloat())
        val w = src.width * s
        val h = src.height * s
        Canvas(bmp).drawBitmap(src, null, RectF((p - w) / 2f, (p - h) / 2f, (p + w) / 2f, (p + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        return bmp
    }

    fun listUser(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.lastModified() }?.map { it.name } ?: emptyList()

    /** Copia una imagen de la galería a la app (reducida a 1024 px como máximo). Devuelve el nombre de archivo. */
    fun importUser(context: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) {
            null
        } else {
            val name = "u_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}.png"
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name
        }
    } catch (e: Exception) {
        null
    }
}
