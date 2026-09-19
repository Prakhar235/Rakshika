package com.rakshika.app.routing

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One concrete, checkable fact behind a route's score — every number in [text] is read straight
 * off that corridor's own [GeoRoute] from the live Directions response, never a canned or
 * fabricated incident report.
 */
data class RouteFact(val text: String, val positive: Boolean)

data class RouteScore(
    val corridor: RouteCorridor,
    val label: String,
    val safetyScore: Int,
    val recommended: Boolean,
    val facts: List<RouteFact>,
    /** The real major roads Directions named for this corridor, e.g. "MG Road, Residency Road". */
    val roadNames: String?,
    /** True when this score is grounded in a real Directions route; false means Directions found
     *  none for this corridor and the app is showing/scoring the offline mock path instead. */
    val isLive: Boolean,
    /** True when [safetyScore]/[facts] came from [com.rakshika.app.risk.OpenAiRiskScorer] rather
     *  than the local heuristic below — see [RouteScoring.withModelScores]. */
    val scoredByModel: Boolean = false,
    /** The model's own one-sentence explanation for [safetyScore], verbatim from its response —
     *  null unless [scoredByModel] is true. Kept separate from [facts] so the UI can show it as
     *  its own line instead of folded into the heuristic facts list. */
    val modelReason: String? = null
)

data class RouteComparison(val safe: RouteScore, val fast: RouteScore)

/** One corridor's model-predicted score, same scale as [RouteScore.safetyScore] (1-99, higher =
 *  safer) — see [com.rakshika.app.risk.OpenAiRiskScorer]. */
data class ModelRiskScore(val corridorId: String, val safetyScore: Int, val reason: String)

/**
 * Scores each corridor from the real Directions API data Google returned for the route just
 * searched — average speed, named through-roads, turn density — instead of a fictional safety
 * corpus. Google's Maps Platform has no actual crime/lighting/incident data to draw on, so this
 * is a transparent heuristic like the old one was, but every fact it cites is a real number from
 * that corridor's own response, not an invented report.
 *
 * When both corridors have a real route, they're scored **relative to each other** rather than
 * against fixed absolute thresholds. A fixed "≥25 km/h" or "has a named road" cutoff collapses
 * to the same verdict for both routes whenever they sit on the same side of it — which is the
 * normal case on a rural/village search, where neither alternate is a fast arterial and neither
 * has a name in Google's data. Comparing the two real routes directly means a genuine difference
 * between them (one a little faster, one a little more direct, one named and one not) still
 * separates the scores, instead of both landing on the same bucket and tying.
 */
object RouteScoring {
    private const val MAIN_ROAD_SPEED_KMH = 25.0
    private const val BASE_SCORE = 50.0

    fun compare(routes: RoutingResult?): RouteComparison = finalize(rawScores(routes))

    /**
     * The per-corridor heuristic score/facts, before deciding which one is "safe" vs "fast" —
     * this is the seam [RideViewModel][com.rakshika.app.ride.RideViewModel] uses to hand the same
     * real Directions facts to [com.rakshika.app.risk.OpenAiRiskScorer] and, if that call
     * succeeds, override the numbers below via [withModelScores] before calling [finalize].
     */
    fun rawScores(routes: RoutingResult?): Pair<RouteScore, RouteScore> {
        val mainRoute = routes?.mainRoad
        val backRoute = routes?.backLane

        return when {
            mainRoute != null && backRoute != null -> compareLive(mainRoute, backRoute)
            mainRoute != null -> solo(RouteCorridor.MAIN_ROAD, "Main road", mainRoute) to
                missing(RouteCorridor.BACK_LANE, "Back lane")
            backRoute != null -> missing(RouteCorridor.MAIN_ROAD, "Main road") to
                solo(RouteCorridor.BACK_LANE, "Back lane", backRoute)
            else -> missing(RouteCorridor.MAIN_ROAD, "Main road") to missing(RouteCorridor.BACK_LANE, "Back lane")
        }
    }

    /** Picks the higher-scoring corridor as "safe" (ties go to the main road — busier and easier
     *  to get help on) and the other as "fast", from either heuristic or model-overridden scores. */
    fun finalize(scores: Pair<RouteScore, RouteScore>): RouteComparison {
        val (main, back) = scores
        val safe = if (back.safetyScore > main.safetyScore) back else main
        val fast = if (safe.corridor == main.corridor) back else main

        return RouteComparison(
            safe = safe.copy(recommended = true),
            fast = fast.copy(recommended = false)
        )
    }

    /** Overrides each corridor's heuristic [RouteScore.safetyScore]/[RouteScore.facts] with the
     *  model's own prediction/reason wherever [modelScores] has an entry for it (keyed
     *  "main"/"back") — a corridor the model didn't score (no live route to send it, or the API
     *  call only covered one side) keeps its heuristic score untouched. */
    fun withModelScores(scores: Pair<RouteScore, RouteScore>, modelScores: Map<String, ModelRiskScore>): Pair<RouteScore, RouteScore> {
        val (main, back) = scores
        return applyModelScore(main, "main", modelScores) to applyModelScore(back, "back", modelScores)
    }

    private fun applyModelScore(score: RouteScore, id: String, modelScores: Map<String, ModelRiskScore>): RouteScore {
        val model = modelScores[id] ?: return score
        return score.copy(
            safetyScore = model.safetyScore,
            facts = listOf(RouteFact(model.reason, positive = true)) + score.facts,
            scoredByModel = true,
            modelReason = model.reason
        )
    }

    /** Both corridors have a real Directions route — score each one against the other's real numbers. */
    private fun compareLive(mainRoute: GeoRoute, backRoute: GeoRoute): Pair<RouteScore, RouteScore> {
        var mainScore = BASE_SCORE
        var backScore = BASE_SCORE
        val mainFacts = mutableListOf<RouteFact>()
        val backFacts = mutableListOf<RouteFact>()

        // Speed, compared directly between the two real routes rather than against a fixed
        // cutoff — this is what keeps the scores apart when neither route is a fast arterial.
        val speedDelta = mainRoute.avgSpeedKmh - backRoute.avgSpeedKmh
        if (abs(speedDelta) >= 0.5) {
            val pts = (speedDelta * 1.8).coerceIn(-18.0, 18.0)
            mainScore += pts
            backScore -= pts
            val mainKmh = "%.0f".format(mainRoute.avgSpeedKmh)
            val backKmh = "%.0f".format(backRoute.avgSpeedKmh)
            mainFacts += RouteFact("$mainKmh km/h average vs $backKmh km/h on the back lane, per Google's own timing.", pts >= 0)
            backFacts += RouteFact("$backKmh km/h average vs $mainKmh km/h on the main road, per Google's own timing.", pts < 0)
        }

        // Named road: whichever corridor Google actually names in its route summary scores
        // higher. When neither is named — common in villages/rural areas Google hasn't tagged
        // with proper road names — this factor is a wash instead of forcing a fake split.
        when {
            mainRoute.summary != null && backRoute.summary == null -> {
                mainScore += 12; backScore -= 12
                mainFacts += RouteFact("Runs along ${mainRoute.summary} — a named through-road; the back lane has none.", true)
                backFacts += RouteFact("No named road here, while the main road runs along ${mainRoute.summary}.", false)
            }
            backRoute.summary != null && mainRoute.summary == null -> {
                mainScore -= 12; backScore += 12
                mainFacts += RouteFact("No named road here, while the back lane runs along ${backRoute.summary}.", false)
                backFacts += RouteFact("Runs along ${backRoute.summary} — a named through-road; the main road has none.", true)
            }
            mainRoute.summary != null && backRoute.summary != null -> {
                mainFacts += RouteFact("Runs along ${mainRoute.summary}.", true)
                backFacts += RouteFact("Runs along ${backRoute.summary}.", true)
            }
            else -> {
                val note = "Google names no major road on either path here — this looks like unmapped rural/village " +
                    "territory rather than a lit-arterial-vs-back-lane split."
                mainFacts += RouteFact(note, false)
                backFacts += RouteFact(note, false)
            }
        }

        // Turn density: the more direct (fewer turns per km) path scores higher.
        val mainTurnsPerKm = turnsPerKm(mainRoute)
        val backTurnsPerKm = turnsPerKm(backRoute)
        val turnDelta = backTurnsPerKm - mainTurnsPerKm // positive => main is more direct
        if (abs(turnDelta) >= 0.3) {
            val pts = (turnDelta * 4.0).coerceIn(-8.0, 8.0)
            mainScore += pts
            backScore -= pts
            val mainKm = "%.1f".format(mainRoute.distanceMeters / 1000.0)
            val backKm = "%.1f".format(backRoute.distanceMeters / 1000.0)
            mainFacts += RouteFact("${mainRoute.stepCount} turns over $mainKm km vs ${backRoute.stepCount} over $backKm km on the back lane.", pts >= 0)
            backFacts += RouteFact("${backRoute.stepCount} turns over $backKm km vs ${mainRoute.stepCount} over $mainKm km on the main road.", pts < 0)
        }

        val main = RouteScore(
            corridor = RouteCorridor.MAIN_ROAD,
            label = "Main road",
            safetyScore = mainScore.coerceIn(1.0, 99.0).roundToInt(),
            recommended = false,
            facts = mainFacts,
            roadNames = mainRoute.summary,
            isLive = true
        )
        val back = RouteScore(
            corridor = RouteCorridor.BACK_LANE,
            label = "Back lane",
            safetyScore = backScore.coerceIn(1.0, 99.0).roundToInt(),
            recommended = false,
            facts = backFacts,
            roadNames = backRoute.summary,
            isLive = true
        )
        return main to back
    }

    /** Only one corridor got a real route back from Directions — nothing to compare it against, so score it on its own absolute numbers. */
    private fun solo(corridor: RouteCorridor, label: String, route: GeoRoute): RouteScore {
        var score = BASE_SCORE
        val facts = mutableListOf<RouteFact>()
        val speed = route.avgSpeedKmh.roundToInt()

        if (route.avgSpeedKmh >= MAIN_ROAD_SPEED_KMH) {
            score += 20
            facts += RouteFact(
                "$speed km/h average speed on Google's route — a through-road pace, above the ${MAIN_ROAD_SPEED_KMH.toInt()} km/h main-road cutoff.",
                positive = true
            )
        } else {
            score -= 8
            facts += RouteFact("$speed km/h average speed on Google's route — a slower, likely residential pace.", positive = false)
        }

        if (route.summary != null) {
            score += 12
            facts += RouteFact("Runs along ${route.summary} — a named through-road on Google Maps.", positive = true)
        } else {
            score -= 10
            facts += RouteFact("Google's route summary names no major road here — likely unnamed lanes or alleys.", positive = false)
        }

        facts += RouteFact("Google Directions only found a live route on this corridor — the other side has no real data to compare against.", positive = false)

        return RouteScore(
            corridor = corridor,
            label = label,
            safetyScore = score.coerceIn(1.0, 99.0).roundToInt(),
            recommended = false,
            facts = facts,
            roadNames = route.summary,
            isLive = true
        )
    }

    private fun missing(corridor: RouteCorridor, label: String): RouteScore = RouteScore(
        corridor = corridor,
        label = label,
        safetyScore = BASE_SCORE.roundToInt(),
        recommended = false,
        facts = listOf(
            RouteFact(
                "Google Directions returned no live route for this corridor — showing the offline demo path, with no real data to score it on.",
                positive = false
            )
        ),
        roadNames = null,
        isLive = false
    )

    private fun turnsPerKm(route: GeoRoute): Double {
        val km = (route.distanceMeters / 1000.0).coerceAtLeast(0.1)
        return route.stepCount / km
    }
}
