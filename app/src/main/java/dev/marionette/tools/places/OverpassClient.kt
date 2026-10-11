package dev.marionette.tools.places

import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.double
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class OverpassClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val client: OkHttpClient = Http.client,
) {
    suspend fun search(center: LatLng, radiusMeters: Int, nameQuery: String?, category: PlaceCategory?): List<Place> =
        withContext(Dispatchers.IO) {
            val query = buildQuery(center, radiusMeters, nameQuery, category)
            var lastError: Exception? = null
            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder().url(endpoint).post(FormBody.Builder().add("data", query).build()).build()
                    client.newCall(request).await().use { response ->
                        if (!response.isSuccessful) throw IOException("Overpass HTTP ${response.code}")
                        return@withContext parse(parseObject(response.body.string()))
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("Overpass unavailable")
        }

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        fun escapeRegex(text: String): String =
            text.trim().replace(Regex("""[\\.^$|?*+()\[\]{}"]"""), """\\\\$0""").replace("'", ".?")

        fun nameRegex(text: String): String {
            val folded = escapeRegex(text)
            return folded.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(".*")
        }

        fun buildQuery(center: LatLng, radiusMeters: Int, nameQuery: String?, category: PlaceCategory?): String {
            val around = "(around:$radiusMeters,${center.lat},${center.lng})"
            val name = nameQuery?.takeIf { it.isNotBlank() }?.let(::nameRegex)
            val diet = nameQuery?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z_]+")) }
            val selectors = buildList {
                val filters = category?.osmFilters ?: listOf("")
                filters.forEach { filter ->
                    if (name != null) {
                        add("nwr$filter[\"name\"~\"$name\",i]$around;")
                        add("nwr$filter[\"brand\"~\"$name\",i]$around;")
                        add("nwr$filter[\"cuisine\"~\"$name\",i]$around;")
                        if (diet != null) add("nwr$filter[\"diet:$diet\"~\"^(yes|only)$\"]$around;")
                    } else if (filter.isNotEmpty()) {
                        add("nwr$filter$around;")
                    }
                }
            }
            return "[out:json][timeout:20];(${selectors.joinToString("")});out center tags 60;"
        }

        fun parse(body: JsonObject): List<Place> = body["elements"].arr().orEmpty().mapNotNull { element ->
            val e = element.obj() ?: return@mapNotNull null
            val tags = e["tags"].obj() ?: return@mapNotNull null
            val lat = e.double("lat") ?: e["center"].obj()?.double("lat") ?: return@mapNotNull null
            val lng = e.double("lon") ?: e["center"].obj()?.double("lon") ?: return@mapNotNull null
            val name = tags.string("name") ?: tags.string("brand") ?: return@mapNotNull null
            Place(
                name = name,
                address = address(tags),
                location = LatLng(lat, lng),
                category = tags.string("amenity") ?: tags.string("shop") ?: tags.string("tourism"),
                openingHours = tags.string("opening_hours"),
            )
        }.distinctBy { "${it.name}|${"%.4f".format(java.util.Locale.US, it.location.lat)}|${"%.4f".format(java.util.Locale.US, it.location.lng)}" }

        private fun address(tags: JsonObject): String? {
            val street = listOfNotNull(tags.string("addr:street"), tags.string("addr:housenumber")).joinToString(" ")
            return listOf(street, tags.string("addr:city").orEmpty()).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        }
    }
}
