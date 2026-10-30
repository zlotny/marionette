package dev.marionette.tools

import android.Manifest
import android.content.Intent
import android.net.Uri
import dev.marionette.core.arr
import dev.marionette.core.double
import dev.marionette.core.int
import dev.marionette.core.jsonOf
import dev.marionette.core.obj
import dev.marionette.core.isPackageInstalled
import dev.marionette.core.string
import dev.marionette.core.text
import dev.marionette.launch.LaunchStatus
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.notifications.body
import dev.marionette.notifications.title
import dev.marionette.tools.places.FindPlacesTool
import dev.marionette.tools.places.PlaceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale

class NavigateTool : Tool {
    override val name = "navigate"
    override val category = ToolCategory.NAVIGATION
    override val description =
        "Start turn-by-turn navigation in Google Maps. Give lat and lng (from find_places or your research), or destination as text " +
            "when the user names a full address or a well-known place (Maps finds it, no lookup needed). " +
            "For \"the nearest X\" give nearest and/or category instead: it finds the closest match and goes there in one call. " +
            "contact goes to a contact's saved address. via adds stops on the way, in order; a stop can be category:fuel or nearest:Repsol. " +
            "event goes to the location of the next calendar event whose title has those words (\"llévame a la reunión\")."
    override val parameters = schema {
        number("lat", "Destination latitude.")
        number("lng", "Destination longitude.")
        string("destination", "Destination as text, e.g. \"Calle Mayor 12, Ourense\" or \"Catedral de Santiago\", instead of lat and lng.")
        string("nearest", "Go to the closest place matching this name, brand or cuisine, e.g. \"Repsol\", \"sushi\".")
        string("category", "Go to the closest place of this kind.", enum = PlaceCategory.keys)
        string("contact", "Go to this contact's saved postal address, e.g. \"mamá\".")
        string("event", "Words of an upcoming calendar event title: go to its location.")
        stringArray("via", "Stops before the destination, each an address, a place name, \"lat,lng\", or category:KIND / nearest:NAME for the closest such place.")
        string("label", "Destination name, for the reply and logs.")
        string("mode", "Travel mode. Default drive (also motorcycle).", enum = listOf("drive", "walk", "bicycle"))
    }

    override fun summary(args: JsonObject) = args.string("label") ?: args.string("destination") ?: args.string("event") ?: args.string("contact") ?: args.string("nearest") ?: args.string("category")?.replace('_', ' ').orEmpty()

    private val finder = FindPlacesTool()

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        var lat = args.double("lat")
        var lng = args.double("lng")
        var text = args.string("destination")?.trim()?.takeIf { it.isNotEmpty() }
        var found: Map<String, Any?>? = null
        args.string("event")?.takeIf { it.isNotBlank() && (lat == null || lng == null) && text == null }?.let { words ->
            if (!Contacts.granted(ctx.context, Manifest.permission.READ_CALENDAR)) return ToolResults.missingPermission(Manifest.permission.READ_CALENDAR)
            val event = EventMatch.nextWithLocation(ctx.context.contentResolver, words)
                ?: return ToolResults.error("not_found", "No event in the next 7 days matches \"$words\" with a location. Ask the user for the address.")
            text = event.second
            found = mapOf("event" to event.first, "address" to event.second)
        }
        args.string("contact")?.takeIf { it.isNotBlank() && (lat == null || lng == null) && text == null }?.let { who ->
            when (val address = ContactAddresses.find(ctx, who)) {
                is ContactAddresses.Found -> text = address.address
                is ContactAddresses.Failed -> return address.result
            }
        }
        if ((lat == null || lng == null) && text == null && (args.string("nearest") != null || args.string("category") != null)) {
            val search = finder.execute(jsonOf("query" to args.string("nearest"), "category" to args.string("category"), "limit" to 1), ctx)
            if (search.isError()) return search
            val place = search["places"].arr()?.firstOrNull()?.obj() ?: return ToolResults.error("not_found", "No such place nearby. Use destination with a town, or find_places with near.")
            lat = place.double("lat")
            lng = place.double("lng")
            text = null
            found = mapOf("name" to place.string("name"), "address" to place.string("address"), "distance_m" to place.int("distance_m"))
        }
        val destination = when {
            lat != null && lng != null -> {
                if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return ToolResults.error("bad_arguments", "Coordinates out of range.")
                "%.6f,%.6f".format(Locale.US, lat, lng)
            }
            text != null -> text
            else -> return ToolResults.error("bad_arguments", "Give lat and lng, or destination.")
        }
        val mode = when (args.string("mode")) {
            "walk", "w" -> "w"
            "bicycle", "b" -> "b"
            else -> "d"
        }
        val stops = mutableListOf<String?>()
        val via = args["via"].arr()?.mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty().map { stop ->
            val kind = stop.substringBefore(':', "")
            if (kind != "category" && kind != "nearest") return@map stop.also { stops += null }
            val value = stop.substringAfter(':').trim()
            val search = finder.execute(jsonOf((if (kind == "category") "category" else "query") to value, "limit" to 1), ctx)
            val place = search["places"].arr()?.firstOrNull()?.obj() ?: return ToolResults.error("not_found", "No $value found for the stop.")
            stops += place.string("name")
            "%.6f,%.6f".format(Locale.US, place.double("lat"), place.double("lng"))
        }
        val label = args.string("label") ?: found?.get("name")?.toString() ?: text ?: "destination"
        val intent = Intent(Intent.ACTION_VIEW, MapsUris.navigation(destination, via, mode))
        if (ctx.context.isPackageInstalled(MAPS_PACKAGE)) intent.setPackage(MAPS_PACKAGE)
        if (MapsNavigation.exit() == MapsNavigation.Exit.STOPPED) withTimeoutOrNull(RESTART_DELAY_MILLIS) { while (MapsNavigation.active()) delay(POLL_MILLIS) }
        return when (ctx.launcher.launch(intent, "navigation to $label")) {
            LaunchStatus.STARTED -> ToolResults.ok("status" to "navigating", "label" to label, "mode" to mode, "stops" to via.size.takeIf { it > 0 }, "stop_places" to stops.filterNotNull().takeIf { it.isNotEmpty() }, "place" to found)
            LaunchStatus.WAITING_FOR_UNLOCK -> ToolResults.ok(
                "status" to "waiting_for_unlock",
                "message" to "Navigation starts as soon as the user unlocks the phone. Tell the user to unlock it.",
            )
            LaunchStatus.FAILED -> ToolResults.error("launch_failed", "Google Maps could not start.")
        }
    }

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        private const val RESTART_DELAY_MILLIS = 800L
        private const val POLL_MILLIS = 50L
    }
}

object ContactAddresses {
    sealed interface Result
    data class Found(val name: String, val address: String) : Result
    data class Failed(val result: JsonObject) : Result

    suspend fun find(ctx: ToolContext, who: String): Result {
        if (!Contacts.granted(ctx.context, android.Manifest.permission.READ_CONTACTS)) {
            return Failed(ToolResults.missingPermission(android.Manifest.permission.READ_CONTACTS))
        }
        val found = Contacts.search(ctx.context.contentResolver, who, 20)
        val best = Contacts.scored(found, who).minOfOrNull { it.second }
        val names = Contacts.scored(found, who).filter { it.second == best }.map { it.first.name }.distinct()
        val withAddress = withContext(Dispatchers.IO) { names.associateWith { Contacts.details(ctx.context.contentResolver, it)["addresses"].orEmpty() } }
            .filterValues { it.isNotEmpty() }
        return when {
            withAddress.isEmpty() -> Failed(ToolResults.error("no_address", "No saved address for \"$who\". Ask the user for it, or use memory."))
            withAddress.size > 1 || withAddress.values.first().size > 1 -> Failed(
                ToolResults.error("ambiguous", "Several addresses: ${withAddress.entries.joinToString("; ") { (n, a) -> "$n: ${a.joinToString(" / ")}" }}. Pass destination."),
            )
            else -> withAddress.entries.first().let { (n, a) -> Found(n, a.first()) }
        }
    }
}

object MapsUris {
    private val TRAVEL_MODES = mapOf("d" to "driving", "w" to "walking", "b" to "bicycling")

    fun navigation(destination: String, via: List<String>, mode: String): Uri =
        if (via.isEmpty()) {
            Uri.parse("google.navigation:q=${Uri.encode(destination, ",")}&mode=$mode")
        } else {
            Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", destination)
                .appendQueryParameter("waypoints", via.joinToString("|"))
                .appendQueryParameter("travelmode", TRAVEL_MODES[mode] ?: "driving")
                .appendQueryParameter("dir_action", "navigate")
                .build()
        }
}

object MapsNavigation {
    enum class Exit { STOPPED, NOT_NAVIGATING, NO_BUTTON, NO_ACCESS }

    private val EXIT_WORDS = listOf("exit", "stop", "end", "salir", "detener", "finalizar", "terminar")
    private val SHARING_WORDS = listOf("shar", "compart")

    fun active(): Boolean = MarionetteNotificationListener.instance?.activeNotifications.orEmpty()
        .any { it.packageName == NavigateTool.MAPS_PACKAGE && it.isOngoing }

    fun exit(): Exit {
        val listener = MarionetteNotificationListener.instance ?: return Exit.NO_ACCESS
        val maps = listener.activeNotifications.orEmpty().filter { it.packageName == NavigateTool.MAPS_PACKAGE && it.isOngoing }
        if (maps.isEmpty()) return Exit.NOT_NAVIGATING
        val action = maps
            .filterNot { sbn -> SHARING_WORDS.any { word -> InstalledApps.fold("${sbn.title} ${sbn.body}").contains(word) } }
            .flatMap { it.notification.actions?.toList().orEmpty() }
            .firstOrNull { action -> InstalledApps.fold(action.title?.toString().orEmpty()).split(' ').any { it in EXIT_WORDS } }
            ?: return Exit.NO_BUTTON
        return if (runCatching { action.actionIntent.sendAllowingStart(listener) }.isSuccess) Exit.STOPPED else Exit.NO_BUTTON
    }
}

class StopNavigationTool : Tool {
    override val name = "stop_navigation"
    override val category = ToolCategory.NAVIGATION
    override val description = "Stop the turn-by-turn navigation that Google Maps is running."
    override val parameters = schema {}

    override fun endsTurn(args: JsonObject, result: JsonObject) = result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject = when (MapsNavigation.exit()) {
        MapsNavigation.Exit.STOPPED -> ToolResults.ok("status" to "stopped")
        MapsNavigation.Exit.NOT_NAVIGATING -> ToolResults.ok("status" to "not_navigating")
        MapsNavigation.Exit.NO_BUTTON -> ToolResults.error("no_exit_button", "Maps shows no exit button. Use open_app Maps and ui_act to tap exit.")
        MapsNavigation.Exit.NO_ACCESS -> ToolResults.error("missing_permission", "Notification access is off. Ask the user to enable it in Marionette settings.")
    }
}
