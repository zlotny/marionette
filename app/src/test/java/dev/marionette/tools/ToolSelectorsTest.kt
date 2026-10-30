package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSelectorsTest {
    private val lucia = ContactPhone("Lucía Martínez", "+34600111222", "Mobile", starred = false, mobile = true)
    private val luciaWork = ContactPhone("Lucía Martínez", "981000000", "Work", starred = false)
    private val luciaPerez = ContactPhone("Lucía Pérez", "+34611222333", "Mobile", starred = false, mobile = true)
    private val pedro = ContactPhone("Pedro", "612345678", "Mobile", starred = false, mobile = true)

    @Test
    fun picksTheOnlyMatchingContact() {
        assertEquals(ContactPick.Found("Pedro", "612345678"), Contacts.choose(Contacts.scored(listOf(lucia, pedro), "pedro")))
    }

    @Test
    fun prefersTheMobileNumberOfOneContact() {
        assertEquals(ContactPick.Found("Lucía Martínez", "+34600111222", "Lucía Martínez (Mobile +34600111222)"), Contacts.choose(Contacts.scored(listOf(lucia, luciaWork), "lucia")))
    }

    @Test
    fun listsContactsWhenSeveralNamesMatch() {
        val pick = Contacts.choose(Contacts.scored(listOf(lucia, luciaPerez, pedro), "lucia"))
        assertEquals(ContactPick.Ambiguous(listOf("Lucía Martínez", "Lucía Pérez")), pick)
    }

    @Test
    fun exactNameBeatsLongerNames() {
        val mama = ContactPhone("Mamá", "600000001", "Mobile", starred = false, mobile = true)
        val mamaLuisa = ContactPhone("Mamá Luisa", "600000002", "Mobile", starred = false, mobile = true)
        assertEquals(ContactPick.Found("Mamá", "600000001"), Contacts.choose(Contacts.scored(listOf(mamaLuisa, mama), "mama")))
    }

    @Test
    fun reportsNoMatch() {
        assertEquals(ContactPick.NotFound, Contacts.choose(Contacts.scored(listOf(pedro), "juan")))
    }

    @Test
    fun tellsNumbersFromNames() {
        assertTrue(Contacts.isNumber("600 111 222"))
        assertTrue(Contacts.isNumber("+34600111222"))
        assertFalse(Contacts.isNumber("Lucía"))
        assertFalse(Contacts.isNumber("Juan 2"))
    }

    @Test
    fun buildsWeeklyRules() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", Recurrence.weekly(listOf("mon", "wed", "fri"), null))
        assertEquals("FREQ=WEEKLY;BYDAY=TU;UNTIL=20261231T235959Z", Recurrence.weekly(listOf("Tuesday"), "2026-12-31"))
        assertNull(Recurrence.weekly(listOf("someday"), null))
        assertEquals("P3600S", Recurrence.duration(3_600_000))
    }

    @Test
    fun matchesEventTitlesByWords() {
        assertTrue(EventMatch.matches("Cita dentista Dr. López", "dentista"))
        assertTrue(EventMatch.matches("Cena con Lucía", "cena lucia"))
        assertFalse(EventMatch.matches("Cena con Pedro", "cena lucia"))
    }

    @Test
    fun recognizesAddTimeButtons() {
        assertTrue(ClockTimers.isAdd("Add 1 min"))
        assertTrue(ClockTimers.isAdd("+1:00"))
        assertTrue(ClockTimers.isAdd("Añadir 1 min"))
        assertFalse(ClockTimers.isAdd("Pause"))
    }

    @Test
    fun ranksNotificationTitles() {
        assertEquals(0, TitleMatch.rank("Ana", "ana"))
        assertEquals(1, TitleMatch.rank("Ana García", "ana"))
        assertEquals(2, TitleMatch.rank("Familia Ana", "ana"))
        assertEquals(3, TitleMatch.rank("Juana", "ana"))
        assertEquals(null, TitleMatch.rank("Pedro", "ana"))
    }
}
