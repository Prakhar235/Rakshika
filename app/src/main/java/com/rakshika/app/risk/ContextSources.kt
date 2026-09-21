package com.rakshika.app.risk

import android.content.Context
import android.util.Log
import com.rakshika.app.BuildConfig
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.geo.pathLengthMeters
import com.rakshika.app.net.GoogleApiHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a data source found for one corridor: [features] feed the equation, [summary] is what the model reads. */
data class SourceResult(val features: Map<String, Double>, val summary: JSONObject)

/**
 * The extra data sources the model can choose to call while assessing a route. The model only names
 * a corridor ("main"/"back"); the coordinates come from the app's own route, so no location goes to
 * OpenAI. Each source is free/keyless except Google Places (uses the existing Maps key), and each
 * returns null on any failure so the model simply proceeds without it.
 *
 * A source only reports a feature it actually measured — e.g. no `lit_fraction` unless OSM has
 * lighting tags along the route — because "unmapped" is not the same as "unlit".
 */
object ContextSources {
    private const val TAG = "ContextSources"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val METEO_URL = "https://api.open-meteo.com/v1/forecast"
    private const val PLACES_URL = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
    private const val USER_AGENT = "SafeMaps-Android/0.1 (route safety research)"

    private val COMMERCE = "restaurant|cafe|fast_food|pharmacy|bank|atm|fuel|bar|pub"
    private val EMERGENCY = "police|hospital|clinic|fire_station"
    private val FOOT_HIGHWAYS = setOf("footway", "pedestrian", "path", "steps", "living_street", "cycleway")

    /** OSM via Overpass: street lighting, footpaths, shops/cafés and police/hospitals along the route line. */
    suspend fun osmRouteContext(points: List<DoubleArray>): SourceResult? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        val line = downsample(points, 20).joinToString(",") { "${it[0]},${it[1]}" }
        val query = "[out:json][timeout:20];(" +
            "way(around:25,$line)[\"highway\"];" +
            "node(around:120,$line)[\"amenity\"~\"^($COMMERCE)$\"];" +
            "node(around:120,$line)[\"shop\"];" +
            "node(around:600,$line)[\"amenity\"~\"^($EMERGENCY)$\"];" +
            ");out tags;"
        val body = http(
            OVERPASS_URL, method = "POST", readTimeoutMs = 25000,
            postBody = "data=" + URLEncoder.encode(query, "UTF-8")
        ) ?: return@withContext null

        runCatching {
            val elements = JSONObject(body).getJSONArray("elements")
            var ways = 0; var litYes = 0; var litNo = 0; var foot = 0; var places = 0; var emergency = 0
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: continue
                if (e.getString("type") == "way") {
                    ways++
                    when (tags.optString("lit")) { "yes", "24/7", "automatic" -> litYes++; "no" -> litNo++ }
                    if (tags.optString("highway") in FOOT_HIGHWAYS || tags.optString("sidewalk") in setOf("both", "left", "right", "yes")) foot++
                } else if (tags.optString("amenity").matches(Regex(EMERGENCY))) {
                    emergency++
                } else {
                    places++
                }
            }
            val km = (pathLengthMeters(points) / 1000.0).coerceAtLeast(0.1)
            val features = mutableMapOf(
                "places_per_km" to round1(places / km),
                "emergency_services_count" to emergency.toDouble()
            )
            val litTagged = litYes + litNo
            if (litTagged > 0) features["lit_fraction"] = round2(litYes.toDouble() / litTagged)
            if (ways > 0) features["footpath_fraction"] = round2(foot.toDouble() / ways)

            val summary = JSONObject()
                .put("source", "osm_overpass")
                .put("features", JSONObject(features.toMap()))
                .put("waysSampled", ways)
                .put("waysWithLightingTag", litTagged)
                .put(
                    "caveat",
                    "OSM coverage varies. lit_fraction is only present when lighting tags exist; a missing " +
                        "feature means unknown, not bad. Counts are from a simplified line along the route."
                )
            SourceResult(features, summary)
        }.onFailure { Log.w(TAG, "Overpass parse failed", it) }.getOrNull()
    }

    /** Open-Meteo: whether it's dark right now, plus rain and visibility at the route midpoint. */
    suspend fun weatherDaylight(points: List<DoubleArray>): SourceResult? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext null
        val mid = geoPointAt(points, 0.5f)
        val url = "$METEO_URL?latitude=${mid[0]}&longitude=${mid[1]}" +
            "&current=precipitation,visibility,is_day&daily=sunset&timezone=auto&forecast_days=1"
        val body = http(url) ?: return@withContext null
        runCatching {
            val json = JSONObject(body)
            val cur = json.getJSONObject("current")
            val features = mapOf(
                "is_night" to (if (cur.getInt("is_day") == 1) 0.0 else 1.0),
                "precipitation_mm" to cur.getDouble("precipitation"),
                "visibility_km" to round1(cur.getDouble("visibility") / 1000.0)
            )
            val summary = JSONObject()
                .put("source", "open_meteo")
                .put("features", JSONObject(features))
                .put("sunsetToday", json.optJSONObject("daily")?.optJSONArray("sunset")?.optString(0))
            SourceResult(features, summary)
        }.onFailure { Log.w(TAG, "Open-Meteo parse failed", it) }.getOrNull()
    }

    /** Google Places: how many places are open right now near the route midpoint — a "will anyone be around" signal. */
    suspend fun openPlacesNow(context: Context, points: List<DoubleArray>): SourceResult? = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext null
        val mid = geoPointAt(points, 0.5f)
        val url = "$PLACES_URL?location=${mid[0]},${mid[1]}&radius=400&opennow=true&key=${BuildConfig.MAPS_API_KEY}"
        val body = http(url, context = context) ?: return@withContext null
        runCatching {
            val json = JSONObject(body)
            val status = json.optString("status")
            if (status != "OK" && status != "ZERO_RESULTS") {
                Log.w(TAG, "Places status $status: ${json.optString("error_message")}")
                return@runCatching null
            }
            val count = json.optJSONArray("results")?.length() ?: 0
            val features = mapOf("open_places_now" to count.toDouble())
            SourceResult(
                features,
                JSONObject().put("source", "google_places").put("features", JSONObject(features))
                    .put("caveat", "Sampled at the route midpoint only; capped at 20 by the API's first page.")
            )
        }.onFailure { Log.w(TAG, "Places parse failed", it) }.getOrNull()
    }

    private fun http(
        url: String,
        method: String = "GET",
        postBody: String? = null,
        readTimeoutMs: Int = 8000,
        context: Context? = null
    ): String? {
        val conn = URL(url).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", USER_AGENT)
            context?.let { GoogleApiHeaders.apply(conn, it) }
            if (postBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "$method ${url.substringBefore('?')} -> HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method ${url.substringBefore('?')} failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** At most [max] points, evenly spaced along [points], always keeping the first and last. */
    private fun downsample(points: List<DoubleArray>, max: Int): List<DoubleArray> {
        if (points.size <= max) return points
        return (0 until max).map { points[it * (points.size - 1) / (max - 1)] }
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
