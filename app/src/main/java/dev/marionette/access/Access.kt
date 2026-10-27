package dev.marionette.access

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.marionette.notifications.MarionetteNotificationListener
import dev.marionette.screen.MarionetteAccessibilityService
import dev.marionette.settings.AppSettings
import dev.marionette.tools.Photos

enum class AccessKind { RUNTIME, SPECIAL, ROLE }

enum class Access(
    val kind: AccessKind,
    val essential: Boolean,
    val permissions: List<String> = emptyList(),
    val tools: Set<String> = emptySet(),
    val minSdk: Int = Build.VERSION_CODES.Q,
    val requires: Access? = null,
) {
    MICROPHONE(AccessKind.RUNTIME, essential = true, permissions = listOf(Manifest.permission.RECORD_AUDIO)),
    NOTIFICATIONS(
        AccessKind.RUNTIME,
        essential = true,
        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),
    ASSISTANT(AccessKind.ROLE, essential = true),
    BATTERY(AccessKind.SPECIAL, essential = true),
    LOCATION(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        tools = setOf("find_places", "navigate"),
    ),
    BACKGROUND_LOCATION(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        requires = LOCATION,
    ),
    CONTACTS(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        tools = setOf("search_contacts", "create_contact", "add_contact_info"),
    ),
    PHONE(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.CALL_PHONE),
        tools = setOf("call"),
    ),
    SMS(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS),
        tools = setOf("send_sms", "read_sms"),
    ),
    CALL_LOG(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.READ_CALL_LOG),
        tools = setOf("recent_calls"),
    ),
    CALENDAR(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        tools = setOf("list_events", "create_event", "update_event", "delete_event", "list_calendars"),
    ),
    PHOTOS(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Photos.permission, Manifest.permission.ACCESS_MEDIA_LOCATION),
        tools = setOf("list_photos", "share_photo"),
    ),
    NEARBY_DEVICES(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT),
        minSdk = Build.VERSION_CODES.S,
    ),
    LOCAL_NETWORK(
        AccessKind.RUNTIME,
        essential = false,
        permissions = listOf(PERMISSION_LOCAL_NETWORK),
        minSdk = 37,
    ),
    NOTIFICATION_ACCESS(
        AccessKind.SPECIAL,
        essential = false,
        tools = setOf(
            "read_notifications", "reply_notification", "notification_action", "dismiss_notification",
            "media_control", "stop_navigation",
        ),
    ),
    DO_NOT_DISTURB(AccessKind.SPECIAL, essential = false, tools = setOf("device_setting")),
    SYSTEM_SETTINGS(AccessKind.SPECIAL, essential = false, tools = setOf("device_setting")),
    SCREEN_CONTROL(AccessKind.SPECIAL, essential = false, tools = setOf("ui_read", "ui_act", "screen_capture")),
    ;

    fun isRelevant(context: Context, settings: AppSettings): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) return false
        return when (this) {
            PHONE, SMS, CALL_LOG -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            LOCAL_NETWORK -> LocalNetwork.isLocal(settings.baseUrl)
            else -> true
        }
    }

    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < minSdk) return true
        return when (this) {
            ASSISTANT -> context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
            BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            NOTIFICATION_ACCESS -> MarionetteNotificationListener.isEnabled(context)
            DO_NOT_DISTURB -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
            SYSTEM_SETTINGS -> Settings.System.canWrite(context)
            SCREEN_CONTROL -> MarionetteAccessibilityService.isEnabled(context)
            LOCATION -> permissions.any { granted(context, it) }
            else -> permissions.all { granted(context, it) }
        }
    }

    fun settingsIntent(context: Context): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (this) {
            ASSISTANT -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            NOTIFICATION_ACCESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MarionetteNotificationListener.component(context).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            DO_NOT_DISTURB -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            SYSTEM_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg)
            SCREEN_CONTROL -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> appDetails(context)
        }
    }

    companion object {
        fun appDetails(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

        fun relevant(context: Context, settings: AppSettings): List<Access> = entries.filter { it.isRelevant(context, settings) }

        fun missingEssentials(context: Context, settings: AppSettings): List<Access> =
            relevant(context, settings).filter { it.essential && !it.isGranted(context) }

        fun blocking(tool: String, context: Context, settings: AppSettings): List<Access> =
            relevant(context, settings).filter { tool in it.tools && !it.isGranted(context) }

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private const val PERMISSION_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
