package com.rakshika.app.search

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
 * built on OpenStreetMap data, biased toward [lat]/[lon] so nearby matches rank first.
 * No API key or billing account required.
 */
object PlaceSearch {

    /** Returns null on any network failure (caller should fall back to a local list); empty list means no matches. */
    suspend fun search(query: String, lat: Double, lon: Double, limit: Int = 6): List<Place>? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&lat=$lat&lon=$lon&limit=$limit"
            val body = fetch(url) ?: return@withContext null
            runCatching { parse(body) }.getOrNull()
        }

    private fun fetch(url: String): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: JSONArray()
        val places = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val name = props.optString("name").takeIf { it.isNotBlank() } ?: continue
            val area = listOfNotNull(
                props.optString("street").takeIf { it.isNotBlank() },
                props.optString("district").takeIf { it.isNotBlank() },
                props.optString("city").takeIf { it.isNotBlank() },
                props.optString("state").takeIf { it.isNotBlank() },
                props.optString("country").takeIf { it.isNotBlank() }
            ).firstOrNull() ?: ""
            places.add(Place(name, area))
        }
        return places
    }
}
