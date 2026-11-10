package com.evability.notes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

/** Ajustes de la app (color de los botones y color de fondo). */
object Prefs {
    val ACCENTS = listOf(
        "Rosa" to 0xFFF5A9C6,
        "Lila" to 0xFFCDB8F7,
        "Menta" to 0xFFA8E6CF,
        "Melocotón" to 0xFFFFC09A,
        "Celeste" to 0xFFA9D4F5,
        "Limón" to 0xFFF4E58A,
    )

    /** Fondos oscuros: con los colores pastel de arriba siempre se lee bien. */
    val BACKGROUNDS = listOf(
        "Grafito" to 0xFF0E0F13,
        "Azul noche" to 0xFF0B1220,
        "Océano" to 0xFF0A1A1F,
        "Bosque" to 0xFF0B1612,
        "Ciruela" to 0xFF170E1A,
        "Vino" to 0xFF1A0D12,
        "Café" to 0xFF17110D,
    )

    val LIGHT_BACKGROUNDS = listOf(
        "Hueso" to 0xFFF6F4F0,
        "Rosa pálido" to 0xFFFBEFF3,
        "Lavanda" to 0xFFF1EEFA,
        "Menta" to 0xFFEAF6F0,
        "Cielo" to 0xFFEAF2FB,
        "Crema" to 0xFFFAF3E4,
    )

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        val first = !::sp.isInitialized
        sp = context.getSharedPreferences("evability", Context.MODE_PRIVATE)
        // los servicios en segundo plano también llaman a init: solo la primera vez se leen los colores, para no apagar el fondo oscuro de un minijuego abierto
        if (first) {
            AccentBase = Color(sp.getLong("accent", ACCENTS[0].second))
            Bg = Color(sp.getLong("bg", BACKGROUNDS[0].second))
        }
    }

    fun setBackground(argb: Long) {
        Bg = Color(argb)
        sp.edit().putLong("bg", argb).apply()
    }

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    /** Minutos de estudio y de descanso del pomodoro. */
    var pomoStudy: Int
        get() = sp.getInt("pomo_study", 25)
        set(v) = sp.edit().putInt("pomo_study", v).apply()
    var pomoRest: Int
        get() = sp.getInt("pomo_rest", 5)
        set(v) = sp.edit().putInt("pomo_rest", v).apply()

    /** Los corazones que te mandan salen y vibran, pero sin la canción. */
    var heartMuted: Boolean
        get() = sp.getBoolean("heart_muted", false)
        set(v) = sp.edit().putBoolean("heart_muted", v).apply()

    /** Icono del Ternurín (el logo de arriba) sin sonido al tocarlo. */
    var logoMuted: Boolean
        get() = sp.getBoolean("logo_muted", false)
        set(v) = sp.edit().putBoolean("logo_muted", v).apply()

    /** Ternurín en silencio: el temporizador sigue, pero sin alarma ni vibración. */
    var pomoMuted: Boolean
        get() = sp.getBoolean("pomo_muted", false)
        set(v) = sp.edit().putBoolean("pomo_muted", v).apply()

    /** Sesión de la parte social (JSON), vacía si no hay sesión iniciada. */
    var socialSession: String
        get() = sp.getString("social_session", "") ?: ""
        set(v) = sp.edit().putString("social_session", v).apply()

    /** Música de fondo del Evapoly. */
    var polyMusic: Boolean
        get() = sp.getBoolean("poly_music", true)
        set(v) = sp.edit().putBoolean("poly_music", v).apply()

    /** Foco del Evapoly: resalta las calles propias. */
    var polyFocus: Boolean
        get() = sp.getBoolean("poly_focus", false)
        set(v) = sp.edit().putBoolean("poly_focus", v).apply()

    /** Fondo negro en los minijuegos (Wordle y Evadrez). */
    var gameDark: Boolean
        get() = sp.getBoolean("game_dark", false)
        set(v) = sp.edit().putBoolean("game_dark", v).apply()

    /** Si ya se avisó con una notificación de que hay mensajes sin leer en ese chat (solo se avisa del primero hasta que se lea). */
    fun chatNotified(pair: String): Boolean = sp.getBoolean("chat_notified_$pair", false)
    fun setChatNotified(pair: String, v: Boolean) = sp.edit().putBoolean("chat_notified_$pair", v).apply()

    /** Hasta qué mensaje (hora del servidor, en ms) has leído cada chat. */
    fun chatRead(pair: String): Long = sp.getLong("chat_read_$pair", 0L)
    fun setChatRead(pair: String, at: Long) = sp.edit().putLong("chat_read_$pair", at).apply()

    /** Wordle: partida del día («día:intento,intento») y a qué chats ya se mandó el resultado («día|uid,uid»). */
    var wordleState: String
        get() = sp.getString("wordle_state", "") ?: ""
        set(v) = sp.edit().putString("wordle_state", v).apply()
    var wordlePosted: String
        get() = sp.getString("wordle_posted", "") ?: ""
        set(v) = sp.edit().putString("wordle_posted", v).apply()

    /** Contador simple: hora de inicio (0 = parado) y hasta cuándo está guardado su tiempo en las estadísticas. */
    /** Método Pollo en pausa: desde cuándo (0 = sin pausa) y lo que marcaba el contador en ese momento. */
    var simplePausedAt: Long
        get() = sp.getLong("simple_paused_at", 0L)
        set(v) = sp.edit().putLong("simple_paused_at", v).apply()
    var simplePausedEl: Long
        get() = sp.getLong("simple_paused_el", 0L)
        set(v) = sp.edit().putLong("simple_paused_el", v).apply()

    /** Identifica este dispositivo en la nube para sumar sus tiempos de estudio a los de tus otros dispositivos. */
    val deviceId: String
        get() = sp.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(12).also { sp.edit().putString("device_id", it).apply() }

    var simpleStart: Long
        get() = sp.getLong("simple_start", 0L)
        set(v) = sp.edit().putLong("simple_start", v).apply()
    var simpleMark: Long
        get() = sp.getLong("simple_mark", 0L)
        set(v) = sp.edit().putLong("simple_mark", v).apply()

    /** «Avisos al momento» de documentos de amigos (servicio con notificación fija). Activado por defecto. */
    var quickAlerts: Boolean
        get() = sp.getBoolean("quick_alerts", true)
        set(v) = sp.edit().putBoolean("quick_alerts", v).apply()

    /** Nombre que se muestra para la carpeta «Amigos» (se puede cambiar, pero no borrar). */
    var friendsName: String
        get() = sp.getString("friends_name", "Amigos")?.ifBlank { "Amigos" } ?: "Amigos"
        set(v) = sp.edit().putString("friends_name", v.trim()).apply()

    fun setAccent(argb: Long) {
        AccentBase = Color(argb)
        sp.edit().putLong("accent", argb).apply()
    }
}
