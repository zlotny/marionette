package dev.marionette.tools.places

data class Place(
    val name: String,
    val address: String?,
    val location: LatLng,
    val category: String?,
    val openingHours: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null,
    val openNow: Boolean? = null,
)
