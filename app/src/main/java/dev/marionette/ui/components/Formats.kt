package dev.marionette.ui.components

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat

fun formatUsd(value: Double): String {
    val rounded = if (value >= 1) BigDecimal(value).setScale(2, RoundingMode.HALF_UP) else BigDecimal(value).round(MathContext(2))
    return NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = if (value >= 0.01) 2 else 0
        maximumFractionDigits = maxOf(rounded.scale(), 2)
    }.format(rounded)
}

fun formatSeconds(seconds: Double): String = "%.1f".format(seconds)
