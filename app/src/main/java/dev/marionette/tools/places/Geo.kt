package dev.marionette.tools.places

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun boundingBox(center: LatLng, radiusMeters: Int): BoundingBox {
        val dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_M)
        val dLng = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        return BoundingBox(center.lat - dLat, center.lng - dLng, center.lat + dLat, center.lng + dLng)
    }
}
