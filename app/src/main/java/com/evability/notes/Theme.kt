package com.evability.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

val DefaultBg = Color(0xFF0E0F13)

/** Fondo y color de acento: se pueden cambiar desde Ajustes (ver [Prefs]), por eso son estado observable. */
var Bg by mutableStateOf(DefaultBg)
var Accent by mutableStateOf(Color(0xFFF5A9C6)) // rosa pastel por defecto

val Surface1 = Color(0xFF16181D)
val Surface2 = Color(0xFF1F2229)
val Outline = Color(0xFF2C2F38)
val OnSurface = Color(0xFFE8E8EC)
val OnSurfaceDim = Color(0xFF9A9CA6)

@Composable
fun EvabilityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF2A1420),
            secondary = Accent,
            background = Bg,
            onBackground = OnSurface,
            surface = Surface1,
            onSurface = OnSurface,
            surfaceVariant = Surface2,
            onSurfaceVariant = OnSurfaceDim,
            outline = Outline,
        ),
    ) {
        Surface(color = Bg, contentColor = OnSurface, content = content)
    }
}
