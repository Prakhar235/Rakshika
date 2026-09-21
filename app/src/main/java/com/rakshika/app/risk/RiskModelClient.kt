package com.rakshika.app.risk

import android.content.Context
import android.util.Log
import com.rakshika.app.routing.GeoRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ModelPrediction(val score: Int, val confidence: Double, val reason: String)

/** What the model sent back for a fresh route search. */
data class ModelAssessment(
    /** The seed equation as the model modified it (validated, and re-run on-device by the caller). */
    val equation: SafetyEquation,
    val equationConfidence: Double,
    val predictions: Map<String, ModelPrediction>,
    val explanation: String,
    /** Per-corridor measurements: Google Directions plus everything the model fetched. */
    val features: Map<String, Map<String, Double>>,
    /** Data sources that were *actually* reached (the app's record, not the model's claim). */
    val sourcesUsed: List<String>
)

/** What the model sent back after a rider's feedback — a proposal until [RiskLoop] checks it. */
data class LearnProposal(val equation: SafetyEquation, val confidence: Double, val lessons: String)

/**
 * The two model calls in the loop, both against [OpenAiChat]:
 *  - [assess]: seed equation + real Google Directions measurements in → the model may call data
 *    tools (OSM, weather/daylight, Google Places) it judges useful → modified equation, confidence,
 *    per-route predictions and reasons out.
 *  - [learn]: the stored assessment + the rider's feedback in → improved equation, confidence, lessons out.
 * Both return null on any failure, and the caller degrades gracefully.
 */
object RiskModelClient {
    private const val TAG = "RiskModelClient"
    private const val MAX_TOOL_ROUNDS = 3

    private const val TOOL_OSM = "get_osm_route_context"
    private const val TOOL_WEATHER = "get_weather_daylight"
    private const val TOOL_PLACES = "get_open_places_now"

    suspend fun assess(
        context: Context,
        routes: Map<String, GeoRoute>,
        baseFeatures: Map<String, Map<String, Double>>,
        seed: EquationVersion,
        stats: AccuracyStats
    ): ModelAssessment? {
        if (!OpenAiChat.isConfigured || routes.isEmpty()) return null

        val features = baseFeatures.mapValues { it.value.toMutableMap() }
        val sources = linkedSetOf("google_directions")
        // Tools run concurrently within a round, so the cache must be thread-safe.
        val toolCache = ConcurrentHashMap<String, JSONObject>()

        val request = JSONObject()
            .put(
                "seed",
                JSONObject()
                    .put("version", seed.version)
                    .put("confidence", seed.confidence)
                    .put("note", seed.note)
                    .put("equation", seed.equation.toJson())
            )
            .put("seedScores", JSONObject().also { o -> features.forEach { (id, f) -> o.put(id, seed.equation.evaluate(f).score) } })
            .put(
                "corridors",
                JSONArray().also { a -> features.forEach { (id, f) -> a.put(JSONObject().put("id", id).put("features", featuresToJson(f))) } }
            )
            .put(
                "history",
                JSONObject()
                    .put("ratedTrips", stats.ratedTrips)
                    .put("meanAbsErrorPoints", stats.meanAbsError ?: JSONObject.NULL)
            )

        val messages = JSONArray()
            .put(message("system", assessSystemPrompt()))
            .put(message("user", request.toString()))

        // Phase 1 — let the model pull whatever extra data it thinks is worth having.
        val tools = toolDefinitions(routes.keys)
        for (round in 1..MAX_TOOL_ROUNDS) {
            val reply = OpenAiChat.complete(
                JSONObject().put("temperature", 0).put("messages", messages).put("tools", tools).put("tool_choice", "auto")
            ) ?: return null
            val calls = reply.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) break

            messages.put(
                JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls)
            )
            val results = coroutineScope {
                (0 until calls.length()).map { i ->
                    val call = calls.getJSONObject(i)
                    async { call.getString("id") to runTool(context, call, routes, features, toolCache, sources) }
                }.awaitAll()
            }
            results.forEach { (callId, content) ->
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", content.toString()))
            }
        }

        // Phase 2 — a clean, JSON-only answer with no tools on offer.
        messages.put(message("user", "Data gathering is finished. Reply now with ONLY the JSON object described in your instructions."))
        val answer = OpenAiChat.complete(
            JSONObject().put("temperature", 0).put("messages", messages).put("response_format", JSONObject().put("type", "json_object"))
        ) ?: return null

        return runCatching { parseAssessment(answer.getString("content"), features, sources.toList()) }
            .onFailure { Log.w(TAG, "Rejected the model's assessment: ${it.message}") }
            .onSuccess { Log.i(TAG, "Model assessed ${it.predictions.size} corridor(s) using ${it.sourcesUsed}") }
            .getOrNull()
    }

    suspend fun learn(
        record: AssessmentRecord,
        current: EquationVersion,
        examples: List<TrainingExample>,
        stats: AccuracyStats
    ): LearnProposal? {
        if (!OpenAiChat.isConfigured) return null
        val chosen = record.chosen() ?: return null
        val feedback = record.feedback ?: return null

        val request = JSONObject()
            .put("currentEquation", JSONObject().put("version", current.version).put("confidence", current.confidence).put("equation", current.equation.toJson()))
            .put("equationUsedForThisTrip", record.equation.toJson())
            .put(
                "trip",
                JSONObject()
                    .put("measurements", featuresToJson(chosen.features))
                    .put("predictedScore", chosen.predictedScore)
                    .put("predictedConfidence", chosen.confidence)
                    .put("ourReasoning", chosen.reason)
                    .put("riderOverallStars", feedback.overall)
                    .put("actualScore", feedback.actualScore)
                    .put(
                        "riderPerInputStars",
                        JSONArray().also { a ->
                            feedback.perFeature.forEach { (key, stars) ->
                                a.put(
                                    JSONObject()
                                        .put("feature", key)
                                        .put("measured", chosen.features[key]?.let { FeatureCatalog.format(key, it) } ?: "not measured")
                                        .put("stars", stars)
                                )
                            }
                        }
                    )
            )
            .put(
                "recentRatedTrips",
                JSONArray().also { a ->
                    examples.forEach { a.put(JSONObject().put("measurements", featuresToJson(it.features)).put("actualScore", it.actualScore).put("predictedScore", it.predictedScore)) }
                }
            )
            .put("history", JSONObject().put("ratedTrips", stats.ratedTrips).put("meanAbsErrorPoints", stats.meanAbsError ?: JSONObject.NULL))

        val reply = OpenAiChat.complete(
            JSONObject()
                .put("temperature", 0)
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(message("system", learnSystemPrompt())).put(message("user", request.toString())))
        ) ?: return null

        return runCatching {
            val json = JSONObject(reply.getString("content"))
            LearnProposal(
                equation = SafetyEquation.fromJson(json.getJSONObject("equation")),
                confidence = json.getDouble("confidence").coerceIn(0.0, 1.0),
                lessons = json.optString("lessons").ifBlank { "Equation adjusted from rider feedback." }
            )
        }
            .onFailure { Log.w(TAG, "Rejected the model's learned equation: ${it.message}") }
            .getOrNull()
    }

    /* ---------------- tools ---------------- */

    private suspend fun runTool(
        context: Context,
        call: JSONObject,
        routes: Map<String, GeoRoute>,
        features: Map<String, MutableMap<String, Double>>,
        cache: MutableMap<String, JSONObject>,
        sources: MutableSet<String>
    ): JSONObject {
        val fn = call.getJSONObject("function")
        val name = fn.getString("name")
        val corridor = runCatching { JSONObject(fn.optString("arguments", "{}")).optString("corridor_id") }.getOrDefault("")
        val route = routes[corridor] ?: return JSONObject().put("error", "unknown corridor_id \"$corridor\"; use one of ${routes.keys}")

        val key = "$name/$corridor"
        cache[key]?.let { return it }

        val points = route.points
        val result = when (name) {
            TOOL_OSM -> ContextSources.osmRouteContext(points)
            TOOL_WEATHER -> ContextSources.weatherDaylight(points)
            TOOL_PLACES -> ContextSources.openPlacesNow(context, points)
            else -> return JSONObject().put("error", "unknown tool $name")
        }
        val content = if (result == null) {
            JSONObject().put("error", "source unavailable right now — proceed without it")
        } else {
            synchronized(features) { features.getValue(corridor).putAll(result.features) }
            synchronized(sources) { sources += result.summary.optString("source") }
            result.summary
        }
        Log.i(TAG, "Tool $name($corridor) -> ${if (result == null) "unavailable" else result.features}")
        cache[key] = content
        return content
    }

    private fun toolDefinitions(corridorIds: Set<String>): JSONArray {
        fun tool(name: String, description: String) = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", name).put("description", description).put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("corridor_id", JSONObject().put("type", "string").put("enum", JSONArray(corridorIds.toList())))
                        )
                        .put("required", JSONArray().put("corridor_id"))
                )
            )
        return JSONArray()
            .put(tool(TOOL_OSM, "OpenStreetMap data along a corridor: street-lighting share, footpath share, shops/cafés per km, police/hospital count. Features: lit_fraction (when tagged), footpath_fraction, places_per_km, emergency_services_count."))
            .put(tool(TOOL_WEATHER, "Live weather and daylight at a corridor's midpoint. Features: is_night (exact), precipitation_mm, visibility_km."))
            .put(tool(TOOL_PLACES, "Google Places: how many places are open right now within 400 m of a corridor's midpoint. Feature: open_places_now."))
    }

    /* ---------------- parsing ---------------- */

    private fun parseAssessment(
        content: String,
        features: Map<String, Map<String, Double>>,
        sources: List<String>
    ): ModelAssessment {
        val json = JSONObject(content)
        val equation = SafetyEquation.fromJson(json.getJSONObject("equation"))
        val predictions = mutableMapOf<String, ModelPrediction>()
        val arr = json.getJSONArray("predictions")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val id = p.getString("id")
            if (id !in features) continue
            predictions[id] = ModelPrediction(
                score = p.getInt("safetyScore").coerceIn(1, 99),
                confidence = p.getDouble("confidence").coerceIn(0.0, 1.0),
                reason = p.optString("reason").ifBlank { "AI risk assessment from the live route data." }
            )
        }
        require(predictions.isNotEmpty()) { "no usable predictions" }
        return ModelAssessment(
            equation = equation,
            equationConfidence = json.getDouble("equationConfidence").coerceIn(0.0, 1.0),
            predictions = predictions,
            explanation = json.optString("explanation"),
            features = features.mapValues { it.value.toMap() },
            sourcesUsed = sources
        )
    }

    /* ---------------- prompts ---------------- */

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun featureGuide(): String =
        FeatureCatalog.all.joinToString("\n") { "- ${it.key}: ${it.meaning} [${it.source}]" }

    private val equationRule =
        "The safety equation is: score = clamp(base + Σ over terms of clamp(weight × (feature − reference), −cap, +cap), 1, 99). " +
            "Higher score = safer. `reference` is the neutral value of a feature; `cap` bounds one term's points. " +
            "Equation JSON shape: {\"base\":<1-99>,\"terms\":[{\"feature\":\"<key>\",\"weight\":<number>,\"reference\":<number>,\"cap\":<number in (0,40]>,\"note\":\"<why>\"}]}. " +
            "Use at most 8 terms and only the feature keys listed here:\n"

    private fun assessSystemPrompt() = "You are the risk model inside a women's personal-safety navigation app. You are given a seed " +
        "safety equation, how confident we are in it, and real measurements for one or two candidate route corridors " +
        "(ids \"main\" and \"back\"). Your job: return a MODIFIED equation suited to this situation, a confidence, and a " +
        "1-99 safety prediction with a reason for each corridor.\n\n" +
        equationRule + featureGuide() + "\n\n" +
        "Rules:\n" +
        "- You may call the data tools to measure more of these features when you judge that would make the prediction better, " +
        "and skip them when it wouldn't. Never invent data: use only the numbers you were given or a tool returned. A feature " +
        "with no measurement is unknown, not bad; it is skipped in scoring.\n" +
        "- Only add or reweight a term for a feature you actually have for the corridors, and change the seed with a reason. " +
        "Keep the seed's terms unless you can justify dropping them.\n" +
        "- Each prediction should agree with your equation applied to that corridor's measurements (the app recomputes it); if you " +
        "deliberately deviate, say why in the reason.\n" +
        "- `confidence` is 0-1, honest and conservative: the seed's confidence and the number of rider-rated trips tell you how " +
        "validated the equation is (0 rated trips = unvalidated). Lower it when key data is missing.\n" +
        "- `reason` is one short sentence citing a concrete number. `explanation` is 2-4 sentences: what you changed from the seed, " +
        "which data you used and why.\n" +
        "Reply with ONLY a JSON object of exactly this shape: " +
        "{\"equation\":{...},\"equationConfidence\":<0-1>,\"predictions\":[{\"id\":\"main\",\"safetyScore\":<int 1-99>," +
        "\"confidence\":<0-1>,\"reason\":\"...\"}],\"explanation\":\"...\"} — one prediction per corridor id you were given."

    private fun learnSystemPrompt() = "You are the learning step of the risk model inside a women's personal-safety navigation app. " +
        "A rider just finished a trip on one corridor and rated it. You get the equation to improve (`currentEquation`), the " +
        "equation that was used for this trip, the trip's real measurements, what we predicted, the rider's ratings, and recent " +
        "rated trips. Return an improved equation.\n\n" +
        equationRule + featureGuide() + "\n\n" +
        "Rules:\n" +
        "- The rider's overall stars map to a score on our scale: 1★=10, 2★=30, 3★=50, 4★=70, 5★=90 (`actualScore`). Move the " +
        "equation so this trip's measurements score closer to `actualScore`.\n" +
        "- `riderPerInputStars` says how safe each individual input felt (1-5) against its measured value. If an input the " +
        "equation rewards felt unsafe, reduce that term's weight or cap; if one it penalises felt fine, ease it; if a term isn't " +
        "supported by feedback, leave it alone. You may add a term for a measured feature that the feedback supports.\n" +
        "- One trip is weak evidence: change each weight modestly (roughly ≤30%) unless `recentRatedTrips` agree. The app checks " +
        "your equation against `recentRatedTrips` and rejects it if it fits them worse than currentEquation, so don't sacrifice " +
        "past trips to fit this one.\n" +
        "- `confidence` is 0-1 for the NEW equation, and should stay low while few trips have been rated.\n" +
        "- `lessons` is 1-3 sentences on what the feedback taught.\n" +
        "Reply with ONLY a JSON object of exactly this shape: {\"equation\":{...},\"confidence\":<0-1>,\"lessons\":\"...\"}"
}
