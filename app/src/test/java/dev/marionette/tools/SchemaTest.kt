package dev.marionette.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SchemaTest {
    @Test
    fun omitsEmptyRequired() {
        assertFalse(schema { string("a", "A.") }.containsKey("required"))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"))), schema { string("a", "A.", required = true) }["required"])
    }

    @Test
    fun stringArrayItemsCarryTheEnum() {
        val days = (schema { stringArray("days", "Days.", enum = listOf("mon", "tue")) }["properties"] as JsonObject)["days"] as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("mon"), JsonPrimitive("tue"))), (days["items"] as JsonObject)["enum"])
    }

    @Test
    fun objectPropertyIsTyped() {
        val args = (schema { obj("arguments", "Args.") }["properties"] as JsonObject)["arguments"] as JsonObject
        assertEquals(JsonPrimitive("object"), args["type"])
    }

    @Test
    fun resultsDropNullFields() {
        val ok = ToolResults.ok("a" to 1, "b" to null)
        assertNull(ok["b"])
        assertFalse(ToolResults.error("x").containsKey("message"))
    }

    @Test
    fun readsAliasesAndLongIds() {
        val args = JsonObject(mapOf("from" to JsonPrimitive("2026-01-01"), "id" to JsonPrimitive(9_000_000_000L)))
        assertEquals("2026-01-01", args.firstString("since", "from"))
        assertEquals(9_000_000_000L, args.long("id"))
    }
}
