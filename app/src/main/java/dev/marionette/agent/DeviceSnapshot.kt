package dev.marionette.agent

import java.time.ZonedDateTime

data class DeviceSnapshot(
    val time: ZonedDateTime,
    val locale: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val place: String? = null,
    val speedKmh: Float? = null,
    val headingDegrees: Float? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val audioOutput: String? = null,
    val androidAuto: Boolean? = null,
    val screenOn: Boolean? = null,
    val locked: Boolean? = null,
)
