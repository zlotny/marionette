package dev.marionette.voice

object SpeechText {
    private val CODE = Regex("```[\\s\\S]*?```")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val URL = Regex("https?://(?:www\\.)?([^/\\s]+)\\S*")
    private val MARKERS = Regex("(?m)^\\s*(#{1,6}|[-*•]|\\d+[.)])\\s+")
    private val SYMBOLS = Regex("[*_`~>|#]+")
    private val EMOJI = Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D&&[^°©®™]]")
    private val SPACES = Regex("[ \\t]+")
    private val BREAKS = Regex("\\s*\\n+\\s*")

    fun clean(text: String): String = text
        .replace(CODE, " ")
        .replace(LINK, "$1")
        .replace(URL, "$1")
        .replace(MARKERS, "")
        .replace(SYMBOLS, "")
        .replace(EMOJI, "")
        .replace(SPACES, " ")
        .replace(BREAKS, ". ")
        .replace(Regex("([.!?:;,])\\. "), "$1 ")
        .trim()
}
