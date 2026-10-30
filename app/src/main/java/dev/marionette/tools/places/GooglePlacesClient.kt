package dev.marionette.tools.places

import dev.marionette.core.AppJson
import dev.marionette.core.Http
import dev.marionette.core.arr
import dev.marionette.core.await
import dev.marionette.core.boolean
import dev.marionette.core.double
import dev.marionette.core.obj
import dev.marionette.core.parseObject
import dev.marionette.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GooglePlacesClient(private val apiKey: String, private val client: OkHttpClient = Http.tools) {
    suspend fun search(text: String, center: LatLng, radiusMeters: Int, limit: Int, language: String): List<Place> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("textQuery", text)
                put("pageSize", limit.coerceIn(1, 20))
                put("languageCode", language)
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        putJsonObject("center") {
                            put("latitude", center.lat)
                            put("longitude", center.lng)
                        }
                        put("radius", radiusMeters.coerceIn(1, 50_000).toDouble())
                    }
                }
            }
            val request = Request.Builder()
                .url("https://places.googleapis.com/v1/places:searchText")
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("Places HTTP ${response.code}")
                parse(response.body.string())
            }
        }

    companion object {
        const val FIELD_MASK = "places.displayName,places.formattedAddress,places.location,places.primaryType," +
            "places.rating,places.userRatingCount,places.currentOpeningHours.openNow,places.nationalPhoneNumber"

        fun parse(body: String): List<Place> = parseObject(body)["places"].arr().orEmpty().mapNotNull { element ->
            val p = element.obj() ?: return@mapNotNull null
            val location = p["location"].obj() ?: return@mapNotNull null
            Place(
                name = p["displayName"].obj()?.string("text") ?: return@mapNotNull null,
                address = p.string("formattedAddress"),
                location = LatLng(location.double("latitude") ?: return@mapNotNull null, location.double("longitude") ?: return@mapNotNull null),
                category = p.string("primaryType"),
                rating = p.double("rating"),
                ratingCount = p.double("userRatingCount")?.toInt(),
                openNow = p["currentOpeningHours"].obj()?.boolean("openNow"),
                phone = p.string("nationalPhoneNumber"),
            )
        }
    }
}
