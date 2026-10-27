package dev.marionette.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Attachment
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.ui.graphics.vector.ImageVector
import dev.marionette.R

data class ToolPresentation(
    val icon: ImageVector,
    @StringRes val label: Int,
    @StringRes val description: Int = R.string.tool_generic_desc,
    @StringRes val activity: Int = R.string.activity_generic,
)

object ToolPresentations {
    private val map = mapOf(
        "web_search" to ToolPresentation(Icons.Rounded.Search, R.string.tool_web_search),
        "web_fetch" to ToolPresentation(Icons.Rounded.Language, R.string.tool_web_fetch),
        "find_places" to ToolPresentation(Icons.Rounded.Place, R.string.tool_find_places, R.string.tool_find_places_desc, R.string.activity_find_places),
        "navigate" to ToolPresentation(Icons.Rounded.Directions, R.string.tool_navigate, R.string.tool_navigate_desc, R.string.activity_navigate),
        "stop_navigation" to ToolPresentation(Icons.Rounded.Stop, R.string.tool_stop_navigation, R.string.tool_stop_navigation_desc, R.string.activity_stop_navigation),
        "set_timer" to ToolPresentation(Icons.Rounded.Timer, R.string.tool_set_timer, R.string.tool_set_timer_desc, R.string.activity_set_timer),
        "set_alarm" to ToolPresentation(Icons.Rounded.Alarm, R.string.tool_set_alarm, R.string.tool_set_alarm_desc, R.string.activity_set_alarm),
        "call" to ToolPresentation(Icons.Rounded.Call, R.string.tool_call, R.string.tool_call_desc, R.string.activity_call),
        "send_sms" to ToolPresentation(Icons.AutoMirrored.Rounded.Message, R.string.tool_send_sms, R.string.tool_send_sms_desc, R.string.activity_send_sms),
        "search_contacts" to ToolPresentation(Icons.Rounded.Contacts, R.string.tool_search_contacts, R.string.tool_search_contacts_desc, R.string.activity_search_contacts),
        "read_notifications" to ToolPresentation(Icons.Rounded.Notifications, R.string.tool_read_notifications, R.string.tool_read_notifications_desc, R.string.activity_read_notifications),
        "reply_notification" to ToolPresentation(Icons.AutoMirrored.Rounded.Reply, R.string.tool_reply_notification, R.string.tool_reply_notification_desc, R.string.activity_reply_notification),
        "media_control" to ToolPresentation(Icons.Rounded.MusicNote, R.string.tool_media_control, R.string.tool_media_control_desc, R.string.activity_media_control),
        "create_event" to ToolPresentation(Icons.Rounded.CalendarMonth, R.string.tool_create_event, R.string.tool_create_event_desc, R.string.activity_create_event),
        "list_events" to ToolPresentation(Icons.Rounded.CalendarMonth, R.string.tool_list_events, R.string.tool_list_events_desc, R.string.activity_list_events),
        "device_setting" to ToolPresentation(Icons.Rounded.Tune, R.string.tool_device_setting, R.string.tool_device_setting_desc, R.string.activity_device_setting),
        "open_app" to ToolPresentation(Icons.Rounded.Apps, R.string.tool_open_app, R.string.tool_open_app_desc, R.string.activity_open_app),
        "ui_read" to ToolPresentation(Icons.Rounded.Visibility, R.string.tool_ui_read, R.string.tool_ui_read_desc, R.string.activity_ui_read),
        "ui_act" to ToolPresentation(Icons.Rounded.TouchApp, R.string.tool_ui_act, R.string.tool_ui_act_desc, R.string.activity_ui_act),
        "list_apps" to ToolPresentation(Icons.Rounded.Apps, R.string.tool_list_apps, R.string.tool_list_apps_desc, R.string.activity_list_apps),
        "open_url" to ToolPresentation(Icons.Rounded.Link, R.string.tool_open_url, R.string.tool_open_url_desc, R.string.activity_open_url),
        "show_in_chat" to ToolPresentation(Icons.Rounded.Attachment, R.string.tool_show_in_chat, R.string.tool_show_in_chat_desc, R.string.activity_show_in_chat),
        "share_text" to ToolPresentation(Icons.Rounded.Share, R.string.tool_share_text, R.string.tool_share_text_desc, R.string.activity_share_text),
        "open_settings" to ToolPresentation(Icons.Rounded.Settings, R.string.tool_open_settings, R.string.tool_open_settings_desc, R.string.activity_open_settings),
        "notification_action" to ToolPresentation(Icons.Rounded.Notifications, R.string.tool_notification_action, R.string.tool_notification_action_desc, R.string.activity_notification_action),
        "dismiss_notification" to ToolPresentation(Icons.Rounded.ClearAll, R.string.tool_dismiss_notification, R.string.tool_dismiss_notification_desc, R.string.activity_dismiss_notification),
        "play_media" to ToolPresentation(Icons.Rounded.PlayArrow, R.string.tool_play_media, R.string.tool_play_media_desc, R.string.activity_play_media),
        "update_event" to ToolPresentation(Icons.Rounded.EditCalendar, R.string.tool_update_event, R.string.tool_update_event_desc, R.string.activity_update_event),
        "delete_event" to ToolPresentation(Icons.Rounded.EventBusy, R.string.tool_delete_event, R.string.tool_delete_event_desc, R.string.activity_delete_event),
        "list_calendars" to ToolPresentation(Icons.Rounded.CalendarMonth, R.string.tool_list_calendars, R.string.tool_list_calendars_desc, R.string.activity_list_calendars),
        "next_alarm" to ToolPresentation(Icons.Rounded.Alarm, R.string.tool_next_alarm, R.string.tool_next_alarm_desc, R.string.activity_next_alarm),
        "dismiss_alarm" to ToolPresentation(Icons.Rounded.AlarmOff, R.string.tool_dismiss_alarm, R.string.tool_dismiss_alarm_desc, R.string.activity_dismiss_alarm),
        "snooze_alarm" to ToolPresentation(Icons.Rounded.Snooze, R.string.tool_snooze_alarm, R.string.tool_snooze_alarm_desc, R.string.activity_snooze_alarm),
        "dismiss_timer" to ToolPresentation(Icons.Rounded.TimerOff, R.string.tool_dismiss_timer, R.string.tool_dismiss_timer_desc, R.string.activity_dismiss_timer),
        "read_sms" to ToolPresentation(Icons.Rounded.Sms, R.string.tool_read_sms, R.string.tool_read_sms_desc, R.string.activity_read_sms),
        "recent_calls" to ToolPresentation(Icons.Rounded.History, R.string.tool_recent_calls, R.string.tool_recent_calls_desc, R.string.activity_recent_calls),
        "create_contact" to ToolPresentation(Icons.Rounded.PersonAdd, R.string.tool_create_contact, R.string.tool_create_contact_desc, R.string.activity_create_contact),
        "add_contact_info" to ToolPresentation(Icons.Rounded.Contacts, R.string.tool_add_contact_info, R.string.tool_add_contact_info_desc, R.string.activity_add_contact_info),
        "list_photos" to ToolPresentation(Icons.Rounded.PhotoLibrary, R.string.tool_list_photos, R.string.tool_list_photos_desc, R.string.activity_list_photos),
        "share_photo" to ToolPresentation(Icons.Rounded.Share, R.string.tool_share_photo, R.string.tool_share_photo_desc, R.string.activity_share_photo),
        "screen_capture" to ToolPresentation(Icons.Rounded.Screenshot, R.string.tool_screen_capture, R.string.tool_screen_capture_desc, R.string.activity_screen_capture),
        "copy_text" to ToolPresentation(Icons.Rounded.ContentCopy, R.string.tool_copy_text, R.string.tool_copy_text_desc, R.string.activity_copy_text),
    )

    fun of(tool: String): ToolPresentation = map[tool] ?: ToolPresentation(Icons.Rounded.Build, R.string.tool_generic)
}
