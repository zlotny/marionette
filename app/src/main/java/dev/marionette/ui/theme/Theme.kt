package dev.marionette.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightFallback = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF0B1365),
    secondary = Color(0xFF5C5D72),
    tertiary = Color(0xFF8E4585),
    tertiaryContainer = Color(0xFFFFD7F3),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF1E2A92),
    primaryContainer = Color(0xFF3641BB),
    onPrimaryContainer = Color(0xFFDFE0FF),
    secondary = Color(0xFFC5C4DD),
    tertiary = Color(0xFFFFADE9),
    tertiaryContainer = Color(0xFF72296B),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

@Composable
fun colorScheme(dark: Boolean = isSystemInDarkTheme()): ColorScheme {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MarionetteTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme(dark),
        motionScheme = MotionScheme.expressive(),
        typography = MarionetteTypography,
        content = content,
    )
}
