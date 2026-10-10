package dev.marionette.agent

import dev.marionette.core.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class StepStats(
    val millis: Double,
    val inputTokens: Double? = null,
    val outputTokens: Double? = null,
    val count: Int = 1,
) {
    fun add(millis: Long, inputTokens: Int?, outputTokens: Int?): StepStats = StepStats(
        millis = ema(this.millis, millis.toDouble()),
        inputTokens = inputTokens?.let { new -> this.inputTokens?.let { ema(it, new.toDouble()) } ?: new.toDouble() } ?: this.inputTokens,
        outputTokens = outputTokens?.let { new -> this.outputTokens?.let { ema(it, new.toDouble()) } ?: new.toDouble() } ?: this.outputTokens,
        count = count + 1,
    )

    companion object {
        const val WEIGHT = 0.3

        fun first(millis: Long, inputTokens: Int?, outputTokens: Int?) = StepStats(millis.toDouble(), inputTokens?.toDouble(), outputTokens?.toDouble())

        private fun ema(old: Double, new: Double) = old + WEIGHT * (new - old)
    }
}

class ModelStats(private val file: File?) {
    private val serializer = MapSerializer(String.serializer(), StepStats.serializer())
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<Map<String, StepStats>> = _stats.asStateFlow()

    suspend fun record(provider: String, model: String, thinking: String, millis: Long, inputTokens: Int?, outputTokens: Int?) {
        val key = key(provider, model, thinking)
        val next = _stats.updateAndGet { current ->
            current + (key to (current[key]?.add(millis, inputTokens, outputTokens) ?: StepStats.first(millis, inputTokens, outputTokens)))
        }
        file ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(AppJson.encodeToString(serializer, next))
                temp.renameTo(file)
            }
        }
    }

    fun get(provider: String, model: String, thinking: String): StepStats? = _stats.value[key(provider, model, thinking)]

    private fun load(): Map<String, StepStats> = file?.takeIf { it.exists() }
        ?.let { runCatching { AppJson.decodeFromString(serializer, it.readText()) }.getOrNull() }
        .orEmpty()

    companion object {
        fun key(provider: String, model: String, thinking: String) = "$provider|$model|$thinking"

        fun forModel(stats: Map<String, StepStats>, provider: String, model: String): Map<String, StepStats> {
            val prefix = "$provider|$model|"
            return stats.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        }

        fun forProvider(stats: Map<String, StepStats>, provider: String): List<StepStats> =
            stats.filterKeys { it.startsWith("$provider|") }.values.toList()

        fun merge(items: Collection<StepStats>): StepStats? {
            if (items.isEmpty()) return null
            val total = items.sumOf { it.count }.toDouble()
            fun weighted(value: (StepStats) -> Double?): Double? {
                val known = items.mapNotNull { s -> value(s)?.let { it to s.count } }
                if (known.isEmpty()) return null
                return known.sumOf { (v, c) -> v * c } / known.sumOf { it.second }
            }
            return StepStats(items.sumOf { it.millis * it.count } / total, weighted { it.inputTokens }, weighted { it.outputTokens }, total.toInt())
        }
    }
}
