package com.evability.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

val DefaultBg = Color(0xFF0E0F13)

/** Fondo y color de los botones: se cambian desde Ajustes (ver [Prefs]), por eso son estado observable. */
var Bg by mutableStateOf(DefaultBg)

/** Color de botones tal como lo elige el usuario. Para dibujar usa [Accent]. */
var AccentBase by mutableStateOf(Color(0xFFF5A9C6)) // rosa pastel por defecto

val IsLight: Boolean get() = Bg.luminance() > 0.5f

/** Sobre fondos claros el color elegido se oscurece/intensifica un poco para que textos e iconos se lean. */
val Accent: Color get() = if (IsLight) deepen(AccentBase) else AccentBase

/** Color del texto/iconos que van encima de [Accent]. */
val OnAccent: Color get() = if (Accent.luminance() > 0.38f) Color(0xFF2A1420) else Color.White

/** Tarjetas, paneles y bordes salen del propio fondo, así siempre combinan con él. */
val Surface1: Color get() = if (IsLight) lerp(Bg, Color.White, 0.65f) else lerp(Bg, Color.White, 0.035f)
val Surface2: Color get() = if (IsLight) lerp(Bg, Color.Black, 0.05f) else lerp(Bg, Color.White, 0.07f)
val Outline: Color get() = if (IsLight) lerp(Bg, Color.Black, 0.13f) else lerp(Bg, Color.White, 0.14f)
val OnSurface: Color get() = if (IsLight) Color(0xFF1C1B22) else Color(0xFFE8E8EC)
val OnSurfaceDim: Color get() = if (IsLight) Color(0xFF6A6872) else Color(0xFF9A9CA6)

private fun deepen(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.9f + 0.05f).coerceAtMost(0.85f)
    hsv[2] = 0.74f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

@Composable
fun EvabilityTheme(content: @Composable () -> Unit) {
    val scheme = if (IsLight) {
        lightColorScheme(
            primary = Accent, onPrimary = OnAccent, secondary = Accent,
            background = Bg, onBackground = OnSurface,
            surface = Surface1, onSurface = OnSurface,
            surfaceVariant = Surface2, onSurfaceVariant = OnSurfaceDim, outline = Outline,
            surfaceContainerLow = Surface1, surfaceContainer = Surface1,
            surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
        )
    } else {
        darkColorScheme(
            primary = Accent, onPrimary = OnAccent, secondary = Accent,
            background = Bg, onBackground = OnSurface,
            surface = Surface1, onSurface = OnSurface,
            surfaceVariant = Surface2, onSurfaceVariant = OnSurfaceDim, outline = Outline,
            surfaceContainerLow = Surface1, surfaceContainer = Surface1,
            surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(color = Bg, contentColor = OnSurface, content = content)
    }
}
