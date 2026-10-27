package dev.marionette.voice

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object ReplyLanguage {
    suspend fun detect(context: Context, text: String, languages: List<String>): String {
        if (languages.size < 2) return languages.firstOrNull().orEmpty()
        val scores = withContext(Dispatchers.Default) {
            runCatching {
                val language = context.getSystemService(TextClassificationManager::class.java).textClassifier
                    .detectLanguage(TextLanguage.Request.Builder(text).build())
                (0 until language.localeHypothesisCount).associate { i ->
                    language.getLocale(i).language to language.getConfidenceScore(language.getLocale(i))
                }
            }.getOrDefault(emptyMap())
        }
        return pick(scores, languages)
    }

    fun pick(scores: Map<String, Float>, languages: List<String>): String =
        languages.maxByOrNull { scores[code(it)] ?: 0f }?.takeIf { (scores[code(it)] ?: 0f) > 0f } ?: languages.first()

    private fun code(tag: String): String = (tag.ifBlank { Locale.getDefault().toLanguageTag() }).let { Locale.forLanguageTag(it).language }
}
