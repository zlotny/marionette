package dev.marionette.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

val MarionetteTypography = Base.copy(
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 26.sp),
)
