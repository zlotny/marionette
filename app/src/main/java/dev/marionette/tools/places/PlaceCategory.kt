package dev.marionette.tools.places

enum class PlaceCategory(val key: String, val osmFilters: List<String>, val phrase: String) {
    FUEL("fuel", listOf("[amenity=fuel]"), "fuel"),
    EV_CHARGING("ev_charging", listOf("[amenity=charging_station]"), "charging station"),
    PARKING("parking", listOf("[amenity=parking]", "[amenity=motorcycle_parking]"), "parking"),
    MOTORCYCLE_PARKING("motorcycle_parking", listOf("[amenity=motorcycle_parking]"), "motorcycle parking"),
    RESTAURANT("restaurant", listOf("[amenity=restaurant]"), "restaurant"),
    FAST_FOOD("fast_food", listOf("[amenity=fast_food]"), "fast food"),
    CAFE("cafe", listOf("[amenity=cafe]"), "cafe"),
    BAR("bar", listOf("[amenity=bar]", "[amenity=pub]"), "bar"),
    SUPERMARKET("supermarket", listOf("[shop=supermarket]", "[shop=convenience]"), "supermarket"),
    PHARMACY("pharmacy", listOf("[amenity=pharmacy]"), "pharmacy"),
    HOSPITAL("hospital", listOf("[amenity=hospital]", "[amenity=clinic]"), "hospital"),
    ATM("atm", listOf("[amenity=atm]", "[amenity=bank]"), "atm"),
    HOTEL("hotel", listOf("[tourism=hotel]", "[tourism=guest_house]", "[tourism=hostel]"), "hotel"),
    TOILETS("toilets", listOf("[amenity=toilets]"), "toilets"),
    POLICE("police", listOf("[amenity=police]"), "police"),
    CAR_REPAIR("car_repair", listOf("[shop=car_repair]"), "car repair"),
    MOTORCYCLE_SHOP("motorcycle_shop", listOf("[shop=motorcycle]", "[shop=motorcycle_repair]"), "motorcycle shop"),
    TYRES("tyres", listOf("[shop=tyres]"), "tyres"),
    VIEWPOINT("viewpoint", listOf("[tourism=viewpoint]"), "viewpoint"),
    CAMPSITE("campsite", listOf("[tourism=camp_site]"), "camp site"),
    ;

    val photonTags: List<String> get() = osmFilters.map { it.trim('[', ']').replace('=', ':') }

    companion object {
        fun of(key: String?): PlaceCategory? = entries.firstOrNull { it.key == key }
        val keys = entries.map { it.key }
    }
}
