package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRankingTest {
    private fun phone(name: String, number: String, type: String = "Mobile", starred: Boolean = false, mobile: Boolean = type == "Mobile") =
        ContactPhone(name, number, type, starred, mobile)

    @Test
    fun matchesTheLastDigitsOfANumber() {
        val lucia = phone("Lucía Martínez", "+34600111222")
        val pedro = phone("Pedro", "612345678")
        assertEquals(ContactPick.Found("Lucía Martínez", "+34600111222"), Contacts.choose(Contacts.scored(listOf(lucia, pedro), "1222")))
        assertEquals(ContactPick.NotFound, Contacts.choose(Contacts.scored(listOf(lucia, pedro), "222")))
    }

    @Test
    fun treatsOneNumberInTwoFormatsAsOne() {
        val a = phone("Ana", "+34 600 111 222")
        val b = phone("Ana", "600111222")
        assertEquals(ContactPick.Found("Ana", "+34 600 111 222"), Contacts.choose(Contacts.scored(listOf(a, b), "ana")))
    }

    @Test
    fun asksWhenOneContactHasSeveralLandlines() {
        val home = phone("Ana", "911111111", type = "Home")
        val work = phone("Ana", "922222222", type = "Work")
        assertEquals(
            ContactPick.Ambiguous(listOf("Ana Home 911111111", "Ana Work 922222222")),
            Contacts.choose(Contacts.scored(listOf(home, work), "ana")),
        )
    }

    @Test
    fun wordPrefixBeatsSubstring() {
        val juana = phone("Juana", "600000001")
        val mariAna = phone("Mari Ana", "600000002")
        assertEquals(ContactPick.Found("Mari Ana", "600000002"), Contacts.choose(Contacts.scored(listOf(juana, mariAna), "Ana")))
    }

    @Test
    fun listsFavoritesFirstWhenAmbiguous() {
        val martinez = phone("Lucía Martínez", "600000001")
        val perez = phone("Lucía Pérez", "600000002", starred = true)
        assertEquals(
            ContactPick.Ambiguous(listOf("Lucía Pérez", "Lucía Martínez")),
            Contacts.choose(Contacts.scored(listOf(martinez, perez), "lucia")),
        )
    }

    @Test
    fun matchesNonLatinNames() {
        val ivan = phone("Иван", "600000001")
        val petr = phone("Пётр", "600000002")
        val nikos = phone("Νίκος", "600000003")
        assertEquals(ContactPick.Found("Иван", "600000001"), Contacts.choose(Contacts.scored(listOf(ivan, petr, nikos), "иван")))
        assertEquals(ContactPick.NotFound, Contacts.choose(Contacts.scored(listOf(ivan, petr), "Νίκος")))
    }

    @Test
    fun comparesNumbersAndSenders() {
        assertTrue(PhoneNumbers.same("0034600111222", "+34 600 111 222"))
        assertTrue(PhoneNumbers.same("112", "112"))
        assertFalse(PhoneNumbers.same("112", "113"))
        assertFalse(PhoneNumbers.same("Иван", "Пётр"))
    }
}
