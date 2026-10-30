package dev.marionette.voice.wake

import android.content.Context
import dev.marionette.voice.AudioSpec
import java.io.File

class WakeTemplates(context: Context) {
    val file = File(context.filesDir, "wake-templates.bin")
    val samplesDir = File(context.filesDir, "wake-samples").apply { mkdirs() }

    fun load(): List<List<FloatArray>> = runCatching { AcousticWakeDetector.load(file) }.getOrDefault(emptyList())

    fun save(templates: List<List<FloatArray>>) = AcousticWakeDetector.save(file, templates)

    fun count(): Int = load().size

    fun minPhraseDbfs(): Float {
        val peaks = samplesDir.listFiles { f -> f.name.startsWith("enroll-") && f.name.endsWith(".wav") && f.length() > WAV_HEADER }.orEmpty().flatMap { file ->
            val bytes = file.readBytes()
            val samples = FloatArray((bytes.size - WAV_HEADER) / 2) { i ->
                ((bytes[WAV_HEADER + 2 * i].toInt() and 0xFF) or (bytes[WAV_HEADER + 2 * i + 1].toInt() shl 8)).toShort().toFloat()
            }
            val levels = EnergySegmenter.segments(samples, AudioSpec.SAMPLE_RATE).map { (start, end) ->
                val hops = (start * AudioSpec.SAMPLE_RATE).toInt() until (end * AudioSpec.SAMPLE_RATE).toInt() - HOP step HOP
                hops.maxOfOrNull { AcousticWakeDetector.levelDbfs(samples, HOP, it) } ?: Float.NEGATIVE_INFINITY
            }
            val loudest = levels.maxOrNull() ?: return@flatMap emptyList()
            levels.filter { it >= loudest - JUNK_DB }
        }
        return peaks.minOrNull()?.minus(MARGIN_DB) ?: Float.NEGATIVE_INFINITY
    }

    fun clearSamples() {
        samplesDir.listFiles { f -> f.name.startsWith("enroll-") }?.forEach { it.delete() }
    }

    companion object {
        const val MAX_TEMPLATES = 30
        private const val WAV_HEADER = 44
        private const val HOP = 1280
        private const val JUNK_DB = 12f
        private const val MARGIN_DB = 6f

        fun <T> merge(existing: List<T>, added: List<T>): List<T> = (existing + added).takeLast(MAX_TEMPLATES)
    }

    fun clear() {
        file.delete()
        samplesDir.listFiles()?.forEach { it.delete() }
    }
}
