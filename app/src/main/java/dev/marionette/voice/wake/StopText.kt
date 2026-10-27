package dev.marionette.voice.wake

import java.text.Normalizer

object StopText {
    private const val MAX_WORDS = 6

    fun matches(transcript: String, labels: List<String>, ignored: List<String>): Boolean {
        val skip = ignored.flatMap(::words).toSet()
        val heard = collapse(words(transcript).filter { it !in skip })
        if (heard.isEmpty() || words(transcript).size > MAX_WORDS) return false
        return labels.any { label -> collapse(words(label).filter { it !in skip }).let { it.isNotEmpty() && it == heard } }
    }

    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }

    private fun collapse(words: List<String>): List<String> = words.filterIndexed { i, w -> i == 0 || words[i - 1] != w }
}
