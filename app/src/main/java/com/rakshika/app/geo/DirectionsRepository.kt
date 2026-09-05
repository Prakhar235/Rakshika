package com.rakshika.app.geo

import com.google.android.gms.maps.model.LatLng
import java.net.URLEncoder

/** One real route alternative between two points. */
data class GeoRoute(
    val points: List<LatLng>,
    val minutes: Int,
    val meters: Int,
    val summary: String
)

/**
 * Real walking routes between two real coordinates, via the Directions web service
 * (mode=walking&alternatives=true) — see MapsConfig for the key/setup.
 */
object DirectionsRepository {

    suspend fun fetchRoutes(origin: LatLng, destination: LatLng): List<GeoRoute> {
        if (!MapsConfig.isConfigured) return emptyList()
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${origin.latitude},${origin.longitude}" +
            "&destination=${destination.latitude},${destination.longitude}" +
            "&mode=walking&alternatives=true" +
            "&key=${URLEncoder.encode(MapsConfig.API_KEY, "UTF-8")}"

        val json = GeoHttp.getJson(url) ?: return emptyList()
        val routes = json.optJSONArray("routes") ?: return emptyList()
        return buildList {
            for (i in 0 until routes.length()) {
                val route = routes.getJSONObject(i)
                val legs = route.optJSONArray("legs") ?: continue
                var seconds = 0
                var meters = 0
                for (j in 0 until legs.length()) {
                    val leg = legs.getJSONObject(j)
                    seconds += leg.getJSONObject("duration").getInt("value")
                    meters += leg.getJSONObject("distance").getInt("value")
                }
                val polyline = route.optJSONObject("overview_polyline")?.optString("points")
                if (polyline.isNullOrBlank()) continue
                add(
                    GeoRoute(
                        points = PolylineDecoder.decode(polyline),
                        minutes = (seconds / 60.0).let { if (it < 1) 1 else Math.round(it).toInt() },
                        meters = meters,
                        summary = route.optString("summary").ifBlank { "Route ${i + 1}" }
                    )
                )
            }
        }
    }
}
