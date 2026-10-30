package dev.marionette.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val gutter = l
    val readable = 720.dp
}

val MarionetteShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Immutable
data class ExtendedColors(val success: Color, val onSuccess: Color, val successContainer: Color)

private val LightExtended = ExtendedColors(Color(0xFF1E7A3C), Color.White, Color(0xFFB7F1C4))
private val DarkExtended = ExtendedColors(Color(0xFF7FDB98), Color(0xFF00391A), Color(0xFF005228))

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

fun extendedColors(dark: Boolean) = if (dark) DarkExtended else LightExtended

object Extended {
    val colors: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}
