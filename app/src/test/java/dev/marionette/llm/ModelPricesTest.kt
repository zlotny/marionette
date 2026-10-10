package dev.marionette.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPricesTest {
    private val body = """
        {"data": [
          {"id": "anthropic/claude-haiku-5.5", "pricing": {"prompt": "0.000001", "completion": "0.000005", "input_cache_read": "0.0000001"}},
          {"id": "openai/gpt-5.5-mini", "pricing": {"prompt": "0.0000004", "completion": "0.0000016"}},
          {"id": "openai/gpt-5.5-mini:free", "pricing": {"prompt": "0", "completion": "0"}},
          {"id": "openrouter/auto", "pricing": {"prompt": "-1", "completion": "-1"}},
          {"id": "broken"}
        ]}
    """.trimIndent()

    @Test
    fun parseKeepsPricedModelsOnly() {
        val prices = ModelPrices.parse(body)
        assertEquals(setOf("anthropic/claude-haiku-5-5", "openai/gpt-5-5-mini"), prices.keys)
        assertEquals(0.0000001, prices.getValue("anthropic/claude-haiku-5-5").cachedInputPerToken!!, 1e-12)
    }

    @Test
    fun findMatchesOpenClawAndDirectRefs() {
        val prices = ModelPrices.parse(body)
        val haiku = prices.getValue("anthropic/claude-haiku-5-5")
        assertEquals(haiku, ModelPrices.find(prices, "openrouter/anthropic/claude-haiku-5-5", Provider.OPENCLAW))
        assertEquals(haiku, ModelPrices.find(prices, "anthropic/claude-haiku-5.5", Provider.OPENROUTER))
        assertEquals(haiku, ModelPrices.find(prices, "claude-haiku-5-5", Provider.ANTHROPIC))
        assertEquals(haiku, ModelPrices.find(prices, "claude-haiku-5-5-20260301", Provider.ANTHROPIC))
        assertEquals(prices.getValue("openai/gpt-5-5-mini"), ModelPrices.find(prices, "gpt-5.5-mini", Provider.CUSTOM))
        assertNull(ModelPrices.find(prices, "", Provider.OPENCLAW))
        assertNull(ModelPrices.find(prices, "unknown/model", Provider.OPENROUTER))
    }

    @Test
    fun requestCost() {
        assertEquals(0.0135, ModelPrice(0.000001, 0.000005, null).request(12_000, 300), 1e-9)
    }
}
