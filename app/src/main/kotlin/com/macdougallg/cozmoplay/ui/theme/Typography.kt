package com.macdougallg.cozmoplay.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Nunito is bundled in res/font/. Falls back to system sans-serif if unavailable.
// TODO Agent 3: Add Nunito font files to app/src/main/res/font/ and replace FontFamily.SansSerif
val NunitoFamily = FontFamily.SansSerif

/**
 * CozmoPlay type scale (UI PRD section 2.3).
 */
val CozmoTypography = Typography(
    // Display — screen headers visible to child (28sp Bold)
    displayMedium = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = TextPrimary,
    ),
    // Body — button labels, status text (16sp SemiBold)
    bodyLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = TextPrimary,
    ),
    // Caption — secondary info (12sp Regular)
    bodySmall = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = TextSecondary,
    ),
    // Labels on buttons
    labelLarge = TextStyle(
        fontFamily = NunitoFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
    ),
)
