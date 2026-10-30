package dev.marionette.tools

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import dev.marionette.core.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

object Torch {
    @Volatile
    var on = false
        private set

    fun set(context: Context, enabled: Boolean): Boolean {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return false
        camera.setTorchMode(id, enabled)
        on = enabled
        return true
    }
}

class DeviceSettingTool : Tool {
    override val name = "device_setting"
    override val category = ToolCategory.DEVICE
    override val description =
        "Read or change phone settings: volume (per stream), ringer mode, do not disturb, flashlight, screen brightness, " +
            "or status for all of them plus Wi-Fi and Bluetooth state. Wi-Fi, mobile data and Bluetooth cannot be switched by apps: use open_settings."
    override val parameters = schema {
        string("setting", "Which setting.", required = true, enum = listOf("status", "volume", "ringer_mode", "do_not_disturb", "flashlight", "brightness"))
        string(
            "value",
            "New value. volume: 0-100, +N or -N percent points, up, down, mute, unmute. ringer_mode: normal, vibrate, silent. do_not_disturb: on, off, priority, alarms. " +
                "flashlight: on, off. brightness: 0-100 or auto.",
        )
        string("stream", "Volume stream. Default media.", enum = STREAMS.keys.toList())
    }

    override fun summary(args: JsonObject) = listOfNotNull(args.string("setting")?.replace('_', ' '), args.string("value")).joinToString(" ")

    override fun endsTurn(args: JsonObject, result: JsonObject) = args.string("setting") != "status" && result.isDone()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): JsonObject {
        val c = ctx.context
        val value = args.string("value")?.trim()?.lowercase()
        return when (args.string("setting")) {
            "status" -> status(c)
            "volume" -> volume(c, args.string("stream") ?: "media", value)
            "ringer_mode" -> ringer(c, value)
            "do_not_disturb" -> dnd(c, value)
            "flashlight" -> when (value) {
                "on", "off" -> if (Torch.set(c, value == "on")) ToolResults.ok("flashlight" to value) else ToolResults.error("no_flash", "This phone has no flashlight.")
                else -> ToolResults.error("bad_arguments", "value must be on or off.")
            }
            "brightness" -> brightness(c, value)
            else -> ToolResults.error("bad_arguments", "Unknown setting.")
        }
    }

    private fun audio(c: Context) = c.getSystemService(AudioManager::class.java)

    private fun volumePercent(a: AudioManager, stream: Int): Int {
        val max = a.getStreamMaxVolume(stream).coerceAtLeast(1)
        return (a.getStreamVolume(stream) * 100f / max).roundToInt()
    }

    private fun status(c: Context): JsonObject {
        val a = audio(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val battery = c.getSystemService(BatteryManager::class.java)
        return ToolResults.ok(
            "volume" to STREAMS.mapValues { volumePercent(a, it.value) },
            "ringer_mode" to RINGER_NAMES[a.ringerMode],
            "do_not_disturb" to DND_NAMES[nm.currentInterruptionFilter],
            "flashlight" to if (Torch.on) "on" else "off",
            "brightness" to runCatching {
                if (Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    "auto"
                } else {
                    (Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255).toString()
                }
            }.getOrNull(),
            "wifi_enabled" to runCatching { c.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull(),
            "bluetooth_enabled" to runCatching { c.getSystemService(BluetoothManager::class.java).adapter?.isEnabled }.getOrNull(),
            "battery_percent" to battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to battery.isCharging,
        )
    }

    private fun volume(c: Context, streamName: String, value: String?): JsonObject {
        val stream = STREAMS[streamName] ?: return ToolResults.error("bad_arguments", "Unknown stream.")
        val a = audio(c)
        val previous = volumePercent(a, stream)
        try {
            val relative = value?.takeIf { RELATIVE.matches(it) }?.trimEnd('%')?.toInt()
            if (relative != null) {
                val target = (previous + relative).coerceIn(0, 100)
                a.setStreamVolume(stream, (target / 100f * a.getStreamMaxVolume(stream)).roundToInt(), 0)
            } else when (value) {
                "up" -> a.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
                "down" -> a.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
                "mute" -> a.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> a.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                else -> {
                    val percent = value?.trimEnd('%')?.toIntOrNull()?.coerceIn(0, 100)
                        ?: return ToolResults.error("bad_arguments", "value must be 0-100, up, down, mute or unmute.")
                    a.setStreamVolume(stream, (percent / 100f * a.getStreamMaxVolume(stream)).roundToInt(), 0)
                }
            }
        } catch (e: SecurityException) {
            return ToolResults.error("not_allowed", "Changing this volume needs do not disturb access. Ask the user to grant it in Marionette settings.")
        }
        return ToolResults.ok("stream" to streamName, "volume" to volumePercent(a, stream), "previous" to previous, "note" to "Volume moves in steps of ${100 / a.getStreamMaxVolume(stream).coerceAtLeast(1)}%, so it may differ slightly from the request.")
    }

    private fun ringer(c: Context, value: String?): JsonObject {
        val mode = RINGER_NAMES.entries.firstOrNull { it.value == value }?.key ?: return ToolResults.error("bad_arguments", "value must be normal, vibrate or silent.")
        return try {
            val previous = RINGER_NAMES[audio(c).ringerMode]
            audio(c).ringerMode = mode
            ToolResults.ok("ringer_mode" to value, "previous" to previous)
        } catch (e: SecurityException) {
            ToolResults.error("not_allowed", "Silent mode needs do not disturb access. Set ringer_mode vibrate now, and tell the user to grant that access in Marionette settings.")
        }
    }

    private suspend fun dnd(c: Context, value: String?): JsonObject {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolResults.error("missing_permission", "Do not disturb access is off. Ask the user to grant it in Marionette settings.")
        }
        val filter = when (value) {
            "on", "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            else -> return ToolResults.error("bad_arguments", "value must be on, off, priority or alarms.")
        }
        val previous = DND_NAMES[nm.currentInterruptionFilter]
        nm.setInterruptionFilter(filter)
        if (filter == NotificationManager.INTERRUPTION_FILTER_ALL && withTimeoutOrNull(DND_SETTLE_MILLIS) { while (nm.currentInterruptionFilter != filter) delay(50) } == null) {
            return ToolResults.error("user_dnd", "Do not disturb is still on: the user or a schedule turned it on. Tell the user to turn it off in quick settings or Settings > Modes.")
        }
        return ToolResults.ok("do_not_disturb" to DND_NAMES[filter], "previous" to previous)
    }

    private fun brightness(c: Context, value: String?): JsonObject {
        if (!Settings.System.canWrite(c)) {
            return ToolResults.error("missing_permission", "Changing brightness needs the modify system settings permission. Ask the user to grant it in Marionette settings.")
        }
        val r = c.contentResolver
        val previous = if (Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "auto"
            else (Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255).toString()
        if (value == "auto") {
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return ToolResults.ok("brightness" to "auto", "previous" to previous)
        }
        val percent = value?.trimEnd('%')?.toIntOrNull()?.coerceIn(0, 100) ?: return ToolResults.error("bad_arguments", "value must be 0-100 or auto.")
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (percent * 255 / 100).coerceAtLeast(1))
        return ToolResults.ok("brightness" to percent, "previous" to previous)
    }

    companion object {
        private val RELATIVE = Regex("[+-]\\d{1,3}%?")
        private const val DND_SETTLE_MILLIS = 1_000L
        val STREAMS = linkedMapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
        )
        private val RINGER_NAMES = mapOf(
            AudioManager.RINGER_MODE_NORMAL to "normal",
            AudioManager.RINGER_MODE_VIBRATE to "vibrate",
            AudioManager.RINGER_MODE_SILENT to "silent",
        )
        private val DND_NAMES = mapOf(
            NotificationManager.INTERRUPTION_FILTER_ALL to "off",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY to "priority",
            NotificationManager.INTERRUPTION_FILTER_ALARMS to "alarms",
            NotificationManager.INTERRUPTION_FILTER_NONE to "silence",
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN to "unknown",
        )
    }
}
