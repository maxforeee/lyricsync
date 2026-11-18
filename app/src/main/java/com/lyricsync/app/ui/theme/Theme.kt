package com.lyricsync.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LyricSyncDarkColors = darkColorScheme(
    primary = CyanAccent,
    onPrimary = Color.Black,
    secondary = VioletAccent,
    onSecondary = Color.White,
    background = DeepIndigo,
    onBackground = Color.White,
    surface = DeepIndigo,
    onSurface = Color.White
)

@Composable
fun LyricSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LyricSyncDarkColors,
        content = content
    )
}
