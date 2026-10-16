package dev.marionette.voice.wake

import dev.marionette.voice.AudioSpec
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object NoiseProbe {
    private const val SECONDS = 12
    private const val FLOOR_DBFS = -74f
    private const val CLICK_SAMPLES = 96
    private const val CLICK_DECAY_SAMPLES = 24f

    @Volatile
    private var cached: Pair<SpeechEmbedder, List<FloatArray>>? = null

    fun embeddings(embedder: SpeechEmbedder): List<FloatArray> {
        cached?.takeIf { it.first === embedder }?.let { return it.second }
        return embedder.embeddings(samples(AudioSpec.SAMPLE_RATE)).also { cached = embedder to it }
    }

    fun samples(rate: Int): FloatArray {
        val random = Random(1)
        val n = SECONDS * rate
        val x = lowPass(FloatArray(n) { random.nextGaussian().toFloat() }, 0.9f)
        val floorRms = sqrt(x.fold(0.0) { s, v -> s + v * v } / n).toFloat()
        val floorGain = amplitude(FLOOR_DBFS) / floorRms
        for (i in x.indices) x[i] *= floorGain
        fun uniform(a: Float, b: Float) = a + random.nextFloat() * (b - a)
        fun add(seconds: Float, burst: FloatArray, peakDbfs: Float) {
            val start = (seconds * rate).toInt()
            val peak = burst.maxOf { abs(it) }
            val gain = amplitude(peakDbfs) / peak
            for (i in burst.indices) if (start + i < n) x[start + i] += burst[i] * gain
        }
        fun click() = FloatArray(CLICK_SAMPLES) { random.nextGaussian().toFloat() * exp(-it / CLICK_DECAY_SAMPLES) }
        val third = SECONDS / 3f
        var t = 0.3f
        while (t < third) {
            add(t, click(), uniform(-50f, -20f))
            t += uniform(0.25f, 0.5f)
        }
        while (t < 2 * third) {
            add(t, click(), uniform(-50f, -20f))
            t += uniform(0.08f, 0.2f)
        }
        while (t < SECONDS - 0.8f) {
            val k = (uniform(0.4f, 0.8f) * rate).toInt()
            val breath = lowPass(FloatArray(k) { random.nextGaussian().toFloat() }, 0.7f)
            for (i in breath.indices) breath[i] *= (0.5f - 0.5f * cos(2 * PI * i / (k - 1)).toFloat())
            add(t, breath, uniform(-50f, -30f))
            t += k.toFloat() / rate + uniform(0.2f, 0.5f)
        }
        return FloatArray(n) { (x[it] * 32768f).roundToInt().coerceIn(-32768, 32767).toFloat() }
    }

    private fun amplitude(dbfs: Float) = 10f.pow(dbfs / 20f)

    private fun lowPass(x: FloatArray, a: Float): FloatArray {
        var acc = 0f
        for (i in x.indices) {
            acc = a * acc + (1 - a) * x[i]
            x[i] = acc
        }
        return x
    }
}
