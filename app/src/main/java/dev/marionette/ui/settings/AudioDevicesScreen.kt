package dev.marionette.ui.settings

import kotlin.math.roundToInt
import dev.marionette.voice.CloudVoice
import dev.marionette.ui.voice.VoiceTestButton
import dev.marionette.ui.components.SliderRow
import dev.marionette.audio.VolumeOverride
import dev.marionette.audio.AudioRoute
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marionette.MarionetteApp
import dev.marionette.R
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import dev.marionette.audio.AudioKind
import dev.marionette.audio.KnownAudioDevice
import dev.marionette.ui.components.ChoiceRow
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsNote
import dev.marionette.ui.components.SettingsRow
import dev.marionette.ui.components.SettingsScaffold
import dev.marionette.ui.components.SwitchRow

@Composable
fun AudioDevicesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    val routes = MarionetteApp.graph.audioRoutes
    val devices by routes.devices.collectAsStateWithLifecycle()
    val connected by routes.connected.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.audio_devices_title), onBack = onBack) {
        item { SettingsNote(stringResource(R.string.audio_devices_intro)) }
        item {
            SettingsGroup(null) {
                devices.forEachIndexed { position, device ->
                    item { i, n ->
                        SettingsRow(
                            i, n,
                            headline = deviceName(device),
                            supporting = summary(device, device.id in connected, device.id == routes.route().device?.id),
                            leading = { SettingsIcon(icon(device.kind)) },
                            trailing = {
                                Row {
                                    IconButton(onClick = { routes.move(device.id, -1) }, enabled = position > 0) {
                                        Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.audio_devices_up), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { routes.move(device.id, 1) }, enabled = position < devices.lastIndex) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.audio_devices_down), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            onClick = { onOpen(device.id) },
                        )
                    }
                }
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.audio_devices_phone),
                        supporting = stringResource(R.string.audio_devices_phone_body) + if (settings.phoneVolume != 0) " · ${VolumeOverride.label(settings.phoneVolume)}" else "",
                        leading = { SettingsIcon(Icons.Rounded.PhoneAndroid) },
                        onClick = { onOpen(AudioRoute.PHONE_ID) },
                    )
                }
            }
        }
        if (devices.isEmpty()) item { SettingsNote(stringResource(R.string.audio_devices_empty)) }
    }
}

@Composable
fun AudioDeviceScreen(id: String, onBack: () -> Unit) {
    if (id == AudioRoute.PHONE_ID) return PhoneVolumeScreen(onBack)
    val routes = MarionetteApp.graph.audioRoutes
    val devices by routes.devices.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.id == id }
    SettingsScaffold(title = device?.let { deviceName(it) } ?: stringResource(R.string.audio_devices_title), onBack = onBack) {
        device ?: return@SettingsScaffold
        val car = device.kind == AudioKind.ANDROID_AUTO
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SwitchRow(
                        i, n,
                        headline = stringResource(R.string.audio_device_use),
                        supporting = stringResource(R.string.audio_device_use_body),
                        checked = device.enabled,
                        onCheckedChange = { on -> routes.update(id) { it.copy(enabled = on) } },
                    )
                }
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.audio_device_mic),
                        options = listOf(
                            true to stringResource(R.string.audio_device_mic_device),
                            false to stringResource(R.string.audio_device_mic_phone),
                        ),
                        selected = device.usesDeviceMic,
                        onSelect = { use -> routes.update(id) { it.copy(deviceMic = use) } },
                        leading = { SettingsIcon(Icons.Rounded.Mic) },
                        enabled = device.enabled && device.hasMic,
                    )
                }
                item { i, n ->
                    ChoiceRow(
                        i, n,
                        headline = stringResource(R.string.audio_device_speaker),
                        options = listOf(
                            true to stringResource(if (car) R.string.audio_device_speaker_car else R.string.audio_device_speaker_device),
                            false to stringResource(R.string.audio_device_speaker_phone),
                        ),
                        selected = device.deviceSpeaker,
                        onSelect = { use -> routes.update(id) { it.copy(deviceSpeaker = use) } },
                        leading = { SettingsIcon(Icons.AutoMirrored.Rounded.VolumeUp) },
                        enabled = device.enabled,
                    )
                }
                item { i, n -> VolumeRow(i, n, device.volume) { percent -> routes.update(id) { it.copy(volume = percent) } } }
            }
        }
        if (device.id == routes.route().device?.id) item { VolumeTest() }
        item {
            SettingsNote(
                stringResource(
                    when {
                        car -> R.string.audio_device_note_car
                        !device.hasMic -> R.string.audio_device_note_no_mic
                        device.kind == AudioKind.BLUETOOTH || device.kind == AudioKind.BLE -> R.string.audio_device_note_bluetooth
                        else -> R.string.audio_device_note_wired
                    },
                ),
            )
        }
        item {
            SettingsGroup(null) {
                item { i, n ->
                    SettingsRow(
                        i, n,
                        headline = stringResource(R.string.audio_device_forget),
                        leading = { SettingsIcon(Icons.Rounded.DeleteOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) },
                        onClick = {
                            routes.forget(id)
                            onBack()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun deviceName(device: KnownAudioDevice): String = when (device.kind) {
    AudioKind.ANDROID_AUTO -> stringResource(R.string.audio_kind_android_auto)
    AudioKind.WIRED -> stringResource(R.string.audio_kind_wired)
    else -> device.name.ifBlank { stringResource(R.string.audio_kind_bluetooth) }
}

@Composable
private fun summary(device: KnownAudioDevice, connected: Boolean, inUse: Boolean): String {
    val state = stringResource(
        when {
            inUse -> R.string.audio_devices_in_use
            connected -> R.string.audio_devices_connected
            else -> R.string.audio_devices_not_connected
        },
    )
    if (!device.enabled) return "$state · ${stringResource(R.string.audio_devices_ignored)}"
    val mic = stringResource(if (device.usesDeviceMic) R.string.audio_devices_mic_device else R.string.audio_devices_mic_phone)
    val speaker = stringResource(if (device.deviceSpeaker) R.string.audio_devices_speaker_device else R.string.audio_devices_speaker_phone)
    val volume = if (device.volume != 0) " · ${VolumeOverride.label(device.volume)}" else ""
    return "$state · $mic · $speaker$volume"
}

@Composable
private fun PhoneVolumeScreen(onBack: () -> Unit) {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    SettingsScaffold(title = stringResource(R.string.audio_devices_phone), onBack = onBack) {
        item {
            SettingsGroup(null) {
                item { i, n -> VolumeRow(i, n, settings.phoneVolume) { percent -> updateSettings { it.copy(phoneVolume = percent) } } }
            }
        }
        if (MarionetteApp.graph.audioRoutes.route().device == null) item { VolumeTest() }
    }
}

@Composable
private fun VolumeTest() {
    val settings by MarionetteApp.graph.settings.settings.collectAsStateWithLifecycle()
    VoiceTestButton(settings.language, settings.ttsVoice, settings.speechRate, Modifier.fillMaxWidth(), cloud = CloudVoice.from(settings))
}

@Composable
private fun VolumeRow(index: Int, count: Int, percent: Int, onChange: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    SliderRow(
        index, count,
        headline = stringResource(R.string.audio_device_volume),
        supporting = stringResource(R.string.audio_device_volume_body),
        valueLabel = value.roundToInt().let { if (it == VolumeOverride.MIN) stringResource(R.string.volume_muted) else VolumeOverride.label(it) },
        value = value,
        valueRange = VolumeOverride.MIN.toFloat()..VolumeOverride.MAX.toFloat(),
        steps = (VolumeOverride.MAX - VolumeOverride.MIN) / VolumeOverride.STEP - 1,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(value.roundToInt()) },
    )
}

private fun icon(kind: AudioKind) = when (kind) {
    AudioKind.ANDROID_AUTO -> Icons.Rounded.DirectionsCar
    AudioKind.USB -> Icons.Rounded.Usb
    AudioKind.WIRED -> Icons.Rounded.HeadsetMic
    else -> Icons.Rounded.Headphones
}
