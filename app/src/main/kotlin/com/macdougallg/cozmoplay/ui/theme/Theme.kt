package com.macdougallg.cozmoplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val CozmoColorScheme = lightColorScheme(
    primary          = PrimaryBlue,
    secondary        = CozmoOrange,
    tertiary         = CozmoYellow,
    background       = AppBackground,
    surface          = SurfaceWhite,
    error            = ErrorRed,
    onPrimary        = androidx.compose.ui.graphics.Color.White,
    onSecondary      = androidx.compose.ui.graphics.Color.White,
    onBackground     = TextPrimary,
    onSurface        = TextPrimary,
    onError          = androidx.compose.ui.graphics.Color.White,
)

@Composable
fun CozmoPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CozmoColorScheme,
        typography  = CozmoTypography,
        content     = content,
    )
}
