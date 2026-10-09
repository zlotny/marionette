package dev.marionette.tools

import dev.marionette.tools.places.FindPlacesTool

object Tools {
    fun all(): List<Tool> = listOf(
        NavigateTool(),
        StopNavigationTool(),
        FindPlacesTool(),
        SetTimerTool(),
        SetAlarmTool(),
        CreateEventTool(),
        ListEventsTool(),
        SearchContactsTool(),
        CallTool(),
        SendSmsTool(),
        ReadNotificationsTool(),
        ReplyNotificationTool(),
        NotificationActionTool(),
        DismissNotificationTool(),
        MediaControlTool(),
        PlayMediaTool(),
        ListAppsTool(),
        OpenAppTool(),
        OpenUrlTool(),
        ShareTextTool(),
        OpenSettingsTool(),
        DeviceSettingTool(),
        UiReadTool(),
        UiActTool(),
    )
}
