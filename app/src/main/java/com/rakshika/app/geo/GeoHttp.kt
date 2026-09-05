package com.rakshika.app.geo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny shared GET-JSON helper for the Places/Directions/Geocoding web services — mirrors the
 *  plain HttpURLConnection style already used by live/LiveShareRepository.kt for Firebase. */
internal object GeoHttp {
    private const val TAG = "GeoHttp"

    suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            if (code !in 200..299 || body == null) {
                Log.w(TAG, "GET $url -> HTTP $code")
                return@withContext null
            }
            val json = JSONObject(body)
            val status = json.optString("status", "OK")
            if (status != "OK" && status != "ZERO_RESULTS") {
                Log.w(TAG, "GET $url -> status $status: ${json.optString("error_message")}")
            }
            json
        } catch (e: Exception) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        }
    }
}
