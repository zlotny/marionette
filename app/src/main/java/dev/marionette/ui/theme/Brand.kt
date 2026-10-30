package dev.marionette.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object Brand {
    val Blue = Color(0xFF4C7DFF)
    val Violet = Color(0xFF8B5CF6)
    val Magenta = Color(0xFFE04BD8)
    val Coral = Color(0xFFFF7A6B)

    val glowColors = listOf(Blue, Violet, Magenta, Coral)

    @Composable
    @ReadOnlyComposable
    fun textBrush(): Brush = Brush.linearGradient(listOf(Blue, Violet, Magenta))
}
