package dev.marionette.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.ui.graphics.vector.ImageVector
import dev.marionette.R

data class ToolPresentation(val icon: ImageVector, @StringRes val label: Int)

object ToolPresentations {
    private val map = mapOf(
        "find_places" to ToolPresentation(Icons.Rounded.Place, R.string.tool_find_places),
        "navigate" to ToolPresentation(Icons.Rounded.Directions, R.string.tool_navigate),
        "stop_navigation" to ToolPresentation(Icons.Rounded.Stop, R.string.tool_stop_navigation),
        "set_timer" to ToolPresentation(Icons.Rounded.Timer, R.string.tool_set_timer),
        "set_alarm" to ToolPresentation(Icons.Rounded.Alarm, R.string.tool_set_alarm),
        "call" to ToolPresentation(Icons.Rounded.Call, R.string.tool_call),
        "send_sms" to ToolPresentation(Icons.AutoMirrored.Rounded.Message, R.string.tool_send_sms),
        "search_contacts" to ToolPresentation(Icons.Rounded.Contacts, R.string.tool_search_contacts),
        "read_notifications" to ToolPresentation(Icons.Rounded.Notifications, R.string.tool_read_notifications),
        "reply_notification" to ToolPresentation(Icons.AutoMirrored.Rounded.Reply, R.string.tool_reply_notification),
        "media_control" to ToolPresentation(Icons.Rounded.MusicNote, R.string.tool_media_control),
        "create_event" to ToolPresentation(Icons.Rounded.CalendarMonth, R.string.tool_create_event),
        "list_events" to ToolPresentation(Icons.Rounded.CalendarMonth, R.string.tool_list_events),
        "device_setting" to ToolPresentation(Icons.Rounded.Tune, R.string.tool_device_setting),
        "open_app" to ToolPresentation(Icons.Rounded.Apps, R.string.tool_open_app),
        "ui_read" to ToolPresentation(Icons.Rounded.Visibility, R.string.tool_ui_read),
        "ui_act" to ToolPresentation(Icons.Rounded.TouchApp, R.string.tool_ui_act),
        "list_apps" to ToolPresentation(Icons.Rounded.Apps, R.string.tool_list_apps),
        "open_url" to ToolPresentation(Icons.Rounded.Link, R.string.tool_open_url),
        "share_text" to ToolPresentation(Icons.Rounded.Share, R.string.tool_share_text),
        "open_settings" to ToolPresentation(Icons.Rounded.Settings, R.string.tool_open_settings),
        "notification_action" to ToolPresentation(Icons.Rounded.Notifications, R.string.tool_notification_action),
        "dismiss_notification" to ToolPresentation(Icons.Rounded.ClearAll, R.string.tool_dismiss_notification),
        "play_media" to ToolPresentation(Icons.Rounded.PlayArrow, R.string.tool_play_media),
    )

    fun of(tool: String): ToolPresentation = map[tool] ?: ToolPresentation(Icons.Rounded.Build, R.string.tool_generic)
}
