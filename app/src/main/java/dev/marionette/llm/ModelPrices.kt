package dev.marionette.llm

import dev.marionette.core.Http
import dev.marionette.core.Logger
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

data class ModelPrice(val inputPerToken: Double, val outputPerToken: Double, val cachedInputPerToken: Double?) {
    fun request(inputTokens: Int, outputTokens: Int): Double = inputPerToken * inputTokens + outputPerToken * outputTokens
}

class ModelPrices(private val cacheFile: File?) {
    private val lock = Mutex()
    private var prices: Map<String, ModelPrice> = emptyMap()
    private var loadedAt = 0L

    suspend fun load(): Map<String, ModelPrice> = lock.withLock {
        if (prices.isNotEmpty() && System.currentTimeMillis() - loadedAt < MAX_AGE_MILLIS) return prices
        withContext(Dispatchers.IO) {
            val cached = cacheFile?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < MAX_AGE_MILLIS }
            val body = cached?.readText() ?: runCatching { fetch().also { cacheFile?.writeText(it) } }
                .onFailure { Logger.w("Model prices unavailable", it) }
                .getOrNull() ?: cacheFile?.takeIf { it.exists() }?.readText()
            body?.let { prices = parse(it); loadedAt = System.currentTimeMillis() }
        }
        prices
    }

    private suspend fun fetch(): String {
        val request = Request.Builder().url(CATALOG_URL).get().build()
        return Http.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw response.toLlmException()
            response.body.string()
        }
    }

    companion object {
        private const val CATALOG_URL = "https://openrouter.ai/api/v1/models"
        private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        private val DATE_SUFFIX = Regex("-\\d{8}$|-\\d{4}-\\d{2}-\\d{2}$")

        fun parse(body: String): Map<String, ModelPrice> = parseObject(body)["data"].arr().orEmpty().mapNotNull { element ->
            val model = element.obj() ?: return@mapNotNull null
            val id = model.string("id") ?: return@mapNotNull null
            if (id.contains(':')) return@mapNotNull null
            val pricing = model["pricing"].obj() ?: return@mapNotNull null
            val input = pricing.string("prompt")?.toDoubleOrNull() ?: return@mapNotNull null
            val output = pricing.string("completion")?.toDoubleOrNull() ?: return@mapNotNull null
            if (input < 0 || output < 0) return@mapNotNull null
            key(id) to ModelPrice(input, output, pricing.string("input_cache_read")?.toDoubleOrNull())
        }.toMap()

        fun key(ref: String): String = ref.lowercase().removePrefix("openrouter/").removePrefix("~").replace(DATE_SUFFIX, "").replace('.', '-')

        fun find(prices: Map<String, ModelPrice>, ref: String, provider: Provider): ModelPrice? {
            if (ref.isBlank()) return null
            val direct = key(ref)
            prices[direct]?.let { return it }
            val prefix = when (provider) {
                Provider.ANTHROPIC -> "anthropic/"
                Provider.OPENAI -> "openai/"
                Provider.GEMINI -> "google/"
                else -> null
            }
            prefix?.let { p -> prices[key(p + ref)]?.let { return it } }
            val name = direct.substringAfterLast('/')
            return prices.entries.singleOrNull { it.key.substringAfterLast('/') == name }?.value
        }
    }
}
