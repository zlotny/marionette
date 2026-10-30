package dev.marionette.tools

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.marionette.core.Logger
import dev.marionette.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.net.UnknownHostException

class ToolRegistry(val tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun specs(disabled: Set<String> = emptySet()): List<ToolSpec> = tools.filter { it.name !in disabled }.map { it.spec() }

    operator fun get(name: String): Tool? = byName[name]

    suspend fun resolve(name: String, args: JsonObject, ctx: ToolContext): Resolution {
        val tool = byName[name] ?: return Resolution.Failed(ToolResults.error("unknown_tool", "No tool named $name."))
        val missing = tool.permissions.firstOrNull {
            ContextCompat.checkSelfPermission(ctx.context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing != null) return Resolution.Failed(ToolResults.missingPermission(missing))
        return guarded(name, tool.readOnly, Resolution::Failed) { tool.resolve(args, ctx) }
    }

    suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): JsonObject {
        val tool = byName[name] ?: return ToolResults.error("unknown_tool", "No tool named $name.")
        return guarded(name, tool.readOnly, { it }) { tool.execute(args, ctx) }
    }

    private suspend fun <T> guarded(name: String, readOnly: Boolean, failed: (JsonObject) -> T, block: suspend () -> T): T = try {
        withContext(Dispatchers.Default) { withTimeout(TOOL_TIMEOUT_MILLIS) { block() } }
    } catch (e: TimeoutCancellationException) {
        val message = if (readOnly) "$name took too long. Try once more." else "$name took too long. It may have run. Do not call it again; tell the user to check."
        failed(ToolResults.error("timeout", message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnknownHostException) {
        failed(ToolResults.error("offline", "No internet connection."))
    } catch (e: SecurityException) {
        Logger.w("Tool $name security error", e)
        failed(ToolResults.error("not_allowed", e.message))
    } catch (e: Exception) {
        Logger.w("Tool $name failed", e)
        failed(ToolResults.error("failed", e.message ?: e.javaClass.simpleName))
    }

    companion object {
        private const val TOOL_TIMEOUT_MILLIS = 25_000L
    }
}
