package dev.marionette.spike.audio

object FuzzyMatcher {

    data class Match(val variant: String, val distance: Int)

    fun letters(text: String): String = SentencePieceTokenizer.normalize(text).filter { it in 'A'..'Z' }

    fun find(text: String, variants: Collection<String>, maxRatio: Float): Match? {
        val haystack = letters(text)
        if (haystack.isEmpty()) return null
        return variants
            .map(::letters)
            .filter { it.isNotEmpty() }
            .map { Match(it, substringDistance(it, haystack)) }
            .filter { it.distance <= (it.variant.length * maxRatio).toInt() }
            .minByOrNull { it.distance.toFloat() / it.variant.length }
    }

    fun substringDistance(pattern: String, text: String): Int {
        var previous = IntArray(text.length + 1)
        var current = IntArray(text.length + 1)
        for (i in 1..pattern.length) {
            current[0] = i
            for (j in 1..text.length) {
                val cost = if (pattern[i - 1] == text[j - 1]) 0 else 1
                current[j] = minOf(previous[j - 1] + cost, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current.also { current = previous }
        }
        return previous.min()
    }
}
