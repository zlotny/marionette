package dev.marionette.agent

import dev.marionette.settings.ContextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ContextBlockTest {
    private val snapshot = DeviceSnapshot(
        time = ZonedDateTime.of(2026, 10, 10, 9, 30, 15, 500_000_000, ZoneOffset.ofHours(2)),
        locale = "es-ES",
        latitude = 40.416775,
        longitude = -3.703790,
        accuracyMeters = 12.4f,
        place = "Madrid",
        speedKmh = 87.6f,
        headingDegrees = 181.2f,
        batteryPercent = 64,
        charging = true,
        audioOutput = "bluetooth",
        androidAuto = true,
        screenOn = true,
        locked = false,
        clock = "no timers; next alarm tomorrow (Sun) 07:00",
        messages = "none",
    )

    @Test
    fun rendersAllFields() {
        val block = ContextBlock.render(snapshot, ContextField.entries.toSet())
        assertEquals(
            listOf(
                "time: 2026-10-10T09:30:15+02:00 (Saturday)",
                "locale: es-ES",
                "location: 40.41678,-3.70379 accuracy 12m, Madrid",
                "speed: 88 km/h, heading 181",
                "battery: 64%, charging",
                "audio_output: bluetooth",
                "driving: Android Auto connected",
                "clock: no timers; next alarm tomorrow (Sun) 07:00",
                "unread_messages: none",
                "screen: on, unlocked",
            ),
            block.lines(),
        )
    }

    @Test
    fun alwaysIncludesLocale() {
        assertEquals("locale: es-ES", ContextBlock.render(snapshot, emptySet()))
    }

    @Test
    fun omitsDisabledFields() {
        val block = ContextBlock.render(snapshot, setOf(ContextField.BATTERY))
        assertTrue(block.contains("battery: 64%"))
        assertFalse(block.contains("location"))
        assertFalse(block.contains("time"))
    }

    @Test
    fun reportsUnknownLocation() {
        val block = ContextBlock.render(snapshot.copy(latitude = null, longitude = null), setOf(ContextField.LOCATION))
        assertTrue(block.lines().contains("location: unknown"))
    }

    @Test
    fun skipsMotionWithoutSpeed() {
        val block = ContextBlock.render(snapshot.copy(speedKmh = null), setOf(ContextField.MOTION))
        assertFalse(block.contains("speed"))
    }
}
