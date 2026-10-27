package dev.marionette.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WakeHitRecorderTest {
    @Test
    fun parsesRecordingNames() {
        val hit = WakeHitRecorder.parse(File("20261027-183737_wake_s0.120_v2.wav"))
        assertNotNull(hit)
        assertEquals("wake", hit!!.kind)
        assertEquals(0.120f, hit.score, 1e-6f)
        assertEquals("20261027-183737_wake_s0.120_v2", hit.key)
        assertTrue(WakeHitRecorder.parse(File("20261027-183737_wake_s0.120_v2_false.wav"))!!.falseWake)
        assertNull(WakeHitRecorder.parse(File("notes.wav")))
    }
}
