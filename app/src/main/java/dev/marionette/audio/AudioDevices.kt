package dev.marionette.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import kotlinx.serialization.Serializable

@Serializable
enum class AudioKind { BLUETOOTH, BLE, WIRED, USB, ANDROID_AUTO }

@Serializable
data class KnownAudioDevice(
    val id: String,
    val kind: AudioKind,
    val name: String,
    val hasMic: Boolean,
    val enabled: Boolean = true,
    val deviceMic: Boolean = true,
    val deviceSpeaker: Boolean = true,
    val volume: Int = 0,
) {
    val usesDeviceMic: Boolean get() = hasMic && deviceMic
}

data class Port(val id: Int, val type: Int, val address: String, val name: String, val input: Boolean) {
    val key: Pair<String, AudioKind>? get() = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt:${address.ifBlank { name }}" to AudioKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble:${address.ifBlank { name }}" to AudioKind.BLE
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired" to AudioKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb:$name" to AudioKind.USB
        else -> null
    }

    val needsCommunication: Boolean get() = input && type in COMMUNICATION_TYPES

    companion object {
        val COMMUNICATION_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}

data class AudioRoute(
    val device: KnownAudioDevice?,
    val output: Port?,
    val wakeInput: Port?,
    val requestInput: Port?,
    val car: Boolean,
) {
    fun speechGain(phoneVolume: Int): Float =
        (if (wirelessOutput) WIRELESS_SPEECH_GAIN else 1f) * VolumeOverride.factor(device?.volume ?: phoneVolume)

    fun cueGain(phoneVolume: Int, earconVolume: Int): Float =
        VolumeOverride.factor(device?.volume ?: phoneVolume) * VolumeOverride.factor(earconVolume)

    val carSpeaker: Boolean get() = car && output == null

    val wirelessOutput: Boolean get() = carSpeaker || output?.type in WIRELESS_OUTPUTS

    val speechUsage: Int get() = when {
        carSpeaker -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        car && wirelessOutput -> AudioAttributes.USAGE_MEDIA
        car -> AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
        else -> AudioAttributes.USAGE_ASSISTANT
    }

    val toneUsage: Int get() = speechUsage

    companion object {
        val PHONE = AudioRoute(null, null, null, null, car = false)
        val WIRELESS_OUTPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
        const val ANDROID_AUTO_ID = "android-auto"
        const val PHONE_ID = "phone"
        private const val WIRELESS_SPEECH_GAIN = 2.82f
    }
}

object VolumeOverride {
    const val MIN = -100
    const val MAX = 200
    const val STEP = 10

    fun factor(percent: Int): Float = (1f + percent.coerceIn(MIN, MAX) / 100f)

    fun label(percent: Int): String = if (percent > 0) "+$percent%" else "$percent%"
}

object AudioRouting {
    private fun callOnly(device: KnownAudioDevice, ports: List<Port>): Boolean {
        val own = ports.filter { it.key?.first == device.id }
        return device.kind != AudioKind.ANDROID_AUTO && own.isNotEmpty() && own.none { !it.input && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    fun seen(ports: List<Port>, car: Boolean): List<KnownAudioDevice> {
        val found = ports.mapNotNull { port -> port.key?.let { it to port } }
            .groupBy({ it.first }, { it.second })
            .map { (key, group) ->
                val name = group.firstOrNull { !it.input && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.name ?: group.first().name
                KnownAudioDevice(key.first, key.second, name, hasMic = group.any { it.input })
            }
        return if (car) listOf(KnownAudioDevice(AudioRoute.ANDROID_AUTO_ID, AudioKind.ANDROID_AUTO, "Android Auto", hasMic = false)) + found else found
    }

    fun merge(known: List<KnownAudioDevice>, seen: List<KnownAudioDevice>): List<KnownAudioDevice> {
        val updated = known.map { old ->
            seen.firstOrNull { it.id == old.id }?.let { now -> old.copy(name = now.name.ifBlank { old.name }, hasMic = old.hasMic || now.hasMic) } ?: old
        }
        val added = seen.filter { new -> known.none { it.id == new.id } }
        return added + updated
    }

    fun route(known: List<KnownAudioDevice>, ports: List<Port>, car: Boolean): AudioRoute {
        val connected = seen(ports, car).map { it.id }.toSet()
        if (connected.isEmpty()) return AudioRoute.PHONE
        val speaker = ports.firstOrNull { !it.input && it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val mic = ports.firstOrNull { it.input && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val active = known.firstOrNull { device ->
            device.enabled && device.id in connected && !(car && callOnly(device, ports))
        }
            ?: return AudioRoute(null, speaker, mic, mic, car)
        val own = ports.filter { it.key?.first == active.id }
        val deviceOut = own.firstOrNull { !it.input && it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val deviceIn = own.firstOrNull { it.input }.takeIf { active.usesDeviceMic }
        return AudioRoute(
            device = active,
            output = if (active.deviceSpeaker) deviceOut else speaker,
            wakeInput = deviceIn?.takeUnless { it.needsCommunication } ?: mic,
            requestInput = deviceIn ?: mic,
            car = car,
        )
    }
}
