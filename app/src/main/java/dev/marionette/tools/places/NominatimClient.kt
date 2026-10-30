package dev.marionette.tools.places

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.await
import dev.marionette.core.obj
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

class NominatimClient(
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val client: OkHttpClient = Http.tools,
) {
    private val gate = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(
        text: String,
        near: LatLng?,
        limit: Int,
        language: String = Locale.getDefault().toLanguageTag(),
        radiusMeters: Int? = null,
    ): List<Place> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("extratags", "1")
                .addQueryParameter("limit", limit.coerceIn(1, 40).toString())
                .apply {
                    near?.let {
                        val box = Geo.boundingBox(it, radiusMeters ?: DEFAULT_BIAS_METERS)
                        addQueryParameter("viewbox", "${box.west},${box.north},${box.east},${box.south}")
                        if (radiusMeters != null) addQueryParameter("bounded", "1")
                    }
                }
                .build()
            val request = Request.Builder().url(url).header("Accept-Language", language).get().build()
            throttled {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Nominatim HTTP ${response.code}")
                    val items = AppJson.parseToJsonElement(response.body.string()) as? JsonArray ?: JsonArray(emptyList())
                    items.mapNotNull { element ->
                        val item = element.obj() ?: return@mapNotNull null
                        val lat = item.string("lat")?.toDoubleOrNull() ?: return@mapNotNull null
                        val lng = item.string("lon")?.toDoubleOrNull() ?: return@mapNotNull null
                        val display = item.string("display_name").orEmpty()
                        Place(
                            name = item.string("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(","),
                            address = display,
                            location = LatLng(lat, lng),
                            category = item.string("type"),
                            phone = item["extratags"].obj()?.let { it.string("phone") ?: it.string("contact:phone") },
                        )
                    }
                }
            }
        }

    suspend fun reverse(point: LatLng, language: String = Locale.getDefault().toLanguageTag()): String? = withContext(Dispatchers.IO) {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lng.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "17")
            .build()
        throttled {
            client.newCall(Request.Builder().url(url).header("Accept-Language", language).build()).await().use { response ->
                if (!response.isSuccessful) return@use null
                (AppJson.parseToJsonElement(response.body.string()).obj())?.string("display_name")
            }
        }
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = gate.withLock {
        val wait = lastRequestAt + MIN_INTERVAL_MILLIS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    companion object {
        private const val MIN_INTERVAL_MILLIS = 1_100L
        private const val DEFAULT_BIAS_METERS = 50_000
    }
}
