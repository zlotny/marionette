package dev.marionette.ui.access

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assistant
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.marionette.R
import dev.marionette.access.Access
import dev.marionette.access.AccessKind
import dev.marionette.ui.components.SettingsGroup
import dev.marionette.ui.components.SettingsIcon
import dev.marionette.ui.components.SettingsRow

data class AccessPresentation(val icon: ImageVector, @StringRes val title: Int, @StringRes val body: Int)

val Access.presentation: AccessPresentation
    get() = when (this) {
        Access.MICROPHONE -> AccessPresentation(Icons.Rounded.Mic, R.string.access_microphone, R.string.access_microphone_body)
        Access.NOTIFICATIONS -> AccessPresentation(Icons.Rounded.Notifications, R.string.access_notifications, R.string.access_notifications_body)
        Access.ASSISTANT -> AccessPresentation(Icons.Rounded.Assistant, R.string.access_assistant, R.string.access_assistant_body)
        Access.BATTERY -> AccessPresentation(Icons.Rounded.BatteryChargingFull, R.string.access_battery, R.string.access_battery_body)
        Access.LOCATION -> AccessPresentation(Icons.Rounded.LocationOn, R.string.access_location, R.string.access_location_body)
        Access.BACKGROUND_LOCATION -> AccessPresentation(Icons.Rounded.MyLocation, R.string.access_background_location, R.string.access_background_location_body)
        Access.CONTACTS -> AccessPresentation(Icons.Rounded.Contacts, R.string.access_contacts, R.string.access_contacts_body)
        Access.PHONE -> AccessPresentation(Icons.Rounded.Call, R.string.access_phone, R.string.access_phone_body)
        Access.SMS -> AccessPresentation(Icons.Rounded.Sms, R.string.access_sms, R.string.access_sms_body)
        Access.CALENDAR -> AccessPresentation(Icons.Rounded.CalendarMonth, R.string.access_calendar, R.string.access_calendar_body)
        Access.NEARBY_DEVICES -> AccessPresentation(Icons.Rounded.Bluetooth, R.string.access_nearby, R.string.access_nearby_body)
        Access.LOCAL_NETWORK -> AccessPresentation(Icons.Rounded.Router, R.string.access_local_network, R.string.access_local_network_body)
        Access.NOTIFICATION_ACCESS -> AccessPresentation(Icons.Rounded.NotificationsActive, R.string.access_notification_access, R.string.access_notification_access_body)
        Access.DO_NOT_DISTURB -> AccessPresentation(Icons.Rounded.DoNotDisturbOn, R.string.access_dnd, R.string.access_dnd_body)
        Access.SYSTEM_SETTINGS -> AccessPresentation(Icons.Rounded.Tune, R.string.access_system_settings, R.string.access_system_settings_body)
        Access.SCREEN_CONTROL -> AccessPresentation(Icons.Rounded.TouchApp, R.string.access_screen_control, R.string.access_screen_control_body)
    }

@Composable
fun AccessList(title: String?, accesses: List<Access>, state: AccessState, modifier: Modifier = Modifier) {
    if (accesses.isEmpty()) return
    SettingsGroup(title, modifier) {
        accesses.forEach { access ->
            item { i, n -> AccessRow(i, n, access, state) }
        }
    }
}

@Composable
private fun AccessRow(index: Int, count: Int, access: Access, state: AccessState) {
    val p = access.presentation
    val granted = state.isGranted(access)
    val ready = state.isReady(access)
    val colors = MaterialTheme.colorScheme
    val supporting = if (!ready) {
        stringResource(R.string.access_requires, stringResource(access.requires!!.presentation.title))
    } else {
        stringResource(p.body)
    }
    SettingsRow(
        index, count,
        headline = stringResource(p.title),
        supporting = supporting,
        leading = {
            if (granted) SettingsIcon(p.icon) else SettingsIcon(p.icon, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        },
        trailing = {
            if (granted) {
                Icon(Icons.Rounded.CheckCircle, stringResource(R.string.access_granted), tint = colors.primary)
            } else {
                FilledTonalButton(onClick = { state.request(access) }, enabled = ready) {
                    Text(stringResource(if (access.kind == AccessKind.RUNTIME) R.string.access_allow else R.string.access_open))
                }
            }
        },
        enabled = ready,
        onClick = { if (granted) state.openSettings(access) else state.request(access) },
    )
}
