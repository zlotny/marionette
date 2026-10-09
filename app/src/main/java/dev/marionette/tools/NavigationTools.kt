package dev.marionette.tools

import android.content.Intent
import android.net.Uri
import dev.marionette.core.double
import dev.marionette.core.isPackageInstalled
import dev.marionette.core.string
import dev.marionette.launch.LaunchStatus
import dev.marionette.notifications.MarionetteNotificationListener
import kotlinx.serialization.json.JsonObject
import java.util.Locale

class NavigateTool : Tool {
    override val name = "navigate"
    override val category = ToolCategory.NAVIGATION
    override val description =
        "Start turn-by-turn navigation in Google Maps to coordinates. Get coordinates from find_places or your own research. " +
            "Mode: d = drive (default, also for motorcycle), w = walk, b = bicycle."
    override val parameters = schema {
        number("lat", "Destination latitude.", required = true)
        number("lng", "Destination longitude.", required = true)
        string("label", "Destination name, for the reply and logs.", required = true)
        string("mode", "Travel mode.", enum = listOf("d", "w", "b"))
    }

    override fun summary(args: JsonObject) = args.string("label") ?: "destination"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val lat = args.double("lat") ?: return ToolResults.error("bad_arguments", "lat is required.")
        val lng = args.double("lng") ?: return ToolResults.error("bad_arguments", "lng is required.")
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return ToolResults.error("bad_arguments", "Coordinates out of range.")
        val mode = args.string("mode")?.takeIf { it in setOf("d", "w", "b") } ?: "d"
        val label = args.string("label") ?: "destination"
        val uri = Uri.parse("google.navigation:q=${"%.6f,%.6f".format(Locale.US, lat, lng)}&mode=$mode")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (ctx.context.isPackageInstalled(MAPS_PACKAGE)) intent.setPackage(MAPS_PACKAGE)
        return when (ctx.launcher.launch(intent, "navigation to $label")) {
            LaunchStatus.STARTED -> ToolResults.ok("status" to "navigating", "label" to label, "mode" to mode)
            LaunchStatus.WAITING_FOR_UNLOCK -> ToolResults.ok(
                "status" to "waiting_for_unlock",
                "message" to "Navigation starts as soon as the user unlocks the phone. Tell the user to unlock it.",
            )
            LaunchStatus.FAILED -> ToolResults.error("launch_failed", "Google Maps could not start.")
        }
    }

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }
}

class StopNavigationTool : Tool {
    override val name = "stop_navigation"
    override val category = ToolCategory.NAVIGATION
    override val description = "Stop the turn-by-turn navigation that Google Maps is running."
    override val parameters = schema {}

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val listener = MarionetteNotificationListener.instance
            ?: return ToolResults.error("missing_permission", "Notification access is off. Ask the user to enable it in Marionette settings.")
        val maps = listener.activeNotifications.orEmpty().filter { it.packageName == NavigateTool.MAPS_PACKAGE && it.isOngoing }
        if (maps.isEmpty()) return ToolResults.ok("status" to "not_navigating")
        val exit = maps.flatMap { it.notification.actions?.toList().orEmpty() }
            .firstOrNull { action -> EXIT_WORDS.any { action.title?.toString()?.lowercase()?.contains(it) == true } }
            ?: return ToolResults.error("no_exit_button", "Maps shows no exit button. Use open_app Maps and ui_act to tap exit.")
        exit.actionIntent.send()
        return ToolResults.ok("status" to "stopped")
    }

    companion object {
        private val EXIT_WORDS = listOf("exit", "stop", "end", "salir", "detener", "finalizar", "terminar")
    }
}
