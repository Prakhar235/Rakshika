package com.rakshika.app.live

import android.util.Log
import com.rakshika.app.geo.geoPointAt
import com.rakshika.app.ride.Place
import com.rakshika.app.ride.RouteOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** What the "Sharing live" chip shows on the ride screen. */
enum class LiveShareStatus { OFF, CONNECTING, LIVE, ERROR }

/**
 * Publishes the in-progress demo ride to Firebase Realtime Database using its REST API.
 *
 * Writes go to  <DATABASE_URL>/liveTrips/<TRIP_ID>.json  — a PUT for the whole trip when it
 * starts, then small PATCHes for the moving location, SOS, and arrival. Every call is
 * best-effort: failures are logged and surfaced as [status] but never crash the demo.
 */
class LiveShareRepository(private val scope: CoroutineScope) {

    private val _status = MutableStateFlow(LiveShareStatus.OFF)
    val status: StateFlow<LiveShareStatus> = _status

    /** URL of the reader page / Firebase console path, handy to show in the UI. */
    val tripUrl: String
        get() = "${LiveShareConfig.DATABASE_URL}/liveTrips/${LiveShareConfig.TRIP_ID}"

    /** A single location fix along the way. [t] is 0..1 ride progress. [path] is `[lat, lng]` pairs. */
    fun updateLocation(path: List<DoubleArray>, t: Float, etaMinutesLeft: Int) {
        if (!LiveShareConfig.isConfigured) return
        val geo = geoPointAt(path, t)
        val body = JSONObject()
            .put("lat", geo[0])
            .put("lng", geo[1])
            .put("progress", t.toDouble())
            .put("etaMinutesLeft", etaMinutesLeft)
            .put("updatedAt", now())
        send("location.json", "PATCH", body.toString())
    }

    /** Publish the whole trip: origin, destination, chosen route, full polyline, first fix. */
    fun startTrip(
        origin: Place,
        destination: Place,
        route: RouteOption,
        safeSelected: Boolean,
        path: List<DoubleArray>,
        etaMinutes: Int
    ) {
        if (!LiveShareConfig.isConfigured) {
            _status.value = LiveShareStatus.OFF
            Log.w(TAG, "LiveShare skipped — set LiveShareConfig.DATABASE_URL to enable it.")
            return
        }
        _status.value = LiveShareStatus.CONNECTING

        val polyline = JSONArray()
        path.forEach { g -> polyline.put(JSONObject().put("lat", g[0]).put("lng", g[1])) }
        val start = path.first()
        val end = path.last()

        val trip = JSONObject()
            .put("status", "riding")
            .put("startedAt", now())
            .put("updatedAt", now())
            .put("sos", false)
            .put("origin", JSONObject()
                .put("name", origin.name).put("area", origin.area)
                .put("lat", start[0]).put("lng", start[1]))
            .put("destination", JSONObject()
                .put("name", destination.name).put("area", destination.area)
                .put("lat", end[0]).put("lng", end[1]))
            .put("route", JSONObject()
                .put("label", route.label)
                .put("kind", if (safeSelected) "safe" else "fast")
                .put("minutes", route.minutes)
                .put("safetyScore", route.safetyScore)
                .put("recommended", route.recommended)
                .put("reasons", JSONArray(route.reasons)))
            .put("polyline", polyline)
            .put("location", JSONObject()
                .put("lat", start[0]).put("lng", start[1])
                .put("progress", 0.0)
                .put("etaMinutesLeft", etaMinutes)
                .put("updatedAt", now()))

        send(".json", "PUT", trip.toString(), markLiveOnSuccess = true)
    }

    fun setSos(active: Boolean) {
        if (!LiveShareConfig.isConfigured) return
        val body = JSONObject().put("sos", active)
        if (active) body.put("sosAt", now())
        body.put("updatedAt", now())
        send(".json", "PATCH", body.toString())
    }

    fun arrive() {
        if (!LiveShareConfig.isConfigured) return
        val body = JSONObject()
            .put("status", "arrived")
            .put("arrivedAt", now())
            .put("updatedAt", now())
        send(".json", "PATCH", body.toString())
    }

    fun endTrip() {
        if (!LiveShareConfig.isConfigured) return
        _status.value = LiveShareStatus.OFF
        val body = JSONObject().put("status", "ended").put("updatedAt", now())
        send(".json", "PATCH", body.toString())
    }

    // --- internals -----------------------------------------------------------

    private fun send(suffix: String, method: String, json: String, markLiveOnSuccess: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val base = "${LiveShareConfig.DATABASE_URL}/liveTrips/${LiveShareConfig.TRIP_ID}"
                val url = URL(if (suffix == ".json") "$base.json" else "$base/$suffix")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    // HttpURLConnection can't send PATCH directly; Firebase honours this override.
                    if (method == "PATCH") {
                        requestMethod = "POST"
                        setRequestProperty("X-HTTP-Method-Override", "PATCH")
                    } else {
                        requestMethod = method
                    }
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    if (markLiveOnSuccess) _status.value = LiveShareStatus.LIVE
                } else {
                    _status.value = LiveShareStatus.ERROR
                    Log.w(TAG, "Firebase $method $suffix -> HTTP $code")
                }
            } catch (e: Exception) {
                _status.value = LiveShareStatus.ERROR
                Log.w(TAG, "Firebase $method $suffix failed: ${e.message}")
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object { const val TAG = "LiveShare" }
}
