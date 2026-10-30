package dev.marionette.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingTest {
    private val speaker = Port(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "Pixel", input = false)
    private val mic = Port(2, AudioDeviceInfo.TYPE_BUILTIN_MIC, "", "Pixel", input = true)
    private val budsOut = Port(3, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "AA:BB", "Buds", input = false)
    private val budsSco = Port(4, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", "Buds", input = false)
    private val budsMic = Port(5, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "AA:BB", "Buds", input = true)
    private val wiredOut = Port(6, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "Jack", input = false)
    private val phone = listOf(speaker, mic)
    private val buds = listOf(budsOut, budsSco, budsMic)

    @Test
    fun nothingConnectedUsesThePhone() {
        assertEquals(AudioRoute.PHONE, AudioRouting.route(emptyList(), phone, car = false))
    }

    @Test
    fun bluetoothHeadsetJoinsOutputAndMicByAddress() {
        val seen = AudioRouting.seen(phone + buds, car = false)
        assertEquals(listOf(KnownAudioDevice("bt:AA:BB", AudioKind.BLUETOOTH, "Buds", hasMic = true)), seen)
    }

    @Test
    fun headsetMicOpensOnlyForTheRequest() {
        val known = AudioRouting.seen(phone + buds, car = false)
        val route = AudioRouting.route(known, phone + buds, car = false)
        assertEquals(budsOut, route.output)
        assertEquals(mic, route.wakeInput)
        assertEquals(budsMic, route.requestInput)
        assertEquals(AudioAttributes.USAGE_ASSISTANT, route.speechUsage)
    }

    @Test
    fun ignoredDeviceFallsBackToThePhone() {
        val known = AudioRouting.seen(phone + buds, car = false).map { it.copy(enabled = false) }
        val route = AudioRouting.route(known, phone + buds, car = false)
        assertNull(route.device)
        assertEquals(speaker, route.output)
        assertEquals(mic, route.requestInput)
    }

    @Test
    fun deviceWithoutMicKeepsThePhoneMic() {
        val known = AudioRouting.seen(phone + wiredOut, car = false)
        assertFalse(known.single().hasMic)
        val route = AudioRouting.route(known, phone + wiredOut, car = false)
        assertEquals(wiredOut, route.output)
        assertEquals(mic, route.requestInput)
    }

    @Test
    fun priorityPicksTheFirstConnectedDevice() {
        val wired = KnownAudioDevice("wired", AudioKind.WIRED, "Jack", hasMic = false)
        val budsDevice = KnownAudioDevice("bt:AA:BB", AudioKind.BLUETOOTH, "Buds", hasMic = true)
        val ports = phone + buds + wiredOut
        assertEquals(wiredOut, AudioRouting.route(listOf(wired, budsDevice), ports, car = false).output)
        assertEquals(budsOut, AudioRouting.route(listOf(budsDevice, wired), ports, car = false).output)
    }

    @Test
    fun androidAutoPlaysAsNavigationOnTheCar() {
        val known = AudioRouting.seen(phone, car = true)
        val route = AudioRouting.route(known, phone, car = true)
        assertTrue(route.carSpeaker)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, route.speechUsage)
        assertEquals(mic, route.requestInput)
    }

    @Test
    fun androidAutoWithPhoneSpeakerAvoidsTheCar() {
        val known = AudioRouting.seen(phone, car = true).map { it.copy(deviceSpeaker = false) }
        val route = AudioRouting.route(known, phone, car = true)
        assertEquals(speaker, route.output)
        assertFalse(route.carSpeaker)
    }

    @Test
    fun carCallLinkDoesNotOverrideAndroidAutoSpeaker() {
        val carCall = listOf(budsSco, budsMic)
        val auto = AudioRouting.seen(phone, car = true).map { it.copy(deviceSpeaker = false) }
        val link = AudioRouting.seen(phone + carCall, car = false)
        val route = AudioRouting.route(link + auto, phone + carCall, car = true)
        assertEquals(AudioRoute.ANDROID_AUTO_ID, route.device?.id)
        assertEquals(speaker, route.output)
        assertFalse(route.carSpeaker)
    }

    @Test
    fun deviceVolumeMultipliesSpeechAndCues() {
        val ports = phone + budsOut
        val known = AudioRouting.seen(ports, car = false).map { it.copy(volume = -50) }
        val route = AudioRouting.route(known, ports, car = false)
        assertEquals(1.41f, route.speechGain(phoneVolume = 100), 0.01f)
        assertEquals(1.0f, route.cueGain(phoneVolume = 100, earconVolume = 100), 0.01f)
    }

    @Test
    fun phoneVolumeAppliesWithoutADevice() {
        assertEquals(1f, AudioRoute.PHONE.speechGain(0))
        assertEquals(3f, AudioRoute.PHONE.speechGain(200))
        assertEquals(0f, AudioRoute.PHONE.cueGain(-100, 0))
    }

    @Test
    fun carBluetoothOutputUsesTheMusicPath() {
        val ports = phone + budsOut
        val route = AudioRouting.route(AudioRouting.seen(ports, car = true).reversed(), ports, car = true)
        assertEquals(budsOut, route.output)
        assertEquals(AudioAttributes.USAGE_MEDIA, route.speechUsage)
        assertEquals(AudioAttributes.USAGE_MEDIA, route.toneUsage)
    }

    @Test
    fun carSpeakerAndBluetoothOutputsAreWireless() {
        val auto = AudioRouting.seen(phone, car = true)
        assertTrue(AudioRouting.route(auto, phone, car = true).wirelessOutput)
        assertTrue(AudioRouting.route(AudioRouting.seen(phone + buds, car = false), phone + buds, car = false).wirelessOutput)
        assertFalse(AudioRouting.route(auto.map { it.copy(deviceSpeaker = false) }, phone, car = true).wirelessOutput)
    }

    @Test
    fun newDevicesGoFirstAndKnownOnesKeepSettings() {
        val old = KnownAudioDevice("wired", AudioKind.WIRED, "Jack", hasMic = false, deviceSpeaker = false)
        val merged = AudioRouting.merge(listOf(old), AudioRouting.seen(phone + buds + wiredOut, car = false))
        assertEquals(listOf("bt:AA:BB", "wired"), merged.map { it.id })
        assertFalse(merged.last().deviceSpeaker)
    }

    @Test
    fun micSupportIsRemembered() {
        val known = listOf(KnownAudioDevice("bt:AA:BB", AudioKind.BLUETOOTH, "Buds", hasMic = true))
        val merged = AudioRouting.merge(known, AudioRouting.seen(phone + budsOut, car = false))
        assertTrue(merged.single().hasMic)
    }
}
