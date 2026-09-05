package com.rakshika.app.ride

import com.rakshika.app.geo.MapsConfig
import com.rakshika.app.geo.PlaceSuggestion

/**
 * A curated, realistic-looking directory around Sector 75, Noida — used as the ride's
 * destination search whenever the live Places Autocomplete call comes back empty (no key,
 * API not enabled on the project, or no connectivity). Real place names and plausible real
 * coordinates, so the demo still looks like a real search even with no network at all.
 *
 * "local:" place IDs mark an entry as belonging to this directory (never sent to Google) —
 * RideViewModel checks for that prefix before trying a live Place Details lookup.
 */
object NearbyPlaces {

    val FALLBACK_ORIGIN = Place(
        name = "Sector 75, Noida",
        area = "Near the Sector 75–76 boundary, Noida",
        lat = MapsConfig.FALLBACK_ORIGIN.latitude,
        lng = MapsConfig.FALLBACK_ORIGIN.longitude
    )

    val NEARBY: List<Place> = listOf(
        Place("Sector 76 Metro Station", "Aqua Line · Sector 76, Noida", 28.5508, 77.3635),
        Place("Jaypee Wish Town", "Sector 78, Noida", 28.5647, 77.3533),
        Place("Sector 75 Market", "Sector 75, Noida", 28.5504, 77.3512),
        Place("Sector 50 Metro Station", "Aqua Line · Sector 50, Noida", 28.5553, 77.3376),
        Place("Worldmark 65", "Sector 65, Noida", 28.5450, 77.3676),
        Place("Sector 78 Community Centre", "Sector 78, Noida", 28.5607, 77.3625)
    )

    private fun placeId(place: Place) = "local:${place.name}"

    private fun toSuggestion(place: Place) = PlaceSuggestion(
        placeId = placeId(place),
        primaryText = place.name,
        secondaryText = place.area
    )

    /** Shown before the rider has typed anything — mirrors what a "nearby" tab would show. */
    fun defaultSuggestions(): List<PlaceSuggestion> = NEARBY.take(5).map(::toSuggestion)

    fun search(query: String): List<PlaceSuggestion> = NEARBY
        .filter { it.name.contains(query, ignoreCase = true) || it.area.contains(query, ignoreCase = true) }
        .map(::toSuggestion)

    /** Resolves a "local:" suggestion straight to its real coordinate — no network round-trip. */
    fun byPlaceId(id: String): Place? = NEARBY.find { placeId(it) == id }
}
