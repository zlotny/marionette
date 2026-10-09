package dev.marionette.spike

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.UiModeManager
import android.app.role.RoleManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object DeviceState {
    fun isAssistant(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)

    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun battery(context: Context): BatterySample {
        val bm = context.getSystemService(BatteryManager::class.java)
        return BatterySample(
            System.currentTimeMillis(),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
        )
    }

    fun summary(context: Context): String {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val uiMode = context.getSystemService(UiModeManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH_TYPES }
            .joinToString { it.productName.toString() }
            .ifEmpty { "none" }
        return "locked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} secure=${keyguard.isDeviceSecure} " +
            "screenOn=${power.isInteractive} " +
            "car=${uiMode.currentModeType == Configuration.UI_MODE_TYPE_CAR} " +
            "bt=$outputs android=${Build.VERSION.RELEASE} model=${Build.MODEL}"
    }

    fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "remote_submix"
        else -> "type_$type"
    }

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
}
