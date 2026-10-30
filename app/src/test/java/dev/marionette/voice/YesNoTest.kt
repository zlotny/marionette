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
        listOf("vale, no", "sí, no", "sí, cancela", "ok no").forEach { assertEquals(it, false, YesNo.parse(it)) }
    }

    @Test
    fun returnsNullWhenUnclear() {
        listOf("", "   ", "what time is it", "quizás mañana").forEach {
            assertEquals(it, null, YesNo.parse(it))
        }
    }

    @Test
    fun tellsBareAnswersFromCorrections() {
        assertEquals(true, YesNo.isBare("sí"))
        assertEquals(true, YesNo.isBare("vale, envíalo"))
        assertEquals(false, YesNo.isBare("sí, y dile que traigo pan"))
        assertEquals(true, YesNo.changesRequest("no, a las 9"))
        assertEquals(true, YesNo.changesRequest("no, mejor dile que mañana"))
        assertEquals(false, YesNo.changesRequest("no, mejor no lo envíes"))
    }

    @Test
    fun namesAreNotBareAnswers() {
        assertEquals(false, YesNo.isBare("llama Pedro"))
        assertEquals(true, YesNo.isBare("sí, sí"))
    }

    @Test
    fun politeYesIsBare() {
        listOf("sí, por favor", "sí, gracias", "vale, mándaselo", "sí, llámale").forEach {
            assertEquals(it, true, YesNo.parse(it))
            assertEquals(it, true, YesNo.isBare(it))
        }
    }

    @Test
    fun shortRedirectsAreCorrections() {
        listOf("no, a su móvil", "no, por SMS").forEach { assertEquals(it, true, YesNo.changesRequest(it)) }
        assertEquals(true, YesNo.isCorrection("al móvil"))
        assertEquals(false, YesNo.isCorrection("sí, gracias"))
    }

    @Test
    fun noHayProblemaIsYes() {
        assertEquals(true, YesNo.parse("Sí, no hay problema"))
        assertEquals(true, YesNo.parse("«Sí»…"))
        assertEquals(false, YesNo.parse("Don't send it"))
    }
}
