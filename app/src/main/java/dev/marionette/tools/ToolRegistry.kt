package dev.marionette.tools

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.marionette.core.Logger
import dev.marionette.core.parseObject
import dev.marionette.llm.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun specs(disabled: Set<String> = emptySet()): List<ToolSpec> = tools.filter { it.name !in disabled }.map { it.spec() }

    operator fun get(name: String): Tool? = byName[name]

    suspend fun execute(name: String, arguments: String, ctx: ToolContext): JsonObject {
        val tool = byName[name] ?: return ToolResults.error("unknown_tool", "No tool named $name.")
        val missing = tool.permissions.firstOrNull {
            ContextCompat.checkSelfPermission(ctx.context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing != null) return ToolResults.missingPermission(missing)
        return try {
            withTimeout(TOOL_TIMEOUT_MILLIS) { tool.execute(parseObject(arguments), ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResults.error("timeout", "$name took too long.")
        } catch (e: SecurityException) {
            Logger.w("Tool $name security error", e)
            ToolResults.error("not_allowed", e.message)
        } catch (e: Exception) {
            Logger.w("Tool $name failed", e)
            ToolResults.error("failed", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TOOL_TIMEOUT_MILLIS = 25_000L
    }
}
