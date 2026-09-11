package com.wh.island.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IslandColors = lightColorScheme(
    primary = Color(0xFFEC407A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EC),
    secondary = Color(0xFFFF8A65),
    tertiary = Color(0xFF7E57C2),
    background = Color(0xFFFFF8F9),
    surface = Color.White,
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IslandColors,
        content = content
    )
}
