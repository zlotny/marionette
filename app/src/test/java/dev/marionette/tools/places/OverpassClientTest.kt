package dev.marionette.tools.places

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassClientTest {
    private val center = LatLng(43.1, -9.0)

    @Test
    fun matchesCuisineAndDietForOneWord() {
        val query = OverpassClient.buildQuery(center, 1000, "Vegan", PlaceCategory.RESTAURANT)
        assertTrue(query.contains("nwr[amenity=restaurant][\"cuisine\"~\"Vegan\",i]"))
        assertTrue(query.contains("nwr[amenity=restaurant][\"diet:vegan\"~\"^(yes|only)$\"]"))
    }

    @Test
    fun skipsDietForNamesWithSpacesOrSymbols() {
        assertFalse(OverpassClient.buildQuery(center, 1000, "Burger King", null).contains("diet:"))
        assertFalse(OverpassClient.buildQuery(center, 1000, "McDonald's", null).contains("diet:"))
    }
}
