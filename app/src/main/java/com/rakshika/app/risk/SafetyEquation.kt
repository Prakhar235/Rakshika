package com.rakshika.app.risk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One line of the safety equation: `points = clamp(weight * (feature - reference), -cap, +cap)`.
 * [reference] is the "neutral" value of the feature (no points either way), [cap] keeps any one
 * signal from swamping the rest.
 */
data class EquationTerm(
    val feature: String,
    val weight: Double,
    val reference: Double,
    val cap: Double,
    val note: String = ""
) {
    fun points(value: Double): Double = (weight * (value - reference)).coerceIn(-cap, cap)
}

data class TermContribution(val term: EquationTerm, val value: Double, val points: Double)

data class Evaluation(
    val score: Int,
    val contributions: List<TermContribution>,
    /** Features the equation wants but this corridor has no measurement for — they add 0 points. */
    val unmeasured: List<String>
)

/**
 * The safety score as data — `score = clamp(base + Σ term points, 1, 99)`, higher = safer — so the
 * model can hand back a *modified* function and the app can re-run it locally on the same
 * measurements to check the model's claim instead of just trusting it.
 */
data class SafetyEquation(val base: Double, val terms: List<EquationTerm>) {

    fun evaluate(features: Map<String, Double>): Evaluation {
        val contributions = mutableListOf<TermContribution>()
        val unmeasured = mutableListOf<String>()
        var total = base
        for (term in terms) {
            val value = features[term.feature]
            if (value == null) {
                unmeasured += term.feature
                continue
            }
            val points = term.points(value)
            total += points
            contributions += TermContribution(term, value, points)
        }
        return Evaluation(total.coerceIn(MIN_SCORE, MAX_SCORE).roundToInt(), contributions, unmeasured)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("base", base)
        .put(
            "terms",
            JSONArray().also { arr ->
                terms.forEach {
                    arr.put(
                        JSONObject()
                            .put("feature", it.feature)
                            .put("weight", it.weight)
                            .put("reference", it.reference)
                            .put("cap", it.cap)
                            .put("note", it.note)
                    )
                }
            }
        )

    companion object {
        const val MIN_SCORE = 1.0
        const val MAX_SCORE = 99.0
        private const val MAX_TERMS = 8
        private const val MAX_ABS_WEIGHT = 100.0
        private const val MAX_CAP = 40.0

        /** The starting equation, built from the same three Google Directions signals the old fixed
         *  heuristic in [com.rakshika.app.routing.RouteScoring] read — restated as absolute terms so it
         *  can score one corridor on its own. Nothing here has been checked against real trips yet,
         *  which is why [SEED_CONFIDENCE] starts low. */
        val SEED = SafetyEquation(
            base = 50.0,
            terms = listOf(
                EquationTerm("avg_speed_kmh", weight = 0.9, reference = 25.0, cap = 18.0, note = "faster average pace suggests a busier through-road"),
                EquationTerm("has_named_road", weight = 22.0, reference = 0.5, cap = 11.0, note = "a named road suggests a lit arterial, not an unmapped lane"),
                EquationTerm("turns_per_km", weight = -2.0, reference = 3.0, cap = 8.0, note = "fewer turns means a more direct, easier-to-be-seen route")
            )
        )
        const val SEED_CONFIDENCE = 0.30

        /** Parses and validates an equation — from the model or from disk. Throws
         *  [IllegalArgumentException] naming the first thing wrong, so a bad model reply is rejected
         *  rather than silently reshaping how safety is scored. */
        fun fromJson(json: JSONObject): SafetyEquation {
            val base = json.getDouble("base")
            require(base in MIN_SCORE..MAX_SCORE) { "base $base outside 1..99" }
            val arr = json.getJSONArray("terms")
            require(arr.length() in 1..MAX_TERMS) { "needs 1..$MAX_TERMS terms, got ${arr.length()}" }

            val terms = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val feature = o.getString("feature")
                require(FeatureCatalog.get(feature) != null) { "unknown feature \"$feature\"" }
                val weight = o.getDouble("weight")
                val reference = o.getDouble("reference")
                val cap = o.getDouble("cap")
                require(weight.isFinite() && abs(weight) <= MAX_ABS_WEIGHT) { "weight for $feature out of range" }
                require(reference.isFinite()) { "reference for $feature is not finite" }
                require(cap > 0 && cap <= MAX_CAP) { "cap for $feature must be in (0, $MAX_CAP]" }
                EquationTerm(feature, weight, reference, cap, o.optString("note"))
            }
            require(terms.map { it.feature }.toSet().size == terms.size) { "duplicate feature term" }
            return SafetyEquation(base, terms)
        }
    }
}

/** One measurable input the equation may use — its plain-language label drives the feedback form. */
data class FeatureSpec(
    val key: String,
    val label: String,
    /** What it means and its scale, shown to the model. */
    val meaning: String,
    /** Which data source measures it, shown to the model. */
    val source: String,
    val format: (Double) -> String
)

object FeatureCatalog {
    private val yesNo: (Double) -> String = { if (it >= 0.5) "Yes" else "No" }
    private val percent: (Double) -> String = { "${(it * 100).roundToInt()}%" }

    val all = listOf(
        FeatureSpec("avg_speed_kmh", "Road pace", "average speed along the route (distance ÷ Google's duration), km/h", "google_directions") { "%.0f km/h".format(it) },
        FeatureSpec("turns_per_km", "Route directness", "turn-by-turn steps per km; more = more winding", "google_directions") { "%.1f turns/km".format(it) },
        FeatureSpec("has_named_road", "Named through-road", "1 if Google names a major road on the route, else 0", "google_directions", yesNo),
        FeatureSpec("distance_km", "Route length", "total route length, km", "google_directions") { "%.1f km".format(it) },
        FeatureSpec("duration_min", "Trip time", "Google's estimated duration, minutes", "google_directions") { "%.0f min".format(it) },
        FeatureSpec("is_night", "After dark", "1 if it is dark now, else 0 (phone clock by default; get_weather_daylight makes it exact)", "device_clock / open_meteo", yesNo),
        FeatureSpec("lit_fraction", "Street lighting", "0-1 share of OSM-mapped road segments tagged lit=yes (only when OSM has lighting tags)", "osm_overpass", percent),
        FeatureSpec("footpath_fraction", "Footpaths & sidewalks", "0-1 share of nearby OSM ways that are footways/sidewalks/pedestrian streets", "osm_overpass", percent),
        FeatureSpec("places_per_km", "Shops & cafés along route", "count of OSM shops/cafés/restaurants/ATMs near the route, per km (not necessarily open now)", "osm_overpass") { "%.1f per km".format(it) },
        FeatureSpec("emergency_services_count", "Police / hospital nearby", "count of OSM police, hospital, clinic and fire stations within 600 m of the route", "osm_overpass") { "${it.roundToInt()} nearby" },
        FeatureSpec("open_places_now", "Places open right now", "count (max 20) of Google Places open right now within 400 m of the route midpoint", "google_places") { "${it.roundToInt()} open" },
        FeatureSpec("precipitation_mm", "Rain", "current precipitation, mm", "open_meteo") { "%.1f mm".format(it) },
        FeatureSpec("visibility_km", "Visibility", "current visibility, km", "open_meteo") { "%.1f km".format(it) }
    )

    private val byKey = all.associateBy { it.key }

    fun get(key: String): FeatureSpec? = byKey[key]
    fun label(key: String): String = byKey[key]?.label ?: key
    fun format(key: String, value: Double): String = byKey[key]?.format?.invoke(value) ?: "%.2f".format(value)
}
