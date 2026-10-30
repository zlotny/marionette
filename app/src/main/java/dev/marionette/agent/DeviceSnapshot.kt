package dev.marionette.agent

import java.time.ZonedDateTime

data class DeviceSnapshot(
    val time: ZonedDateTime,
    val locale: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationAgeSeconds: Long? = null,
    val place: String? = null,
    val speedKmh: Float? = null,
    val headingDegrees: Float? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val audioOutput: String? = null,
    val androidAuto: Boolean? = null,
    val audioDevices: String? = null,
    val network: String? = null,
    val screenOn: Boolean? = null,
    val locked: Boolean? = null,
    val media: String? = null,
    val clock: String? = null,
    val navigation: String? = null,
    val messages: String? = null,
    val agenda: String? = null,
    val missedCalls: String? = null,
)
