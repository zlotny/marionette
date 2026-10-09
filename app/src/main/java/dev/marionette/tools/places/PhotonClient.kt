package dev.marionette.tools.places

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.obj
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PhotonClient(
    private val baseUrl: String = "https://photon.komoot.io",
    private val client: OkHttpClient = Http.client,
) {
    suspend fun search(text: String, near: LatLng, limit: Int, category: PlaceCategory?, language: String): List<Place> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/".toHttpUrl().newBuilder()
                .addQueryParameter("q", text)
                .addQueryParameter("lat", near.lat.toString())
                .addQueryParameter("lon", near.lng.toString())
                .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
                .addQueryParameter("location_bias_scale", "0.1")
                .apply {
                    language.substringBefore('-').takeIf { it in SUPPORTED_LANGUAGES }?.let { addQueryParameter("lang", it) }
                    category?.photonTags?.forEach { addQueryParameter("osm_tag", it) }
                }
                .build()
            client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
                if (!response.isSuccessful) throw IOException("Photon HTTP ${response.code}")
                parse(AppJson.parseToJsonElement(response.body.string()).obj() ?: JsonObject(emptyMap()))
            }
        }

    companion object {
        private val SUPPORTED_LANGUAGES = setOf("en", "de", "fr", "it")

        fun parse(body: JsonObject): List<Place> = body["features"].arr().orEmpty().mapNotNull { element ->
            val feature = element.obj() ?: return@mapNotNull null
            val props = feature["properties"].obj() ?: return@mapNotNull null
            val coordinates = feature["geometry"].obj()?.get("coordinates").arr() ?: return@mapNotNull null
            if (coordinates.size < 2) return@mapNotNull null
            val name = props.string("name") ?: return@mapNotNull null
            val street = listOfNotNull(props.string("street"), props.string("housenumber")).joinToString(" ")
            Place(
                name = name,
                address = listOf(street, props.string("city").orEmpty()).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                location = LatLng(coordinates[1].jsonPrimitive.double, coordinates[0].jsonPrimitive.double),
                category = props.string("osm_value"),
            )
        }
    }
}
