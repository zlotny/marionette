package dev.marionette.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class SilenceGateTest {
    private val rate = 16_000
    private val hop = 1_280
    private val random = Random(5)

    private fun amplitude(dbfs: Float) = 10f.pow(dbfs / 20) * 32768f

    private fun noise(dbfs: Float): FloatArray {
        val a = amplitude(dbfs) * 1.732f
        return FloatArray(hop) { a * (random.nextFloat() * 2 - 1) }
    }

    private fun tone(dbfs: Float, floorDbfs: Float): FloatArray {
        val a = amplitude(dbfs) * 1.414f
        val n = noise(floorDbfs)
        return FloatArray(hop) { i -> a * sin(2 * PI * 220 * i / rate).toFloat() + n[i] }
    }

    private fun openHops(hops: List<FloatArray>): Int {
        val gate = SilenceGate(hop.toFloat() / rate)
        return hops.count { !gate.isQuiet(it, hop) }
    }

    @Test
    fun quietRoomStaysClosed() {
        assertEquals(0, openHops(List(250) { noise(-74f) }))
    }

    @Test
    fun faintSpeechAboveTheFloorOpens() {
        val hops = List(25) { noise(-74f) } + List(10) { tone(-62f, -74f) }
        assertEquals(10, openHops(hops))
    }

    @Test
    fun loudSteadyNoiseStaysOpen() {
        assertEquals(250, openHops(List(250) { noise(-50f) }))
    }

    @Test
    fun slowlyRisingNoiseBelowTheLimitStaysClosed() {
        val hops = List(250) { noise(-80f + it * 0.06f) }
        assertEquals(0, openHops(hops))
    }
}
