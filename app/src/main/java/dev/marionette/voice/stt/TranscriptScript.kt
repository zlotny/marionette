package dev.marionette.voice.stt

import android.icu.lang.UScript
import android.icu.util.ULocale
import java.util.Locale

object TranscriptScript {
    fun foreign(text: String, languages: List<String>): Boolean {
        val allowed = languages.map { it.ifBlank { Locale.getDefault().toLanguageTag() } }.flatMap(::scripts).toSet()
        if (allowed.isEmpty()) return false
        return text.codePoints().anyMatch { cp ->
            Character.isLetter(cp) && UScript.getScript(cp).let { it != UScript.COMMON && it != UScript.INHERITED && it !in allowed }
        }
    }

    private fun scripts(language: String): List<Int> = when (val script = ULocale.addLikelySubtags(ULocale.forLanguageTag(language)).script) {
        "Jpan" -> listOf(UScript.HAN, UScript.HIRAGANA, UScript.KATAKANA)
        "Kore" -> listOf(UScript.HANGUL, UScript.HAN)
        "Hans", "Hant" -> listOf(UScript.HAN)
        else -> listOf(UScript.getCodeFromName(script))
    }
}
