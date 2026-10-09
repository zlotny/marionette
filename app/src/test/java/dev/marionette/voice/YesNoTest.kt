package dev.marionette.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class YesNoTest {
    @Test
    fun parsesAffirmativeAnswers() {
        listOf("yes", "Yeah.", "go ahead", "Sí", "si, envíalo", "Vale!", "claro que sí", "por supuesto").forEach {
            assertEquals(it, true, YesNo.parse(it))
        }
    }

    @Test
    fun parsesNegativeAnswers() {
        listOf("no", "No, gracias", "cancel", "mejor no", "déjalo", "don't send it", "never mind").forEach {
            assertEquals(it, false, YesNo.parse(it))
        }
    }

    @Test
    fun negativeWinsOverPositive() {
        assertEquals(false, YesNo.parse("no, mejor no lo envíes"))
    }

    @Test
    fun returnsNullWhenUnclear() {
        listOf("", "   ", "what time is it", "quizás mañana").forEach {
            assertEquals(it, null, YesNo.parse(it))
        }
    }
}
