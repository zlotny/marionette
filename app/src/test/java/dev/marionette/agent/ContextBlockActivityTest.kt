package dev.marionette.agent

import dev.marionette.settings.ContextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ContextBlockActivityTest {
    private val base = DeviceSnapshot(time = ZonedDateTime.of(2026, 10, 10, 9, 30, 0, 0, ZoneOffset.UTC), locale = "en-US")

    @Test
    fun rendersActivityInFixedOrder() {
        val s = base.copy(
            media = "Spotify playing",
            clock = "no timers; no alarm set",
            navigation = "none",
            messages = "WhatsApp: Ana 09:12",
            missedCalls = "none",
            agenda = "today: 10:00 \"Dentist\"",
        )
        assertEquals(
            listOf(
                "locale: en-US",
                "media: Spotify playing",
                "clock: no timers; no alarm set",
                "navigation: none",
                "unread_messages: WhatsApp: Ana 09:12",
                "missed_calls: none",
                "calendar: today: 10:00 \"Dentist\"",
            ),
            ContextBlock.render(s, setOf(ContextField.ACTIVITY)).lines(),
        )
    }

    @Test
    fun describesScreenState() {
        val fields = setOf(ContextField.SCREEN)
        assertEquals("screen: off", ContextBlock.render(base.copy(screenOn = false, locked = true), fields).lines().last())
        assertEquals(
            "screen: on, locked (needs PIN, fingerprint or face)",
            ContextBlock.render(base.copy(screenOn = true, locked = true), fields).lines().last(),
        )
        assertFalse(ContextBlock.render(base, fields).contains("screen"))
    }

    @Test
    fun addsLocationAgeOnlyWhenStale() {
        val located = base.copy(latitude = 1.0, longitude = 2.0)
        val fields = setOf(ContextField.LOCATION)
        assertEquals("location: 1.00000,2.00000 (45s old)", ContextBlock.render(located.copy(locationAgeSeconds = 45), fields).lines().last())
        assertEquals("location: 1.00000,2.00000", ContextBlock.render(located.copy(locationAgeSeconds = 29), fields).lines().last())
    }

    @Test
    fun hidesSlowSpeedAndUnknownCharging() {
        assertFalse(ContextBlock.render(base.copy(speedKmh = 4.9f), setOf(ContextField.MOTION)).contains("speed"))
        assertEquals("speed: 30 km/h", ContextBlock.render(base.copy(speedKmh = 30f), setOf(ContextField.MOTION)).lines().last())
        assertEquals("battery: 15%, not charging", ContextBlock.render(base.copy(batteryPercent = 15), setOf(ContextField.BATTERY)).lines().last())
    }
}
