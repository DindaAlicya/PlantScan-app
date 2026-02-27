package com.example.plantscan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary        = Color(0xFF74070E),
    onPrimary      = Color(0xFFFFFFFF),

    background     = Color(0xFF0F261C),
    surface        = Color(0xFF133126),

    onBackground   = Color(0xFFE6F2EC),
    onSurface      = Color(0xFFE6F2EC),

    secondary      = Color(0xFF8B1A1A),
    onSecondary    = Color(0xFFFFFFFF)
)

@Composable
fun PlantScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content     = content
    )
}