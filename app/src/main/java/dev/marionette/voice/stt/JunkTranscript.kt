package dev.marionette.voice.stt

import dev.marionette.voice.wake.StopText

object JunkTranscript {
    private const val MIN_LETTERS = 3
    private val FILLERS = setOf(
        "a", "e", "o", "y", "u", "ah", "eh", "oh", "uh", "um", "mm", "mmm", "hm", "hmm", "ok", "okay", "vale", "bueno",
        "pues", "vamos", "venga", "nada", "si", "no", "ya", "ei", "hey", "bah", "buf", "uf", "ay", "jaja", "jeje", "haha",
    )

    fun isJunk(text: String, languages: List<String>): Boolean {
        val words = StopText.words(text)
        if (words.isEmpty()) return true
        if (words.sumOf { it.length } < MIN_LETTERS) return true
        if (words.all { it in FILLERS }) return true
        return TranscriptScript.foreign(text, languages)
    }
}
