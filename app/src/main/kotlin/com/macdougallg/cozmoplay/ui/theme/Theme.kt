package com.macdougallg.cozmoplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Colour Palette (UI PRD section 2.2) ──────────────────────────────────────

val PrimaryBlue = Color(0xFF1A6FD4)
val CozmoOrange = Color(0xFFFF6B2B)
val CozmoYellow = Color(0xFFFFD600)
val AppBackground = Color(0xFFF0F4F8)
val SurfaceWhite = Color(0xFFFFFFFF)
val ErrorRed = Color(0xFFD32F2F)
val SuccessGreen = Color(0xFF388E3C)
val TextPrimary = Color(0xFF1A1A2E)
val TextSecondary = Color(0xFF4A5568)

private val CozmoColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = CozmoOrange,
    onSecondary = Color.White,
    tertiary = CozmoYellow,
    background = AppBackground,
    surface = SurfaceWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = ErrorRed,
    onError = Color.White,
)

/**
 * Root theme for CozmoPlay.
 * Applied once in MainActivity; all child composables inherit this theme.
 */
@Composable
fun CozmoPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CozmoColorScheme,
        typography = CozmoTypography,
        content = content
    )
}
