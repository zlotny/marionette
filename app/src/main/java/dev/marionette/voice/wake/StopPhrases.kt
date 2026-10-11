package dev.marionette.voice.wake

import android.content.Context
import java.io.File

data class StopPhrase(val id: String, val label: String, val recordings: Int)

class StopPhrases(context: Context) {
    private val dir = File(context.filesDir, "stop-phrases").apply { mkdirs() }

    fun list(): List<StopPhrase> = ids().map { id ->
        StopPhrase(id, labelFile(id).takeIf { it.exists() }?.readText().orEmpty(), load(id).size)
    }.filter { it.recordings > 0 }

    fun loadAll(): List<List<FloatArray>> = ids().flatMap(::load)

    fun save(id: String, label: String, templates: List<List<FloatArray>>, append: Boolean) {
        val merged = if (append) WakeTemplates.merge(load(id), templates) else templates
        AcousticWakeDetector.save(templatesFile(id), merged)
        labelFile(id).writeText(label)
    }

    fun delete(id: String) {
        templatesFile(id).delete()
        labelFile(id).delete()
    }

    private fun ids(): List<String> = dir.listFiles { f -> f.extension == "bin" }.orEmpty().map { it.nameWithoutExtension }.sorted()

    private fun load(id: String): List<List<FloatArray>> = runCatching { AcousticWakeDetector.load(templatesFile(id)) }.getOrDefault(emptyList())

    private fun templatesFile(id: String) = File(dir, "$id.bin")

    private fun labelFile(id: String) = File(dir, "$id.txt")
}
