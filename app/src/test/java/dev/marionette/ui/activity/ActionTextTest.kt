package dev.marionette.ui.activity

import dev.marionette.core.parseObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionTextTest {
    @Test
    fun successIsDone() {
        assertEquals(Outcome.DONE, ActionText.outcome(parseObject("""{"ok":true,"status":"opened"}""")))
    }

    @Test
    fun waitingForUnlockIsNotFailure() {
        assertEquals(Outcome.WAITING_UNLOCK, ActionText.outcome(parseObject("""{"ok":true,"status":"waiting_for_unlock"}""")))
    }

    @Test
    fun errorCodesMapToOutcomes() {
        assertEquals(Outcome.DECLINED, ActionText.outcome(parseObject("""{"ok":false,"error":"declined_by_user"}""")))
        assertEquals(Outcome.PERMISSION, ActionText.outcome(parseObject("""{"ok":false,"error":"missing_permission"}""")))
        assertEquals(Outcome.NOT_FOUND, ActionText.outcome(parseObject("""{"ok":false,"error":"not_installed"}""")))
        assertEquals(Outcome.FAILED, ActionText.outcome(parseObject("""{"ok":false,"error":"something_new"}""")))
    }

    @Test
    fun readableFlattensNestedJson() {
        val json = parseObject("""{"ok":true,"place_name":"Mercadona","results":[{"name":"A","distance_m":120}],"tags":[]}""")
        assertEquals(
            "Place name: Mercadona\nResults:\n  •\n    Name: A\n    Distance m: 120\nTags: -",
            ActionText.readable(json, setOf("ok")),
        )
    }
}
