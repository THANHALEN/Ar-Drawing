package com.example.ardrawing.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary     = Color(0xFF90CAF9),
    background  = Color(0xFF000000),
    surface     = Color(0xFF1A1A1A),
    onPrimary   = Color(0xFF003258),
    onSurface   = Color(0xFFE0E0E0)
)

@Composable
fun ArDrawingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}
