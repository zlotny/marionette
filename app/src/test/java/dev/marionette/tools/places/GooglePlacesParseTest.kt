package dev.marionette.tools.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GooglePlacesParseTest {
    @Test
    fun readsRatingAndOpenNow() {
        val places = GooglePlacesClient.parse(
            """{"places":[
            {"displayName":{"text":"Farmacia Sol"},"formattedAddress":"Calle Mayor 1","location":{"latitude":40.4,"longitude":-3.7},
             "primaryType":"pharmacy","rating":4.6,"userRatingCount":212,"currentOpeningHours":{"openNow":true}},
            {"displayName":{"text":"Bar Pepe"},"location":{"latitude":40.5,"longitude":-3.6}}]}""",
        )
        assertEquals(2, places.size)
        assertEquals(4.6, places[0].rating!!, 0.001)
        assertEquals(212, places[0].ratingCount)
        assertEquals(true, places[0].openNow)
        assertNull(places[1].rating)
        assertNull(places[1].openNow)
    }
}
