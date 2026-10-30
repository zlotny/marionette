package dev.marionette.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class FoldTest {
    @Test
    fun dropsAccentsCaseAndPunctuation() {
        assertEquals("lucia maria", InstalledApps.fold("Lucía-María"))
        assertEquals("nandu", InstalledApps.fold("  ¡Ñandú! "))
        assertEquals("cafe 24 7", InstalledApps.fold("Café 24/7"))
    }

    @Test
    fun keepsNonLatinLetters() {
        assertEquals("иван", InstalledApps.fold("Иван"))
        assertEquals("νικος", InstalledApps.fold("Νίκος"))
        assertEquals("微信", InstalledApps.fold("微信"))
    }
}
