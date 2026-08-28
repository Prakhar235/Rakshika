package com.rakshika.app.ride

import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.rag.RagResult
import com.rakshika.app.rag.RouteAssessment
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.RouteEvidence
import com.rakshika.app.rag.SafetyDatasets

data class Place(val name: String, val area: String)

val ORIGIN = Place("Hostel", "Sector 5")

val PLACES = listOf(
    Place("MG Road Metro", "Sector 14"),
    Place("City Central Mall", "Sector 18"),
    Place("Sunrise Apartments", "Sector 21"),
    Place("Tech Park · Gate 2", "Sector 62"),
    Place("Central Library", "Sector 15"),
    Place("Green Valley Hospital", "Sector 12"),
    Place("Riverside Cafe", "Sector 29"),
    Place("St. Xavier's College", "Sector 20"),
    Place("Old Bus Terminal", "Sector 8"),
    Place("Lakeview Residency", "Sector 33")
)

data class RouteOption(
    val label: String,
    val minutes: Int,
    val recommended: Boolean,
    val reasons: List<String>,
    val safetyScore: Int = 0,
    val corridor: RouteCorridor = RouteCorridor.MAIN_ROAD,
    val evidence: List<RouteEvidence> = emptyList()
)

/** [safe] is the RAG-recommended corridor, [fast] is the other one (kept for screen wiring). */
data class RoutePair(val fast: RouteOption, val safe: RouteOption)

/** Projects a [RagResult] onto the two route cards the ride screen renders. */
fun routePairFrom(result: RagResult): RoutePair = RoutePair(
    safe = result.safest.toRouteOption(),
    fast = result.routes.first { !it.recommended }.toRouteOption()
)

private fun RouteAssessment.toRouteOption() = RouteOption(
    label = label,
    minutes = minutes,
    recommended = recommended,
    reasons = rationale,
    safetyScore = safetyScore,
    corridor = corridor,
    evidence = evidence
)

enum class RideStep { SEARCH, ROUTES, RIDING, ARRIVED }

data class RideState(
    val step: RideStep = RideStep.SEARCH,
    val query: String = "",
    val selectedDatasetId: String = SafetyDatasets.ALL.first().id,
    val assessing: Boolean = false,
    val rag: RagResult? = null,
    val destination: Place? = null,
    val routes: RoutePair? = null,
    val safeSelected: Boolean = true,
    val rideProgress: Float = 0f,
    val etaMinutesLeft: Int = 0,
    val sharingLive: Boolean = false,
    val contactAmma: ContactStatus? = null,
    val contactRohan: ContactStatus? = null,
    val sosActive: Boolean = false
) {
    val suggestions: List<Place>
        get() = if (query.isBlank()) {
            PLACES.take(5)
        } else {
            PLACES.filter {
                it.name.contains(query, ignoreCase = true) || it.area.contains(query, ignoreCase = true)
            }
        }
}
