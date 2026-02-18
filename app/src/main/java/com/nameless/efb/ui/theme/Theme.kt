package com.nameless.efb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EfbDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00C8FF),
    secondary = Color(0xFF00FF00),
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun EfbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EfbDarkColorScheme,
        content = content,
    )
}
