package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleAndEventMatchTest {
    @Test
    fun ranksMultiWordTitles() {
        assertEquals(1, TitleMatch.rank("Lucía Martínez", "lucia mar"))
        assertEquals(2, TitleMatch.rank("Grupo Familia García", "familia gar"))
        assertNull(TitleMatch.rank("Ana García", "garcia ana"))
        assertNull(TitleMatch.rank("Ana", "ana garcia"))
    }

    @Test
    fun ignoresAccentsCaseAndSymbols() {
        assertEquals(0, TitleMatch.rank("👨‍👩‍👧 FAMILIA", "familia"))
        assertEquals(0, TitleMatch.rank("José-Luis", "jose luis"))
    }

    @Test
    fun emptyTitleOrQueryNeverMatches() {
        assertNull(TitleMatch.rank(null, "ana"))
        assertNull(TitleMatch.rank("Ana", ""))
        assertNull(TitleMatch.rank("Ana", "¡!"))
    }

    @Test
    fun nonLatinTitlesMatchOnlyThemselves() {
        assertEquals(0, TitleMatch.rank("Мама", "мама"))
        assertNull(TitleMatch.rank("Мама", "Папа"))
    }

    @Test
    fun eventMatchSkipsShortWords() {
        assertTrue(EventMatch.matches("Reunión con el equipo", "reunion de equipo"))
        assertTrue(EventMatch.matches("Dentista", "el dentista"))
        assertTrue(EventMatch.matches("Yoga", "yo"))
        assertFalse(EventMatch.matches("Reunión de ventas", "reunion equipo"))
    }

    @Test
    fun eventMatchNeedsSomeWords() {
        assertFalse(EventMatch.matches("Cena", "¿?"))
        assertFalse(EventMatch.matches("Встреча", "Ужин"))
    }
}
