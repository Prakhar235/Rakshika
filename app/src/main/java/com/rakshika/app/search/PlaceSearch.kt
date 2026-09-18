package com.rakshika.app.search

import android.content.Context
import android.util.Log
import com.rakshika.app.BuildConfig
import com.rakshika.app.geo.haversineMeters
import com.rakshika.app.net.GoogleApiHeaders
import com.rakshika.app.ride.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free-text place search via the Places API (Text Search + Nearby Search) — biased to a radius
 * around [lat]/[lon] so results skew toward real, nearby, road-routable places. Note this is a
 * *bias*, not a hard restriction (the legacy Places API has no rectangle/box filter like the
 * old OSM-based search had) — a query with no good local match can still come back with a real
 * but distant place, which a routing engine then correctly reports as unroutable. Requires
 * MAPS_API_KEY (see local.properties) with the Places API enabled and billing on.
 */
object PlaceSearch {
    private const val TAG = "PlaceSearch"

    /** Search bias radius in meters — wide enough for a real city destination. */
    private const val SEARCH_RADIUS_METERS = 15000

    /** Returns null on any network failure (caller should fall back to a local list); empty list means no matches. */
    suspend fun search(context: Context, query: String, lat: Double, lon: Double, limit: Int = 6): List<Place>? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val url = "https://maps.googleapis.com/maps/api/place/textsearch/json" +
                "?query=${URLEncoder.encode(query, "UTF-8")}" +
                "&location=$lat,$lon&radius=$SEARCH_RADIUS_METERS&key=${BuildConfig.MAPS_API_KEY}"
            val body = fetch(url, context) ?: return@withContext null
            val places = runCatching { parse(body, limit) }
                .onFailure { Log.w(TAG, "Failed to parse Places textsearch response for \"$query\"", it) }
                .getOrNull()
            Log.i(TAG, "\"$query\" -> ${places?.size ?: "parse failed"} result(s)" +
                (places?.let { r -> ", ${r.count { it.lat != null }} with coordinates" } ?: ""))
            places
        }

    /**
     * Real named places actually near [lat]/[lon] — used for the "Nearby" list shown before the
     * user types anything, so it reflects wherever the device really is instead of a fixed demo
     * spot. Returns null on any network/parse failure (caller falls back to the static list);
     * empty list means the API genuinely found nothing nearby.
     */
    suspend fun nearby(context: Context, lat: Double, lon: Double, radiusMeters: Int = 1500, limit: Int = 5): List<Place>? =
        withContext(Dispatchers.IO) {
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                "?location=$lat,$lon&radius=$radiusMeters&key=${BuildConfig.MAPS_API_KEY}"
            val body = fetch(url, context) ?: return@withContext null
            val here = doubleArrayOf(lat, lon)
            val places = runCatching { parse(body, limit * 6) }
                .onFailure { Log.w(TAG, "Failed to parse Places nearbysearch response", it) }
                .getOrNull()
                ?.sortedBy { haversineMeters(here, doubleArrayOf(it.lat!!, it.lng!!)) }
                ?.take(limit)
            Log.i(TAG, "Nearby @ $lat,$lon -> ${places?.size ?: "parse failed"} result(s)")
            places
        }

    private fun fetch(url: String, context: Context, readTimeoutMs: Int = 6000): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = readTimeoutMs
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

    private fun parse(body: String, limit: Int): List<Place> {
        val json = JSONObject(body)
        val status = json.optString("status")
        if (status != "OK" && status != "ZERO_RESULTS") {
            Log.w(TAG, "Places API status $status: ${json.optString("error_message")}")
        }
        val results = json.optJSONArray("results") ?: JSONArray()
        val places = mutableListOf<Place>()
        for (i in 0 until minOf(results.length(), limit)) {
            val r = results.optJSONObject(i) ?: continue
            val name = r.optString("name").takeIf { it.isNotBlank() } ?: continue
            val area = r.optString("formatted_address").takeIf { it.isNotBlank() }
                ?: r.optString("vicinity").takeIf { it.isNotBlank() }
                ?: ""
            val loc = r.optJSONObject("geometry")?.optJSONObject("location")
            val lat = loc?.optDouble("lat")?.takeUnless { it.isNaN() }
            val lng = loc?.optDouble("lng")?.takeUnless { it.isNaN() }
            places.add(Place(name, area, lat, lng))
        }
        return places
    }
}
