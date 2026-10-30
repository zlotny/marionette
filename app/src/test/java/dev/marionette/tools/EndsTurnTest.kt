package dev.marionette.tools

import dev.marionette.core.jsonOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndsTurnTest {
    @Test
    fun quickActionsEndTheTurnOnSuccess() {
        assertTrue(SetTimerTool().endsTurn(jsonOf("seconds" to 600), ToolResults.ok("seconds" to 600)))
        assertTrue(DeviceSettingTool().endsTurn(jsonOf("setting" to "flashlight", "value" to "on"), ToolResults.ok("flashlight" to "on")))
        assertTrue(NavigateTool().endsTurn(jsonOf("destination" to "Vigo"), ToolResults.ok("status" to "navigating")))
    }

    @Test
    fun failuresAndPendingStatesWaitForTheModel() {
        assertFalse(SetTimerTool().endsTurn(jsonOf("seconds" to 0), ToolResults.error("bad_arguments")))
        assertFalse(NavigateTool().endsTurn(jsonOf("destination" to "Vigo"), ToolResults.ok("status" to "waiting_for_unlock")))
        assertFalse(SendMessageTool().endsTurn(jsonOf("to" to "Lucía"), ToolResults.ok("status" to "typed")))
    }

    @Test
    fun readsWaitForTheModel() {
        assertFalse(MediaControlTool().endsTurn(jsonOf("action" to "status"), ToolResults.ok("state" to "playing")))
        assertFalse(TimersTool().endsTurn(jsonOf("action" to "list"), ToolResults.ok("count" to 1)))
        assertFalse(ReadNotificationsTool().endsTurn(jsonOf(), ToolResults.ok("count" to 0)))
        assertFalse(UiActTool().endsTurn(jsonOf("action" to "tap"), ToolResults.ok()))
    }
}
