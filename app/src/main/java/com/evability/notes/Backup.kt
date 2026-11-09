package com.evability.notes

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia de seguridad en un .zip: `pdf/<id>.pdf`, `anns/<id>.json` y `manifest.json` (con los datos de cada documento,
 * las carpetas y las pegatinas propias). Es la misma estructura que la de la versión web. Las anotaciones de Android y las
 * de la web se guardan con campos distintos, así que cada copia lleva su `platform` y solo se recuperan las del mismo sistema.
 */
object Backup {
    private val ID = Regex("^[A-Za-z0-9_-]{1,80}$")

    /** Escribe la copia de todos los documentos. Devuelve cuántos documentos entraron. */
    fun export(store: Store, out: OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        var written = 0
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            val docs = store.loadIndex()
            val arr = JSONArray()
            docs.forEachIndexed { i, d ->
                val pdf = store.pdfFile(d.id)
                if (pdf.exists()) {
                    zip.putNextEntry(ZipEntry("pdf/${d.id}.pdf"))
                    pdf.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("anns/${d.id}.json"))
                    zip.write(store.annsText(d.id).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    val o = store.infoJson(d)
                    val thumb = store.thumbFile(d.id)
                    if (thumb.exists()) o.put("thumb", "data:image/png;base64," + Base64.encodeToString(thumb.readBytes(), Base64.NO_WRAP))
                    arr.put(o)
                    written++
                }
                onProgress(i + 1, docs.size)
            }
            val stickers = JSONObject()
            StickerBitmaps.userFiles().forEach { (name, f) ->
                stickers.put(name, "data:image/png;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
            val manifest = JSONObject().put("app", "evability").put("platform", "android").put("version", 1)
                .put("created", System.currentTimeMillis()).put("folders", JSONArray(store.folders()))
                .put("docs", arr).put("stickers", stickers)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return written
    }

    /** Recupera los documentos de una copia que aún no están en la app. Devuelve cuántos se añadieron. */
    fun import(ctx: Context, store: Store, input: InputStream): Int {
        val tmp = File(ctx.cacheDir, "backup-${System.nanoTime()}").apply { mkdirs() }
        try {
            var manifestText: String? = null
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    if (!e.isDirectory) {
                        if (name == "manifest.json") {
                            manifestText = zip.readBytes().toString(Charsets.UTF_8)
                        } else if (name.startsWith("pdf/") || name.startsWith("anns/")) {
                            val id = name.substringAfter('/').substringBeforeLast('.')
                            if (ID.matches(id)) File(tmp, name.replace('/', '_')).outputStream().use { zip.copyTo(it) }
                        }
                    }
                    e = zip.nextEntry
                }
            }
            val m = JSONObject(manifestText ?: throw IllegalArgumentException("No es una copia de Evability"))
            if (m.optString("app") != "evability") throw IllegalArgumentException("No es una copia de Evability")
            val sameSystem = m.optString("platform") == "android"
            var added = 0
            val docs = m.optJSONArray("docs") ?: JSONArray()
            for (i in 0 until docs.length()) {
                try {
                    val o = docs.getJSONObject(i)
                    val id = o.getString("id")
                    if (!ID.matches(id)) continue
                    val pdf = File(tmp, "pdf_$id.pdf").takeIf { it.exists() } ?: continue
                    val anns = if (sameSystem) File(tmp, "anns_$id.json").takeIf { it.exists() }?.readText() else null
                    val thumb = o.optString("thumb").substringAfter("base64,", "").takeIf { it.isNotEmpty() }
                        ?.let { Base64.decode(it, Base64.DEFAULT) }
                    if (store.restoreDoc(store.parseInfo(o), pdf, anns, thumb)) added++
                } catch (e: Exception) { /* un documento dañado no impide recuperar los demás */ }
            }
            val folders = m.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until folders.length()) store.addFolder(folders.optString(i))
            if (sameSystem) {
                val st = m.optJSONObject("stickers")
                st?.keys()?.forEach { name ->
                    try {
                        val b64 = st.getString(name).substringAfter("base64,", "")
                        if (b64.isNotEmpty()) StickerBitmaps.restoreUser(name, Base64.decode(b64, Base64.DEFAULT))
                    } catch (e: Exception) { /* pegatina dañada */ }
                }
            }
            return added
        } finally {
            tmp.deleteRecursively()
        }
    }
}
