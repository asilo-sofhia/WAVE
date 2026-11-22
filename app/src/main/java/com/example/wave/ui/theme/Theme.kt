package com.example.wave.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light only for this phase, and no dynamic color: the brand palette must win over wallpaper colors.
private val WaveColorScheme = lightColorScheme(
    primary = Blue700,
    onPrimary = Color.White,
    primaryContainer = BlueTint,
    onPrimaryContainer = Navy900,
    secondary = Blue700,
    onSecondary = Color.White,
    secondaryContainer = BlueTint,
    onSecondaryContainer = Navy900,
    background = SkyTop,
    onBackground = Navy900,
    surface = Color.White,
    onSurface = Navy900,
    surfaceVariant = NeutralBg,
    onSurfaceVariant = Slate600,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    outline = Slate600,
    outlineVariant = Divider,
    error = Danger,
)

@Composable
fun WAVETheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WaveColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content,
    )
}
