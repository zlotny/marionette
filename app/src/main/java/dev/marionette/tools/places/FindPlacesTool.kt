package dev.marionette.tools.places

import dev.marionette.core.Logger
import dev.marionette.core.int
import dev.marionette.core.string
import dev.marionette.tools.Tool
import dev.marionette.tools.ToolCategory
import dev.marionette.tools.ToolContext
import dev.marionette.tools.ToolResults
import dev.marionette.tools.schema
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.roundToInt

class FindPlacesTool(
    private val photon: PhotonClient = PhotonClient(),
    private val overpass: OverpassClient = OverpassClient(),
    private val nominatim: NominatimClient = NominatimClient(),
) : Tool {
    override val name = "find_places"
    override val readOnly = true
    override val category = ToolCategory.PLACES
    override val description =
        "Find places near the user or near a named place. Use for 'the nearest X', 'a gas station', 'McDonald's near Toledo', addresses. " +
            "Returns places sorted by distance from the search center, with coordinates for navigate and a phone number when known. " +
            "With a Google Places key, results also include rating, rating_count and open_now."
    override val parameters = schema {
        string("query", "Name, brand, cuisine or diet to match, e.g. \"McDonald's\", \"Repsol\", \"sushi\", \"vegan\", or a full address. Cuisine and diet match one English word. Omit when only a category is wanted.")
        string("category", "Kind of place, when the user asks for a type of place.", enum = PlaceCategory.keys)
        string("near", "A named place or address to search around instead of the current location.")
        integer("radius_m", "Search radius in meters. Default 10000.")
        integer("limit", "Maximum results. Default 5.")
    }

    override fun summary(args: JsonObject): String =
        listOfNotNull(args.string("query"), args.string("category")?.replace('_', ' '), args.string("near")?.let { "near $it" })
            .joinToString(" ")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val query = args.string("query")?.trim()?.takeIf { it.isNotEmpty() }
        val category = PlaceCategory.of(args.string("category"))
        if (query == null && category == null) return ToolResults.error("bad_arguments", "Give a query or a category.")
        val radius = (args.int("radius_m") ?: DEFAULT_RADIUS_M).coerceIn(200, MAX_RADIUS_M)
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, 20)
        val language = ctx.settings.language.ifBlank { Locale.getDefault().toLanguageTag() }

        val nearText = args.string("near")?.takeIf { it.isNotBlank() }
        val here = ctx.device.location()?.let { LatLng(it.latitude, it.longitude) }
        val center = if (nearText != null) {
            nominatim.search(nearText, here, 1, language).firstOrNull()?.location
                ?: return ToolResults.error("unknown_place", "Could not find \"$nearText\". Pass the town name, or town and region.")
        } else {
            here ?: return ToolResults.error("no_location", "The current location is not available. Pass near with a town or address, or ask where the user is.")
        }

        val searchText = listOfNotNull(query, category?.key?.replace('_', ' ')).joinToString(" ")
        val (places, source) = withTimeoutOrNull(SEARCH_BUDGET_MILLIS) {
            when {
                ctx.settings.googlePlacesKey.isNotBlank() -> runCatching {
                    GooglePlacesClient(ctx.settings.googlePlacesKey).search(searchText, center, radius, limit, language) to "google_places"
                }.getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException || e is UnknownHostException) throw e
                    Logger.w("Google Places failed, using OpenStreetMap", e)
                    searchOsm(center, radius, query, category, language) to "openstreetmap"
                }
                else -> searchOsm(center, radius, query, category, language) to "openstreetmap"
            }
        } ?: return ToolResults.error("timeout", "The place search took too long. Try again.")
        val ranked = places
            .map { it to Geo.distanceMeters(center, it.location) }
            .sortedBy { it.second }
            .take(limit)
        if (ranked.isEmpty()) {
            return ToolResults.ok("places" to emptyList<Any>(), "message" to "No places within ${MAX_RADIUS_M / 1000} km. Try a category, or one English word for a cuisine.")
        }
        Logger.i("Places from $source")
        return ToolResults.ok(
            "near" to nearText,
            "places" to ranked.map { (place, distance) ->
                mapOf(
                    "name" to place.name,
                    "address" to place.address,
                    "lat" to place.location.lat,
                    "lng" to place.location.lng,
                    "distance_m" to distance.roundToInt(),
                    "category" to place.category,
                    "opening_hours" to place.openingHours,
                    "open_now" to place.openNow,
                    "rating" to place.rating,
                    "rating_count" to place.ratingCount,
                    "phone" to place.phone,
                ).filterValues { it != null }
            },
        )
    }

    private suspend fun searchOsm(center: LatLng, radius: Int, query: String?, category: PlaceCategory?, language: String): List<Place> {
        val looksLikeAddress = query != null && category == null && query.any(Char::isDigit)
        if (looksLikeAddress) return nominatim.search(query, center, 10, language)
        val attempts: List<suspend () -> List<Place>> = if (query != null) {
            listOf(
                { photon.search(query, center, 20, category, language) },
                { nominatim.search(query, center, 40, language, MAX_RADIUS_M) },
                { withTimeout(OVERPASS_TIMEOUT_MILLIS) { overpass.search(center, radius, query, category) } },
            )
        } else {
            val phrase = category?.phrase ?: return emptyList()
            expandingRadii(radius).map { r -> suspend { nominatim.search(phrase, center, 40, language, r) } } +
                listOf(suspend { withTimeout(OVERPASS_TIMEOUT_MILLIS) { overpass.search(center, radius, null, category) } })
        }
        return attempts.firstNotNullOfOrNull { attempt ->
            runCatching { attempt() }
                .onFailure {
                    currentCoroutineContext().ensureActive()
                    if (it is UnknownHostException) throw it
                    Logger.w("Place search source failed: ${it.message}")
                }
                .getOrNull()
                ?.filter { Geo.distanceMeters(center, it.location) <= MAX_RADIUS_M }
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun expandingRadii(start: Int): List<Int> = buildList {
        var r = start
        while (true) {
            add(r)
            if (r >= MAX_RADIUS_M) break
            r = (r * 3).coerceAtMost(MAX_RADIUS_M)
        }
    }

    companion object {
        private const val DEFAULT_RADIUS_M = 10_000
        private const val MAX_RADIUS_M = 50_000
        private const val DEFAULT_LIMIT = 5
        private const val OVERPASS_TIMEOUT_MILLIS = 8_000L
        private const val SEARCH_BUDGET_MILLIS = 12_000L
    }
}
