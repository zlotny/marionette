package dev.marionette.voice.stt

import dev.marionette.voice.wake.StopText

object JunkTranscript {
    private const val MIN_LETTERS = 3
    private val FILLERS = setOf(
        "a", "e", "o", "y", "u", "ah", "eh", "oh", "uh", "um", "mm", "mmm", "hm", "hmm", "ok", "okay", "vale", "bueno",
        "pues", "vamos", "venga", "nada", "si", "no", "ya", "ei", "hey", "bah", "buf", "uf", "ay", "jaja", "jeje", "haha",
    )

    private val HALLUCINATIONS = listOf(
        "amara org", "subtitulos realizados por", "subtitulos por", "subtitulado por", "gracias por ver", "thanks for watching",
        "thank you for watching", "suscribete", "please subscribe", "like and subscribe",
    )
    private val ALONE = setOf("gracias", "thank you", "thanks", "you")

    fun isHallucination(text: String): Boolean {
        val joined = StopText.words(text).joinToString(" ")
        if (joined.isEmpty()) return false
        return joined in ALONE || HALLUCINATIONS.any { it in joined }
    }

    fun isJunk(text: String, languages: List<String>): Boolean {
        val words = StopText.words(text)
        if (words.isEmpty()) return true
        if (words.sumOf { it.length } < MIN_LETTERS) return true
        if (words.all { it in FILLERS }) return true
        if (isHallucination(text)) return true
        return TranscriptScript.foreign(text, languages)
    }
}
