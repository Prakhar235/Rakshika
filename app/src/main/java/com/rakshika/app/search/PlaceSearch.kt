package com.rakshika.app.search

import android.util.Log
import com.rakshika.app.geo.haversineMeters
import com.rakshika.app.ride.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free-text place search via Photon (photon.komoot.io) — a public, keyless geocoder
 * built on OpenStreetMap data, restricted to a box around [lat]/[lon] so every result
 * is a real, nearby, road-routable place — not just soft-biased toward it. Without a
 * hard box, a query with no good local match can come back with a real but very
 * distant place (a different city, even a different continent), which a routing
 * engine then correctly reports as unroutable — that's what "no route" usually means,
 * not a routing-API failure. No API key or billing account required.
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"

    /** ~30km-wide box around the search origin — big enough for a real city destination, small enough to stay routable. */
    private const val SEARCH_RADIUS_DEG = 0.15

    /** Returns null on any network failure (caller should fall back to a local list); empty list means no matches. */
    suspend fun search(query: String, lat: Double, lon: Double, limit: Int = 6): List<Place>? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val bbox = "${lon - SEARCH_RADIUS_DEG},${lat - SEARCH_RADIUS_DEG}," +
                "${lon + SEARCH_RADIUS_DEG},${lat + SEARCH_RADIUS_DEG}"
            val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&lat=$lat&lon=$lon&limit=$limit&bbox=$bbox"
            val body = fetch(url) ?: return@withContext null
            val places = runCatching { parse(body) }
                .onFailure { Log.w(TAG, "Failed to parse Photon response for \"$query\"", it) }
                .getOrNull()
            Log.i(TAG, "\"$query\" -> ${places?.size ?: "parse failed"} result(s)" +
                (places?.let { r -> ", ${r.count { it.lat != null }} with coordinates" } ?: ""))
            places
        }

    /**
     * Real named places actually near [lat]/[lon], via the Overpass API (OSM, no key) — used for
     * the "Nearby" list shown before the user types anything, so it reflects wherever the device
     * really is instead of a fixed demo spot. Returns null on any network/parse failure (caller
     * falls back to the static list); empty list means Overpass genuinely found nothing nearby.
     */
    suspend fun nearby(lat: Double, lon: Double, radiusMeters: Int = 1500, limit: Int = 5): List<Place>? =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:10];" +
                "(node[\"name\"][\"amenity\"](around:$radiusMeters,$lat,$lon);" +
                "node[\"name\"][\"shop\"](around:$radiusMeters,$lat,$lon);" +
                "node[\"name\"][\"railway\"=\"station\"](around:$radiusMeters,$lat,$lon);" +
                "node[\"name\"][\"tourism\"](around:$radiusMeters,$lat,$lon););" +
                "out body ${limit * 6};"
            val url = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
            val body = fetch(url, readTimeoutMs = 12000) ?: return@withContext null
            val here = doubleArrayOf(lat, lon)
            val places = runCatching { parseOverpass(body) }
                .onFailure { Log.w(TAG, "Failed to parse Overpass response", it) }
                .getOrNull()
                ?.sortedBy { haversineMeters(here, doubleArrayOf(it.lat!!, it.lng!!)) }
                ?.take(limit)
            Log.i(TAG, "Nearby @ $lat,$lon -> ${places?.size ?: "parse failed"} result(s)")
            places
        }

    private fun parseOverpass(body: String): List<Place> {
        val elements = JSONObject(body).optJSONArray("elements") ?: JSONArray()
        val places = mutableListOf<Place>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: continue
            val elLat = el.optDouble("lat").takeUnless { it.isNaN() } ?: continue
            val elLon = el.optDouble("lon").takeUnless { it.isNaN() } ?: continue
            val kind = tags.optString("amenity").takeIf { it.isNotBlank() }
                ?: tags.optString("shop").takeIf { it.isNotBlank() }
                ?: tags.optString("tourism").takeIf { it.isNotBlank() }
                ?: "Nearby"
            places.add(Place(name, kind.replace('_', ' ').replaceFirstChar { it.uppercase() }, elLat, elLon))
        }
        return places
    }

    private fun fetch(url: String, readTimeoutMs: Int = 5000): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
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

    private fun parse(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        val places = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val name = props.optString("name").takeIf { it.isNotBlank() } ?: continue
            val area = listOfNotNull(
                props.optString("street").takeIf { it.isNotBlank() },
                props.optString("district").takeIf { it.isNotBlank() },
                props.optString("city").takeIf { it.isNotBlank() },
                props.optString("state").takeIf { it.isNotBlank() },
                props.optString("country").takeIf { it.isNotBlank() }
            ).firstOrNull() ?: ""

            // GeoJSON coordinates are [lon, lat].
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
            val lng = coords?.optDouble(0)?.takeUnless { it.isNaN() }
            val lat = coords?.optDouble(1)?.takeUnless { it.isNaN() }

            places.add(Place(name, area, lat, lng))
        }
        return places
    }
}
