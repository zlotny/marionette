package dev.marionette.tools

import android.content.Context
import android.content.res.Resources
import dev.marionette.agent.DeviceSnapshotProvider
import dev.marionette.core.jsonOf
import dev.marionette.launch.LaunchStatus
import dev.marionette.launch.Launcher
import dev.marionette.media.PlaybackHold
import dev.marionette.settings.AppSettings
import dev.marionette.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

enum class ToolCategory { NAVIGATION, PLACES, TIME, COMMUNICATION, NOTIFICATIONS, MEDIA, CALENDAR, DEVICE, APPS, SCREEN }

class ToolContext(
    val context: Context,
    val device: DeviceSnapshotProvider,
    val launcher: Launcher,
    val settings: AppSettings,
    val playback: PlaybackHold,
)

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    val category: ToolCategory
    val requiresConfirmation: Boolean get() = false
    val readOnly: Boolean get() = false
    val permissions: List<String> get() = emptyList()

    fun confirmationPrompt(args: JsonObject, res: Resources): String? = null

    fun summary(args: JsonObject): String = ""

    fun silencesTurn(args: JsonObject, result: JsonObject): Boolean = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject

    fun spec() = ToolSpec(name, description, parameters)
}

object ToolResults {
    fun ok(vararg pairs: Pair<String, Any?>) = jsonOf("ok" to true, *pairs)

    fun error(code: String, message: String? = null) = jsonOf("ok" to false, "error" to code, "message" to message)

    fun needsUnlock() = error("needs_unlock", "The phone is locked. Ask the user to unlock it.")

    fun missingPermission(permission: String) = error("missing_permission", "Permission $permission is not granted. Ask the user to grant it in Marionette settings.")
}

fun launchResult(status: LaunchStatus, what: String, vararg extra: Pair<String, Any?>): JsonObject = when (status) {
    LaunchStatus.STARTED -> ToolResults.ok("status" to "opened", *extra)
    LaunchStatus.WAITING_FOR_UNLOCK -> ToolResults.ok(
        "status" to "waiting_for_unlock",
        "message" to "$what opens as soon as the user unlocks the phone. Tell the user to unlock it.",
        *extra,
    )
    LaunchStatus.FAILED -> ToolResults.error("launch_failed", "$what could not be opened.")
}

fun JsonObject.isError(): Boolean = this["ok"]?.toString() == "false"
