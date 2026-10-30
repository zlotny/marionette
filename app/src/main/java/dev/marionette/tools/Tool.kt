package dev.marionette.tools

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.os.Build
import dev.marionette.agent.DeviceSnapshotProvider
import dev.marionette.core.jsonOf
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.launch.Launcher
import dev.marionette.media.PlaybackHold
import dev.marionette.settings.AppSettings
import dev.marionette.llm.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ToolCategory { NAVIGATION, PLACES, TIME, COMMUNICATION, NOTIFICATIONS, MEDIA, CALENDAR, DEVICE, APPS, SCREEN }

class ToolContext(
    val context: Context,
    val device: DeviceSnapshotProvider,
    val launcher: Launcher,
    val settings: AppSettings,
    val playback: PlaybackHold,
) {
    @Volatile var dismissedKeyguard = false
}

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject
    val category: ToolCategory
    val requiresConfirmation: Boolean get() = false
    val readOnly: Boolean get() = false
    val permissions: List<String> get() = emptyList()

    suspend fun resolve(args: JsonObject, ctx: ToolContext): Resolution = Resolution.Ready(args)

    fun needsConfirmation(args: JsonObject): Boolean = requiresConfirmation

    fun confirmationPrompt(args: JsonObject, res: Resources): String? = null

    fun summary(args: JsonObject): String = ""

    fun silencesTurn(args: JsonObject, result: JsonObject): Boolean = false

    fun endsTurn(args: JsonObject, result: JsonObject): Boolean = false

    val endsTurnOnlyWithText: Boolean get() = false

    suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject

    fun spec() = ToolSpec(name, description, parameters)
}

sealed interface Resolution {
    data class Ready(val args: JsonObject) : Resolution
    data class Failed(val result: JsonObject) : Resolution
}

object ToolResults {
    const val IMAGE_JPEG = "image_jpeg"

    fun ok(vararg pairs: Pair<String, Any?>) = jsonOf("ok" to true, *pairs.filter { it.second != null }.toTypedArray())

    fun error(code: String, message: String? = null) = jsonOf("ok" to false, "error" to code, *listOfNotNull(message?.let { "message" to it }).toTypedArray())

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

fun JsonObject.firstString(vararg names: String): String? = names.firstNotNullOfOrNull { string(it)?.takeIf(String::isNotBlank) }

fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.toLong()

fun PendingIntent.sendAllowingStart(context: Context) {
    val options = when {
        Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS).toBundle()
        Build.VERSION.SDK_INT >= 34 -> @Suppress("DEPRECATION") ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
        else -> null
    }
    send(context, 0, null, null, null, null, options)
}

fun Context.appLabel(pkg: String): String =
    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

fun JsonObject.isError(): Boolean = this["ok"]?.toString() == "false"

fun JsonObject.isDone(vararg pending: String): Boolean = !isError() && string("status").let { it != "waiting_for_unlock" && it !in pending }

fun JsonObject.imageJpeg(): String? = string(ToolResults.IMAGE_JPEG)

fun JsonObject.withoutImage(): JsonObject = if (containsKey(ToolResults.IMAGE_JPEG)) JsonObject(this - ToolResults.IMAGE_JPEG) else this
