package dev.marionette.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelStatsTest {
    @Test
    fun emaWeightsNewSamples() {
        val stats = StepStats.first(1000, 10_000, null).add(2000, 20_000, 400)
        assertEquals(1300.0, stats.millis, 1e-9)
        assertEquals(13_000.0, stats.inputTokens!!, 1e-9)
        assertEquals(400.0, stats.outputTokens!!, 1e-9)
        assertEquals(2, stats.count)
    }

    @Test
    fun recordsPerModelAndThinkingAndPersists() = runBlocking {
        val file = File(Files.createTempDirectory("stats").toFile(), "model-stats.json")
        val stats = ModelStats(file)
        stats.record("OPENROUTER", "anthropic/claude-haiku-5.5", "low", 2000, 9000, 200)
        stats.record("OPENROUTER", "anthropic/claude-haiku-5.5", "high", 6000, 9000, 900)
        stats.record("OPENCLAW", "", "", 3000, null, null)
        val reloaded = ModelStats(file)
        assertEquals(2000.0, reloaded.get("OPENROUTER", "anthropic/claude-haiku-5.5", "low")!!.millis, 1e-9)
        assertNull(reloaded.get("OPENROUTER", "anthropic/claude-haiku-5.5", "max"))
        val levels = ModelStats.forModel(reloaded.stats.value, "OPENROUTER", "anthropic/claude-haiku-5.5")
        assertEquals(setOf("low", "high"), levels.keys)
        val merged = ModelStats.merge(levels.values)!!
        assertEquals(4000.0, merged.millis, 1e-9)
        assertEquals(550.0, merged.outputTokens!!, 1e-9)
        assertEquals(1, ModelStats.forProvider(reloaded.stats.value, "OPENCLAW").size)
    }
}
