package com.evability.notes

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Guarda los PDFs importados, el índice de recientes y las anotaciones en el almacenamiento privado de la app. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "docs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun pdfFile(id: String) = File(dir, "$id.pdf")
    fun thumbFile(id: String) = File(dir, "$id.png")
    private fun annFile(id: String) = File(dir, "$id.ann.json")

    @Synchronized
    fun loadIndex(): List<DocInfo> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                DocInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    pages = o.getInt("pages"),
                    lastOpened = o.getLong("lastOpened"),
                    added = o.getLong("added"),
                    lastPage = o.optInt("lastPage", 0),
                    extra = o.optInt("extra", 0),
                )
            }.sortedByDescending { it.lastOpened }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveIndex(list: List<DocInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("pages", it.pages)
                    .put("lastOpened", it.lastOpened).put("added", it.added).put("lastPage", it.lastPage).put("extra", it.extra)
            )
        }
        writeAtomic(indexFile, arr.toString())
    }

    @Synchronized
    private fun update(id: String, f: (DocInfo) -> DocInfo) {
        saveIndex(loadIndex().map { if (it.id == id) f(it) else it })
    }

    fun touch(id: String) = update(id) { it.copy(lastOpened = System.currentTimeMillis()) }
    fun setLastPage(id: String, page: Int) = update(id) { it.copy(lastPage = page) }

    fun setExtra(id: String, extra: Int) = update(id) { it.copy(extra = extra) }

    /** Crea un documento nuevo a partir de una plantilla (o una hoja en blanco). */
    fun createFromTemplate(kind: TemplateKind): DocInfo {
        val id = UUID.randomUUID().toString()
        val file = pdfFile(id)
        try {
            Templates.writePdf(kind, file)
            val pages = PdfDoc(file).use { doc ->
                doc.writeThumbnail(thumbFile(id))
                doc.pageCount
            }
            val now = System.currentTimeMillis()
            val info = DocInfo(id, kind.docName, pages, now, now)
            saveIndex(listOf(info) + loadIndex())
            return info
        } catch (e: Exception) {
            file.delete()
            thumbFile(id).delete()
            throw e
        }
    }

    @Synchronized
    fun delete(id: String) {
        saveIndex(loadIndex().filter { it.id != id })
        listOf(pdfFile(id), thumbFile(id), annFile(id)).forEach { it.delete() }
    }

    /** Copia el PDF elegido a la app (así sigue disponible aunque el original se mueva) y crea la miniatura. */
    fun import(uri: Uri): DocInfo {
        val id = UUID.randomUUID().toString()
        val file = pdfFile(id)
        try {
            appContext.contentResolver.openInputStream(uri)!!.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
            val pages = PdfDoc(file).use { doc ->
                doc.writeThumbnail(thumbFile(id))
                doc.pageCount
            }
            val now = System.currentTimeMillis()
            val info = DocInfo(id, displayName(uri), pages, now, now)
            saveIndex(listOf(info) + loadIndex())
            return info
        } catch (e: Exception) {
            file.delete()
            thumbFile(id).delete()
            throw e
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        return (name ?: "Documento").removeSuffix(".pdf").removeSuffix(".PDF")
    }

    @Synchronized
    fun saveAnns(id: String, anns: List<Ann>) {
        val arr = JSONArray()
        anns.forEach { a ->
            arr.put(
                when (a) {
                    is Stroke -> JSONObject().put("t", "s").put("id", a.id).put("p", a.page)
                        .put("h", a.highlighter).put("c", a.color).put("w", a.width.toDouble())
                        .put("pts", JSONArray().also { ja ->
                            a.points.forEach { ja.put(it.x.toDouble()); ja.put(it.y.toDouble()) }
                        })
                    is Cover -> JSONObject().put("t", "c").put("id", a.id).put("p", a.page).put("c", a.color)
                        .put("x", a.x.toDouble()).put("y", a.y.toDouble()).put("w", a.w.toDouble()).put("h", a.h.toDouble())
                    is Postit -> JSONObject().put("t", "p").put("id", a.id).put("p", a.page)
                        .put("x", a.x.toDouble()).put("y", a.y.toDouble()).put("s", a.size.toDouble()).put("r", a.rot.toDouble())
                        .put("strokes", JSONArray().also { sa ->
                            a.strokes.forEach { st ->
                                sa.put(
                                    JSONObject().put("c", st.color).put("w", st.width.toDouble()).put("h", st.highlighter)
                                        .put("pts", JSONArray().also { ja -> st.points.forEach { ja.put(it.x.toDouble()); ja.put(it.y.toDouble()) } })
                                )
                            }
                        })
                    is Sticker -> JSONObject().put("t", "k").put("id", a.id).put("p", a.page).put("src", a.src)
                        .put("x", a.x.toDouble()).put("y", a.y.toDouble()).put("s", a.size.toDouble()).put("r", a.rot.toDouble())
                    is TextNote -> JSONObject().put("t", "x").put("id", a.id).put("p", a.page)
                        .put("text", a.text).put("c", a.color).put("s", a.size.toDouble())
                        .put("x", a.x.toDouble()).put("y", a.y.toDouble())
                }
            )
        }
        writeAtomic(annFile(id), arr.toString())
    }

    @Synchronized
    fun loadAnns(id: String): List<Ann> {
        val f = annFile(id)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                if (o.getString("t") == "s") {
                    val pj = o.getJSONArray("pts")
                    Stroke(
                        id = o.getLong("id"), page = o.getInt("p"), highlighter = o.getBoolean("h"),
                        color = o.getInt("c"), width = o.getDouble("w").toFloat(),
                        points = (0 until pj.length() / 2).map { Offset(pj.getDouble(it * 2).toFloat(), pj.getDouble(it * 2 + 1).toFloat()) },
                    )
                } else if (o.getString("t") == "c") {
                    Cover(
                        id = o.getLong("id"), page = o.getInt("p"), color = o.getInt("c"),
                        x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                        w = o.getDouble("w").toFloat(), h = o.getDouble("h").toFloat(),
                    )
                } else if (o.getString("t") == "p") {
                    Postit(
                        id = o.getLong("id"), page = o.getInt("p"), strokes = parsePostitStrokes(o.optJSONArray("strokes")),
                        x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                        size = o.getDouble("s").toFloat(), rot = o.getDouble("r").toFloat(),
                    )
                } else if (o.getString("t") == "k") {
                    Sticker(
                        id = o.getLong("id"), page = o.getInt("p"), src = o.getString("src"),
                        x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                        size = o.getDouble("s").toFloat(), rot = o.getDouble("r").toFloat(),
                    )
                } else {
                    TextNote(
                        id = o.getLong("id"), page = o.getInt("p"), text = o.getString("text"),
                        color = o.getInt("c"), size = o.getDouble("s").toFloat(),
                        x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePostitStrokes(arr: JSONArray?): List<PostitStroke> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val pj = o.getJSONArray("pts")
            PostitStroke(
                color = o.getInt("c"), width = o.getDouble("w").toFloat(), highlighter = o.getBoolean("h"),
                points = (0 until pj.length() / 2).map { Offset(pj.getDouble(it * 2).toFloat(), pj.getDouble(it * 2 + 1).toFloat()) },
            )
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
