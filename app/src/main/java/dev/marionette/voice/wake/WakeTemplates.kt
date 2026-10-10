package dev.marionette.voice.wake

import android.content.Context
import java.io.File

class WakeTemplates(context: Context) {
    val file = File(context.filesDir, "wake-templates.bin")
    val samplesDir = File(context.filesDir, "wake-samples").apply { mkdirs() }

    fun load(): List<List<FloatArray>> = runCatching { AcousticWakeDetector.load(file) }.getOrDefault(emptyList())

    fun save(templates: List<List<FloatArray>>) = AcousticWakeDetector.save(file, templates)

    fun count(): Int = load().size

    companion object {
        const val MAX_TEMPLATES = 30

        fun <T> merge(existing: List<T>, added: List<T>): List<T> = (existing + added).takeLast(MAX_TEMPLATES)
    }

    fun clear() {
        file.delete()
        samplesDir.listFiles()?.forEach { it.delete() }
    }
}
