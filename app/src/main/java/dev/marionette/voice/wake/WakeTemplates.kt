package dev.marionette.voice.wake

import android.content.Context
import java.io.File

class WakeTemplates(context: Context) {
    val file = File(context.filesDir, "wake-templates.bin")
    val samplesDir = File(context.filesDir, "wake-samples").apply { mkdirs() }

    fun load(): List<List<FloatArray>> = runCatching { AcousticWakeDetector.load(file) }.getOrDefault(emptyList())

    fun save(templates: List<List<FloatArray>>) = AcousticWakeDetector.save(file, templates)

    fun count(): Int = load().size

    fun clear() {
        file.delete()
        samplesDir.listFiles()?.forEach { it.delete() }
    }
}
