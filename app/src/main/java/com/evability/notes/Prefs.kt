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
        sp = context.getSharedPreferences("evability", Context.MODE_PRIVATE)
        AccentBase = Color(sp.getLong("accent", ACCENTS[0].second))
        Bg = Color(sp.getLong("bg", BACKGROUNDS[0].second))
    }

    fun setBackground(argb: Long) {
        Bg = Color(argb)
        sp.edit().putLong("bg", argb).apply()
    }

    fun setAccent(argb: Long) {
        AccentBase = Color(argb)
        sp.edit().putLong("accent", argb).apply()
    }
}
