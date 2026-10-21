package com.evability.notes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

/** Ajustes de la app (color de acento). */
object Prefs {
    val ACCENTS = listOf(
        "Rosa" to 0xFFF5A9C6,
        "Lila" to 0xFFCDB8F7,
        "Menta" to 0xFFA8E6CF,
        "Melocotón" to 0xFFFFC09A,
        "Celeste" to 0xFFA9D4F5,
        "Limón" to 0xFFF4E58A,
    )

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("evability", Context.MODE_PRIVATE)
        Accent = Color(sp.getLong("accent", ACCENTS[0].second))
    }

    fun setAccent(argb: Long) {
        Accent = Color(argb)
        sp.edit().putLong("accent", argb).apply()
    }
}
