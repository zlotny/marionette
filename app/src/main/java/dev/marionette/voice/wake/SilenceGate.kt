package dev.marionette.voice.wake

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class SilenceGate(private val hopSeconds: Float) {
    private var floorDb = Float.NaN

    fun isQuiet(pcm: FloatArray, count: Int, scale: Float = 32768f): Boolean {
        var sum = 0.0
        for (i in 0 until count) {
            val s = pcm[i] / scale
            sum += s * s
        }
        val rms = sqrt(sum / count).toFloat()
        val level = 20 * log10(rms + 1e-9f)
        val loud = rms >= MIN_RMS || (!floorDb.isNaN() && level >= max(floorDb, LOWEST_FLOOR_DB) + MARGIN_DB)
        floorDb = if (floorDb.isNaN() || level < floorDb) level else floorDb + FLOOR_RISE_DB * hopSeconds
        return !loud
    }

    private companion object {
        const val MIN_RMS = 0.0012f
        const val MARGIN_DB = 9f
        const val FLOOR_RISE_DB = 2f
        const val LOWEST_FLOOR_DB = -80f
    }
}
