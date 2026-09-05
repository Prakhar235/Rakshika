package com.rakshika.app.geo

import com.google.android.gms.maps.model.LatLng
import com.rakshika.app.BuildConfig

/**
 * Config for the real Google Maps integration in the "Try it yourself" demo ride:
 * search (Places), routing (Directions), and the map itself (Maps SDK, via MAPS_API_KEY
 * in the manifest). Places/Directions/Geocoding are called as plain HTTP web services
 * (see PlacesSearch.kt / DirectionsRepository.kt / DeviceLocation.kt) using the same key.
 *
 * SETUP: paste your key into local.properties as MAPS_API_KEY=... (gitignored) — see
 * app/build.gradle.kts for how it's wired into BuildConfig + the manifest placeholder.
 * Enable on it: Maps SDK for Android, Places API, Directions API, Geocoding API.
 */
object MapsConfig {

    const val API_KEY: String = BuildConfig.MAPS_API_KEY

    /** True once a real key has been pasted in — used to skip network work otherwise. */
    val isConfigured: Boolean
        get() = API_KEY.isNotBlank() && API_KEY != "PASTE_YOUR_KEY_HERE"

    /** Fallback origin (central Bengaluru, MG Road) if device location is denied/unavailable. */
    val FALLBACK_ORIGIN = LatLng(12.9720, 77.5966)
}
