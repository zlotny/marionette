package dev.marionette.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class SchemaBuilder {
    private val properties = linkedMapOf<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun property(name: String, required: Boolean, build: JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(build)
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        property(name, required) {
            put("type", "string")
            put("description", description)
            enum?.let { values -> putJsonArray("enum") { values.forEach { add(it) } } }
        }

    fun number(name: String, description: String, required: Boolean = false) = property(name, required) {
        put("type", "number")
        put("description", description)
    }

    fun integer(name: String, description: String, required: Boolean = false) = property(name, required) {
        put("type", "integer")
        put("description", description)
    }

    fun boolean(name: String, description: String, required: Boolean = false) = property(name, required) {
        put("type", "boolean")
        put("description", description)
    }

    fun stringArray(name: String, description: String, required: Boolean = false) = property(name, required) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(it) } }
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()
