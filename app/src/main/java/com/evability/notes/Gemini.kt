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
    /** Alias que Google mantiene apuntando al modelo "flash" vigente (rápido y con nivel gratuito). */
    const val MODEL = "gemini-flash-latest"
    const val KEY_URL = "https://aistudio.google.com/apikey"

    /** Límite de la API para peticiones con el archivo dentro (20 MB) menos el margen del base64. */
    private const val MAX_PDF_BYTES = 14 * 1024 * 1024

    class GeminiException(message: String) : Exception(message)

    enum class Mode { SUMMARY, RESEARCH }

    class Result(val text: String, val sources: List<Pair<String, String>>)

    fun analyze(apiKey: String, pdf: File, name: String, mode: Mode): Result {
        if (pdf.length() > MAX_PDF_BYTES) throw GeminiException("El PDF es demasiado grande para analizarlo (máximo 14 MB).")
        val data = Base64.encodeToString(pdf.readBytes(), Base64.NO_WRAP)

        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray()
                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", data)))
                    .put(JSONObject().put("text", prompt(name, mode))))
            }))
            if (mode == Mode.RESEARCH) put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            put("generationConfig", JSONObject().put("temperature", 0.3))
        }

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = if (mode == Mode.RESEARCH) 600_000 else 180_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw GeminiException(errorMessage(code, raw))
            return parse(raw)
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
            code == 503 -> "Gemini está saturado en este momento. Inténtalo de nuevo en unos minutos."
            else -> "Error de Gemini ($code)" + if (detail.isNotBlank()) ": $detail" else ""
        }
    }

    private fun parse(raw: String): Result {
        val json = JSONObject(raw)
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GeminiException("Gemini no devolvió respuesta" + (json.optJSONObject("promptFeedback")?.optString("blockReason")?.let { " ($it)" } ?: ""))
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }.trim()
        if (text.startsWith(UNREADABLE)) throw GeminiException("No se puede leer el documento: está vacío, borroso o demasiado tachado para analizarlo.")
        if (text.isEmpty()) throw GeminiException("Gemini devolvió una respuesta vacía. Prueba de nuevo.")

        val sources = LinkedHashMap<String, String>()
        val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val web = chunks.getJSONObject(i).optJSONObject("web") ?: continue
            val uri = web.optString("uri")
            if (uri.isNotBlank()) sources.putIfAbsent(uri, web.optString("title").ifBlank { uri })
        }
        return Result(text, sources.map { it.value to it.key })
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
