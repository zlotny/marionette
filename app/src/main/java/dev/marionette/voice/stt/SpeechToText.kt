package dev.marionette.voice.stt

interface SttSession {
    fun accept(pcm: ShortArray, count: Int)

    suspend fun finish(): String

    fun cancel()

    fun speculate() = Unit

    fun dropSpeculation() = Unit
}

interface SpeechToText {
    val name: String

    fun isAvailable(): Boolean

    suspend fun start(language: String, extraLanguages: List<String>, onPartial: (String) -> Unit): SttSession
}

class SttException(message: String, val languageMissing: Boolean = false) : Exception(message)
