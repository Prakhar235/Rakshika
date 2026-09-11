package com.rakshika.app.routing

import android.util.Log
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
    val durationSeconds: Double
) {
    val avgSpeedKmh: Double
        get() = if (durationSeconds <= 0) 0.0 else (distanceMeters / 1000.0) / (durationSeconds / 3600.0)
}

/** The two corridors the rest of the app reasons about, now backed by real routes when routing found them. */
data class RoutingResult(val mainRoad: GeoRoute?, val backLane: GeoRoute?)

/**
 * Real road routing via the FOSSGIS Valhalla public demo server
 * (valhalla1.openstreetmap.de) — free, no API key, no billing account. (An earlier
 * version of this used OSRM's public demo instead; that one turned out to return
 * "no route" too often from some networks, so this switched to Valhalla's public
 * instance instead — same idea, different free host.)
 *
 * There is no OSM-tag lookup for "is this a main road" in the response, so corridor
 * classification is a heuristic: among the alternative routes, the one with the
 * highest average speed (distance ÷ duration) is treated as the "main road" route —
 * arterial roads sustain faster average speeds than residential back lanes — and
 * the slowest as the "back lane" route.
 */
object ValhallaRouting {
    private const val TAG = "ValhallaRouting"
    private const val URL_STR = "https://valhalla1.openstreetmap.de/route"
    private const val MAIN_ROAD_SPEED_KMH = 25.0

    /** Returns null when routing couldn't be reached or found no route at all — check Logcat tag "ValhallaRouting" why. */
    suspend fun findRoutes(originLat: Double, originLng: Double, destLat: Double, destLng: Double): RoutingResult? =
        withContext(Dispatchers.IO) {
            val requestBody = JSONObject()
                .put(
                    "locations",
                    JSONArray()
                        .put(JSONObject().put("lat", originLat).put("lon", originLng))
                        .put(JSONObject().put("lat", destLat).put("lon", destLng))
                )
                .put("costing", "auto")
                .put("alternates", 2)
                .toString()

            val body = post(URL_STR, requestBody) ?: return@withContext null
            val routes = runCatching { parse(body) }
                .onFailure { Log.w(TAG, "Failed to parse Valhalla response", it) }
                .getOrNull()
            if (routes.isNullOrEmpty()) {
                Log.w(TAG, "Valhalla returned no usable routes for $originLat,$originLng -> $destLat,$destLng")
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

    private fun post(url: String, jsonBody: String): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Valhalla POST $url -> HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Valhalla POST $url failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<GeoRoute> {
        val json = JSONObject(body)
        val routes = mutableListOf<GeoRoute>()
        json.optJSONObject("trip")?.let { tripToRoute(it)?.let(routes::add) }
        json.optJSONArray("alternates")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optJSONObject("trip")?.let { tripToRoute(it)?.let(routes::add) }
            }
        }
        return routes
    }

    private fun tripToRoute(trip: JSONObject): GeoRoute? {
        if (trip.optInt("status", -1) != 0) return null
        val legs = trip.optJSONArray("legs") ?: return null
        val points = mutableListOf<DoubleArray>()
        for (i in 0 until legs.length()) {
            val shape = legs.optJSONObject(i)?.optString("shape") ?: continue
            points.addAll(decodePolyline6(shape))
        }
        if (points.size < 2) return null
        val summary = trip.optJSONObject("summary") ?: return null
        return GeoRoute(
            points = points,
            distanceMeters = summary.optDouble("length") * 1000.0,
            durationSeconds = summary.optDouble("time")
        )
    }

    /** Decodes Valhalla's encoded polyline — Google's algorithm at 1e-6 precision (not the usual 1e-5). */
    private fun decodePolyline6(encoded: String): List<DoubleArray> {
        val points = mutableListOf<DoubleArray>()
        var index = 0
        var lat = 0
        var lng = 0
        val factor = 1e6

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
