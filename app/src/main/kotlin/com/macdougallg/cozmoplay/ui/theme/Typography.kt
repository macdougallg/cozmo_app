package com.macdougallg.cozmoplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CozmoTypography = Typography(
    displayMedium = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Bold,
        fontSize    = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 16.sp,
    ),
    bodySmall = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 12.sp,
    ),
    labelLarge = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Bold,
        fontSize    = 16.sp,
    ),
)
