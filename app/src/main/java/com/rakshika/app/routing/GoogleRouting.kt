package com.rakshika.app.routing

import android.content.Context
import android.util.Log
import com.rakshika.app.BuildConfig
import com.rakshika.app.net.GoogleApiHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One real, road-following route. */
data class GeoRoute(
    /** `[lat, lng]` pairs in travel order. */
    val points: List<DoubleArray>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Major named roads this route follows, straight from Directions' own `summary` field
     *  (e.g. "MG Road, Residency Road") — null when Google didn't name one, which is common
     *  for short legs that stay on unnamed local streets. */
    val summary: String? = null,
    /** Total turn-by-turn steps across all legs — a real directness/complexity signal from the same response. */
    val stepCount: Int = 0
) {
    val avgSpeedKmh: Double
        get() = if (durationSeconds <= 0) 0.0 else (distanceMeters / 1000.0) / (durationSeconds / 3600.0)
}

/** The two corridors the rest of the app reasons about, now backed by real routes when routing found them. */
data class RoutingResult(val mainRoad: GeoRoute?, val backLane: GeoRoute?)

/**
 * Real road routing via the Google Directions API. Requires MAPS_API_KEY (see local.properties)
 * with the Directions API enabled and billing on.
 *
 * The API doesn't label which alternative is "the main road" vs "a back lane", so corridor
 * classification is a heuristic: among the alternative routes, the one with the highest average
 * speed (distance ÷ duration) is treated as the "main road" route — arterial roads sustain
 * faster average speeds than residential back lanes — and the slowest as the "back lane" route.
 */
object GoogleRouting {
    private const val TAG = "GoogleRouting"
    private const val URL_STR = "https://maps.googleapis.com/maps/api/directions/json"
    private const val MAIN_ROAD_SPEED_KMH = 25.0

    /** Returns null when routing couldn't be reached or found no route at all — check Logcat tag "GoogleRouting" why. */
    suspend fun findRoutes(context: Context, originLat: Double, originLng: Double, destLat: Double, destLng: Double): RoutingResult? =
        withContext(Dispatchers.IO) {
            val url = "$URL_STR?origin=$originLat,$originLng&destination=$destLat,$destLng" +
                "&alternatives=true&key=${BuildConfig.MAPS_API_KEY}"

            val body = get(url, context) ?: return@withContext null
            val routes = runCatching { parse(body) }
                .onFailure { Log.w(TAG, "Failed to parse Directions response", it) }
                .getOrNull()
            if (routes.isNullOrEmpty()) {
                Log.w(TAG, "Directions returned no usable routes for $originLat,$originLng -> $destLat,$destLng")
                return@withContext null
            }

            if (routes.size == 1) {
                val only = routes.first()
                Log.i(TAG, "1 route found, ${only.distanceMeters.toInt()}m / ${only.avgSpeedKmh.toInt()}km/h avg")
                return@withContext if (only.avgSpeedKmh >= MAIN_ROAD_SPEED_KMH) {
                    RoutingResult(mainRoad = only, backLane = null)
                } else {
                    RoutingResult(mainRoad = null, backLane = only)
                }
            }

            val sorted = routes.sortedByDescending { it.avgSpeedKmh }
            Log.i(TAG, "${routes.size} routes found, using fastest-avg as main road, slowest as back lane")
            RoutingResult(mainRoad = sorted.first(), backLane = sorted.last())
        }

    private fun get(url: String, context: Context): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            GoogleApiHeaders.apply(conn, context)
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GET $url -> HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<GeoRoute> {
        val json = JSONObject(body)
        val status = json.optString("status")
        if (status != "OK") {
            Log.w(TAG, "Directions API status $status: ${json.optString("error_message")}")
            return emptyList()
        }
        val routes = mutableListOf<GeoRoute>()
        val routesJson = json.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routesJson.length()) {
            routesJson.optJSONObject(i)?.let { routeToGeoRoute(it) }?.let(routes::add)
        }
        return routes
    }

    private fun routeToGeoRoute(route: JSONObject): GeoRoute? {
        val encoded = route.optJSONObject("overview_polyline")?.optString("points")
        if (encoded.isNullOrEmpty()) return null
        val points = decodePolyline(encoded)
        if (points.size < 2) return null

        var distanceMeters = 0.0
        var durationSeconds = 0.0
        var stepCount = 0
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            distanceMeters += leg.optJSONObject("distance")?.optDouble("value") ?: 0.0
            durationSeconds += leg.optJSONObject("duration")?.optDouble("value") ?: 0.0
            stepCount += leg.optJSONArray("steps")?.length() ?: 0
        }
        if (distanceMeters <= 0 || durationSeconds <= 0) return null

        return GeoRoute(
            points = points,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            summary = route.optString("summary").takeIf { it.isNotBlank() },
            stepCount = stepCount
        )
    }

    /** Decodes Google's encoded polyline algorithm at its standard 1e-5 precision. */
    private fun decodePolyline(encoded: String): List<DoubleArray> {
        val points = mutableListOf<DoubleArray>()
        var index = 0
        var lat = 0
        var lng = 0
        val factor = 1e5

        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(doubleArrayOf(lat / factor, lng / factor))
        }
        return points
    }
}
