package com.rakshika.app.geo

import com.google.android.gms.maps.model.LatLng
import java.net.URLEncoder

/** One real autocomplete result from the Places web service. */
data class PlaceSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String
)

/**
 * Real destination search for the demo ride, via the Places Autocomplete + Place Details
 * web services (no Places SDK client dependency needed) — see MapsConfig for the key/setup.
 */
object PlacesSearch {

    suspend fun autocomplete(query: String, sessionToken: String, bias: LatLng?): List<PlaceSuggestion> {
        if (!MapsConfig.isConfigured || query.isBlank()) return emptyList()
        val locationBias = bias?.let { "&location=${it.latitude},${it.longitude}&radius=50000" } ?: ""
        val url = "https://maps.googleapis.com/maps/api/place/autocomplete/json" +
            "?input=${URLEncoder.encode(query, "UTF-8")}" +
            "&sessiontoken=$sessionToken" +
            locationBias +
            "&key=${MapsConfig.API_KEY}"

        val json = GeoHttp.getJson(url) ?: return emptyList()
        val predictions = json.optJSONArray("predictions") ?: return emptyList()
        return buildList {
            for (i in 0 until predictions.length()) {
                val p = predictions.getJSONObject(i)
                val structured = p.optJSONObject("structured_formatting")
                add(
                    PlaceSuggestion(
                        placeId = p.getString("place_id"),
                        primaryText = structured?.optString("main_text") ?: p.optString("description"),
                        secondaryText = structured?.optString("secondary_text").orEmpty()
                    )
                )
            }
        }
    }

    suspend fun fetchLatLng(placeId: String, sessionToken: String): LatLng? {
        if (!MapsConfig.isConfigured) return null
        val url = "https://maps.googleapis.com/maps/api/place/details/json" +
            "?place_id=$placeId" +
            "&fields=geometry" +
            "&sessiontoken=$sessionToken" +
            "&key=${MapsConfig.API_KEY}"

        val json = GeoHttp.getJson(url) ?: return null
        val location = json.optJSONObject("result")
            ?.optJSONObject("geometry")
            ?.optJSONObject("location") ?: return null
        return LatLng(location.getDouble("lat"), location.getDouble("lng"))
    }
}
