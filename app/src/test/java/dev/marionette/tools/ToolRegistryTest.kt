package dev.marionette.tools

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRegistryTest {
    private class FakeTool(override val name: String) : Tool {
        override val description = "fake $name"
        override val parameters = JsonObject(emptyMap())
        override val category = ToolCategory.DEVICE
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResults.ok()
    }

    private val registry = ToolRegistry(listOf(FakeTool("a"), FakeTool("b"), FakeTool("c")))

    @Test
    fun offersAllToolsByDefault() {
        assertEquals(listOf("a", "b", "c"), registry.specs().map { it.name })
    }

    @Test
    fun hidesDisabledTools() {
        assertEquals(listOf("a", "c"), registry.specs(setOf("b", "unknown")).map { it.name })
    }

    @Test
    fun looksUpByName() {
        assertEquals("b", registry["b"]?.name)
        assertNull(registry["z"])
    }

    @Test
    fun everyRealToolHasAUniqueName() {
        val names = Tools.all().map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
