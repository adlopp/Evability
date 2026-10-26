package com.evability.notes

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Análisis de documentos con la API de Gemini. Cada usuario usa su propia clave (se guarda en [Prefs]). */
object Gemini {
    /**
     * Modelos con nivel gratuito, por orden de preferencia: si uno está saturado o sin cupo se prueba el siguiente.
     * Los Gemini 2.5 son los que incluyen búsqueda de Google gratis (unas 500 al día); los 3.x no.
     */
    private fun models(mode: Mode) = when (mode) {
        Mode.RESEARCH -> listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")
        Mode.SUMMARY -> listOf("gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3.5-flash-lite")
    }

    const val KEY_URL = "https://aistudio.google.com/apikey"

    private const val BASE = "https://generativelanguage.googleapis.com"

    /** Hasta este tamaño el PDF va dentro de la petición; por encima se sube antes con la API de archivos. */
    private const val INLINE_MAX = 8 * 1024 * 1024
    private const val MAX_PDF_BYTES = 50 * 1024 * 1024

    /** `retryable`: fallo temporal de Google (saturación) que merece otro intento; `fallback`: conviene probar con otro modelo. */
    class GeminiException(message: String, val retryable: Boolean = false, val fallback: Boolean = false) : Exception(message)

    enum class Mode { SUMMARY, RESEARCH }

    class Result(val text: String, val sources: List<Pair<String, String>>)

    /** `onProgress(etapa, fracción)`: la fracción es null cuando no se puede saber cuánto falta (barra sin porcentaje). */
    fun analyze(apiKey: String, pdf: File, name: String, mode: Mode, onProgress: (String, Float?) -> Unit = { _, _ -> }): Result {
        if (pdf.length() > MAX_PDF_BYTES) throw GeminiException("El PDF es demasiado grande para analizarlo (máximo 50 MB).")
        val filePart = if (pdf.length() <= INLINE_MAX) {
            val data = Base64.encodeToString(pdf.readBytes(), Base64.NO_WRAP)
            JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", data))
        } else {
            uploadFile(apiKey, pdf, name, onProgress)
        }

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(filePart).put(JSONObject().put("text", prompt(name, mode))))
            }))
            if (mode == Mode.RESEARCH) put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", 0.3))
        }.toString()

        // Por cada modelo: si Google está saturado (500/503) se reintenta con espera creciente; si sigue fallando
        // (o no hay cupo) se pasa al siguiente modelo.
        val waits = longArrayOf(5_000, 15_000)
        var last: GeminiException? = null
        for ((m, model) in models(mode).withIndex()) {
            if (m > 0) onProgress("Probando con otro modelo…", null)
            var attempt = 0
            while (true) {
                try {
                    return generate(apiKey, model, body, mode, onProgress)
                } catch (e: GeminiException) {
                    if (e.retryable && attempt < waits.size) {
                        onProgress("Gemini está saturado; reintentando…", null)
                        Thread.sleep(waits[attempt++])
                        continue
                    }
                    if (!e.fallback) throw e
                    last = e
                    break
                }
            }
        }
        throw last ?: GeminiException("No se pudo analizar el documento.")
    }

    /** Sube el PDF con la API de archivos (subida reanudable) y espera a que Google lo procese. */
    private fun uploadFile(apiKey: String, pdf: File, name: String, onProgress: (String, Float?) -> Unit): JSONObject {
        onProgress("Subiendo el PDF…", 0f)
        val start = URL("$BASE/upload/v1beta/files").openConnection() as HttpURLConnection
        val uploadUrl: String
        try {
            start.requestMethod = "POST"
            start.connectTimeout = 20_000
            start.readTimeout = 60_000
            start.doOutput = true
            start.setRequestProperty("x-goog-api-key", apiKey)
            start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            start.setRequestProperty("X-Goog-Upload-Command", "start")
            start.setRequestProperty("X-Goog-Upload-Header-Content-Length", pdf.length().toString())
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", "application/pdf")
            start.setRequestProperty("Content-Type", "application/json")
            start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", name)).toString().toByteArray()) }
            val code = start.responseCode
            if (code !in 200..299) {
                val raw = start.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw GeminiException(errorMessage(code, raw), retryable = code == 500 || code == 503)
            }
            uploadUrl = start.getHeaderField("x-goog-upload-url") ?: throw GeminiException("Google no aceptó la subida del PDF. Inténtalo de nuevo.")
        } finally {
            start.disconnect()
        }

        val up = URL(uploadUrl).openConnection() as HttpURLConnection
        val file: JSONObject
        try {
            up.requestMethod = "POST"
            up.connectTimeout = 20_000
            up.readTimeout = 300_000
            up.doOutput = true
            up.setFixedLengthStreamingMode(pdf.length())
            up.setRequestProperty("X-Goog-Upload-Offset", "0")
            up.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            pdf.inputStream().use { input ->
                up.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress("Subiendo el PDF…", done.toFloat() / pdf.length())
                    }
                }
            }
            onProgress("Google está procesando el PDF…", null)
            val code = up.responseCode
            val raw = (if (code in 200..299) up.inputStream else up.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiException(errorMessage(code, raw), retryable = code == 500 || code == 503)
            file = JSONObject(raw).getJSONObject("file")
        } finally {
            up.disconnect()
        }

        // Los archivos grandes pueden tardar un poco en quedar listos.
        var state = file.optString("state")
        var tries = 0
        while (state == "PROCESSING" && tries++ < 60) {
            Thread.sleep(3_000)
            val c = URL("$BASE/v1beta/${file.getString("name")}").openConnection() as HttpURLConnection
            try {
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                if (c.responseCode in 200..299) state = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("state")
            } finally {
                c.disconnect()
            }
        }
        if (state == "FAILED") throw GeminiException("Google no pudo procesar el PDF. Prueba con otro archivo.")
        return JSONObject().put("fileData", JSONObject().put("mimeType", "application/pdf").put("fileUri", file.getString("uri")))
    }

    /** Pide el análisis en streaming: la respuesta llega por trozos, así la conexión no se corta por inactividad en documentos largos. */
    private fun generate(apiKey: String, model: String, body: String, mode: Mode, onProgress: (String, Float?) -> Unit): Result {
        onProgress(if (mode == Mode.RESEARCH) "Analizando y buscando en internet…" else "Analizando el documento…", null)
        val expected = if (mode == Mode.RESEARCH) 7000f else 5000f
        val conn = URL("$BASE/v1beta/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = if (mode == Mode.RESEARCH) 600_000 else 300_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw GeminiException(errorMessage(code, raw), retryable = code == 500 || code == 503, fallback = code in listOf(404, 429, 500, 503))
            }

            val text = StringBuilder()
            val sources = LinkedHashMap<String, String>()
            var blocked = ""
            conn.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val chunk = JSONObject(line.removePrefix("data:").trim())
                chunk.optJSONObject("error")?.let {
                    val c = it.optInt("code", 500)
                    throw GeminiException(errorMessage(c, chunk.toString()), retryable = c == 500 || c == 503, fallback = c in listOf(404, 429, 500, 503))
                }
                chunk.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { blocked = it }
                val cand = chunk.optJSONArray("candidates")?.optJSONObject(0) ?: return@forEachLine
                val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (!part.optBoolean("thought", false)) text.append(part.optString("text"))
                }
                if (text.isNotEmpty()) onProgress("Escribiendo el resumen…", (text.length / expected).coerceAtMost(0.95f))
                val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                if (chunks != null) for (i in 0 until chunks.length()) {
                    val web = chunks.getJSONObject(i).optJSONObject("web") ?: continue
                    val uri = web.optString("uri")
                    if (uri.isNotBlank()) sources.putIfAbsent(uri, web.optString("title").ifBlank { uri })
                }
            }

            val result = text.toString().trim()
            if (result.startsWith(UNREADABLE)) throw GeminiException("No se puede leer el documento: está vacío, borroso o demasiado tachado para analizarlo.")
            if (result.isEmpty()) {
                throw GeminiException(if (blocked.isNotEmpty()) "Gemini no devolvió respuesta ($blocked)." else "Gemini devolvió una respuesta vacía. Prueba de nuevo.", retryable = blocked.isEmpty())
            }
            return Result(result, sources.map { it.value to it.key })
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, raw: String): String {
        val detail = try {
            JSONObject(raw).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            ""
        }
        return when {
            code == 400 && detail.contains("API key", ignoreCase = true) -> "La clave de Gemini no es válida. Revísala en Ajustes."
            code == 401 || code == 403 -> "Gemini rechazó la clave. Revísala en Ajustes."
            code == 429 -> "Límite de Gemini alcanzado. Espera un rato (o hasta mañana) y vuelve a intentarlo." + if (detail.isNotBlank()) " Detalle de Google: ${detail.take(500)}" else ""
            code == 503 -> "Gemini sigue saturado tras varios intentos. Inténtalo de nuevo en unos minutos."
            code == 404 -> "El modelo de Gemini ya no está disponible. Hay que actualizar la app."
            else -> "Error de Gemini ($code)" + if (detail.isNotBlank()) ": $detail" else ""
        }
    }

    private const val UNREADABLE = "NO_LEGIBLE"

    /** Se añade a ambos prompts: si no se puede leer, Gemini responde solo con [UNREADABLE] y la app muestra un error. */
    private val READABILITY = """
        Legibilidad: si el documento está vacío, ilegible o tan tachado, borroso o dañado que no puedes leer lo suficiente para resumirlo con fiabilidad, responde ÚNICAMENTE con la palabra $UNREADABLE y nada más.
        Si solo son ilegibles partes sueltas, no las adivines: escribe «[ilegible]» en su lugar.
    """.trimIndent()

    private fun prompt(name: String, mode: Mode): String = when (mode) {
        Mode.SUMMARY -> """
            Eres un preparador de estudio. Analiza el documento adjunto («$name») y escribe un resumen en español.
            - Quédate solo con lo importante: conceptos clave, definiciones, cifras, fechas, normas y relaciones entre ideas.
            - Elimina relleno, repeticiones y explicaciones innecesarias.
            - Hazlo fácil de entender y de repasar: usa títulos cortos y listas.
            - No inventes nada que no esté en el documento.
            Formato: Markdown sencillo (títulos con #, listas con -, negritas con **).
            $READABILITY
        """.trimIndent()

        Mode.RESEARCH -> """
            Eres un preparador de estudio riguroso. Hoy es ${SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es", "ES")).format(Date())}.
            El documento adjunto («$name») es material de estudio. Haz esto:
            1. Lee el documento a fondo e identifica las leyes, normas, datos, cifras, organismos, tecnologías y procedimientos que menciona.
            2. Busca en internet (fuentes oficiales como BOE, boe.es y webs de organismos públicos cuando existan) si alguno ha cambiado, se ha derogado, modificado o actualizado desde que se escribió el documento.
            3. Escribe en español un resumen de estudio con lo más importante, ya CORREGIDO con la información vigente, sin relleno y lo más comprensible posible.
            Estructura de la respuesta (Markdown sencillo: títulos con #, listas con -, negritas con **):
            # Resumen
            (contenido esencial y actualizado, con títulos cortos y listas)
            # Cambios y actualizaciones detectados
            (qué dice el documento, qué es lo vigente hoy y desde cuándo; si no hay cambios relevantes, dilo claramente)
            # Dudas
            (cualquier punto que no hayas podido verificar)
            Sé honesto: si no encuentras confirmación de un cambio, no lo des por cierto.
            $READABILITY
        """.trimIndent()
    }
}
