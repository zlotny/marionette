package dev.marionette.agent

import dev.marionette.settings.ContextField
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

object ContextBlock {
    fun render(s: DeviceSnapshot, fields: Set<ContextField>): String = buildList {
        if (ContextField.TIME in fields) {
            val day = s.time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            add("time: ${s.time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)} ($day)")
        }
        add("locale: ${s.locale}")
        if (ContextField.LOCATION in fields) {
            if (s.latitude != null && s.longitude != null) {
                val coords = "%.5f,%.5f".format(Locale.US, s.latitude, s.longitude)
                val accuracy = s.accuracyMeters?.let { " accuracy ${it.roundToInt()}m" }.orEmpty()
                val age = s.locationAgeSeconds?.takeIf { it >= STALE_SECONDS }?.let { " (${it}s old)" }.orEmpty()
                val place = s.place?.let { ", $it" }.orEmpty()
                add("location: $coords$accuracy$age$place")
            } else {
                add("location: unknown")
            }
        }
        if (ContextField.MOTION in fields && s.speedKmh != null && s.speedKmh >= MIN_SPEED_KMH) {
            val heading = s.headingDegrees?.let { ", heading ${it.roundToInt()}" }.orEmpty()
            add("speed: ${s.speedKmh.roundToInt()} km/h$heading")
        }
        if (ContextField.BATTERY in fields && s.batteryPercent != null) {
            add("battery: ${s.batteryPercent}%, ${if (s.charging == true) "charging" else "not charging"}")
        }
        if (ContextField.AUDIO in fields) {
            s.audioOutput?.let { add("audio_output: $it") }
            if (s.androidAuto == true) add("driving: Android Auto connected")
            s.audioDevices?.let { add("audio_devices: $it") }
        }
        if (ContextField.NETWORK in fields) s.network?.let { add("network: $it") }
        if (ContextField.ACTIVITY in fields) {
            s.media?.let { add("media: $it") }
            s.clock?.let { add("clock: $it") }
            s.navigation?.let { add("navigation: $it") }
            s.messages?.let { add("unread_messages: $it") }
            s.missedCalls?.let { add("missed_calls: $it") }
            s.agenda?.let { add("calendar: $it") }
        }
        if (ContextField.SCREEN in fields && s.screenOn != null) {
            add(
                when {
                    !s.screenOn -> "screen: off"
                    s.locked == true -> "screen: on, locked (needs PIN, fingerprint or face)"
                    else -> "screen: on, unlocked"
                },
            )
        }
    }.joinToString("\n")

    private const val STALE_SECONDS = 30L
    private const val MIN_SPEED_KMH = 5f
}
