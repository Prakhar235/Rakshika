package com.rakshika.app.risk

import android.util.Log
import com.rakshika.app.BuildConfig
import com.rakshika.app.routing.GeoRoute
import com.rakshika.app.routing.ModelRiskScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Turns the real Google Directions numbers behind each corridor ([GeoRoute] — average speed,
 * named through-road, turn density; the same facts [com.rakshika.app.routing.RouteScoring]'s own
 * heuristic already reads) into a model-predicted safety score, instead of a fixed formula.
 *
 * Uses OpenAI's Chat Completions API directly from the device (raw REST, same no-SDK style as
 * [com.rakshika.app.routing.GoogleRouting]/[com.rakshika.app.search.PlaceSearch]) with
 * `OPENAI_API_KEY` from `local.properties` (gitignored, never committed — see `BuildConfig`).
 *
 * The returned score keeps the exact same scale/meaning as the heuristic it can replace — 1-99,
 * higher = safer — so callers can swap one for the other without touching anything downstream
 * (the meter, the "Safest" tag, the safe-vs-fast comparison all just keep working).
 */
object OpenAiRiskScorer {
    private const val TAG = "OpenAiRiskScorer"
    private const val URL_STR = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o-mini"

    /** Returns null (caller falls back to the heuristic) when no key is configured, there's no
     *  live route to score, or the request fails/returns something unparseable — check Logcat
     *  tag "OpenAiRiskScorer" why. Keyed by "main"/"back", only for whichever corridors had a
     *  real Directions route to send. */
    suspend fun score(mainRoute: GeoRoute?, backRoute: GeoRoute?): Map<String, ModelRiskScore>? =
        withContext(Dispatchers.IO) {
            if (BuildConfig.OPENAI_API_KEY.isBlank()) {
                Log.w(TAG, "OPENAI_API_KEY not set in local.properties — skipping model risk scoring")
                return@withContext null
            }
            val corridors = buildList {
                mainRoute?.let { add("main" to it) }
                backRoute?.let { add("back" to it) }
            }
            if (corridors.isEmpty()) {
                Log.i(TAG, "No live corridor data to score — skipping the model call")
                return@withContext null
            }

            val response = post(requestBody(corridors)) ?: return@withContext null
            runCatching { parse(response) }
                .onFailure { Log.w(TAG, "Failed to parse OpenAI response", it) }
                .onSuccess { Log.i(TAG, "Model scored ${it.size} corridor(s): $it") }
                .getOrNull()
        }

    private fun requestBody(corridors: List<Pair<String, GeoRoute>>): String {
        val facts = JSONArray()
        corridors.forEach { (id, route) ->
            facts.put(
                JSONObject()
                    .put("id", id)
                    .put("distanceMeters", route.distanceMeters.roundToInt())
                    .put("durationSeconds", route.durationSeconds.roundToInt())
                    .put("avgSpeedKmh", (route.avgSpeedKmh * 10).roundToInt() / 10.0)
                    .put("namedRoad", route.summary ?: JSONObject.NULL)
                    .put("turnCount", route.stepCount)
            )
        }

        val systemPrompt = "You are a pedestrian personal-safety risk model for a women's safety " +
            "navigation app. You are given real Google Directions facts for one or two candidate " +
            "walking corridors between an origin and a destination — never invent facts beyond " +
            "what's given. Average speed is a proxy for how major/trafficked a road is, a named " +
            "through-road suggests a lit arterial rather than an unmapped lane, and turn density " +
            "is a proxy for directness (how easy it would be to get help or be seen). A faster, " +
            "named, more direct corridor is generally safer; a slow, unnamed, winding one is " +
            "generally riskier. Score EVERY corridor id you are given. " +
            "Reply with ONLY a JSON object, no other text, of exactly this shape: " +
            "{\"corridors\":[{\"id\":\"main\",\"safetyScore\":<integer 1-99, higher = safer>," +
            "\"reason\":\"<one short sentence citing a concrete number from the given facts>\"}]}"

        val userPrompt = JSONObject().put("corridors", facts).toString()

        return JSONObject()
            .put("model", MODEL)
            .put("temperature", 0)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt))
            )
            .toString()
    }

    private fun post(body: String): String? {
        val conn = URL(URL_STR).openConnection() as? HttpURLConnection ?: return null
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "POST $URL_STR -> HTTP $code: $err")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $URL_STR failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Map<String, ModelRiskScore> {
        val message = JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val corridors = JSONObject(message).getJSONArray("corridors")

        val result = mutableMapOf<String, ModelRiskScore>()
        for (i in 0 until corridors.length()) {
            val c = corridors.getJSONObject(i)
            val id = c.getString("id")
            result[id] = ModelRiskScore(
                corridorId = id,
                safetyScore = c.getInt("safetyScore").coerceIn(1, 99),
                reason = c.optString("reason").ifBlank { "AI risk assessment from the live Directions data." }
            )
        }
        return result
    }
}
