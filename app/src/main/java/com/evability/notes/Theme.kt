package com.evability.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E0F13)
val Surface1 = Color(0xFF16181D)
val Surface2 = Color(0xFF1F2229)
val Accent = Color(0xFFF5A9C6) // rosa pastel
val Outline = Color(0xFF2C2F38)
val OnSurface = Color(0xFFE8E8EC)
val OnSurfaceDim = Color(0xFF9A9CA6)

@Composable
fun EvabilityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF3A1024),
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
