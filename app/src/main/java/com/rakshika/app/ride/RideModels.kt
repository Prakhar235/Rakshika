package com.rakshika.app.ride

import com.google.android.gms.maps.model.LatLng
import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.PlaceSuggestion
import com.rakshika.app.rag.RagResult
import com.rakshika.app.rag.RouteAssessment
import com.rakshika.app.rag.RouteCorridor
import com.rakshika.app.rag.RouteEvidence
import com.rakshika.app.rag.SafetyDatasets

/** A real, geocoded point — origin or destination — for the demo ride. */
data class Place(val name: String, val area: String, val lat: Double, val lng: Double) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

data class RouteOption(
    val label: String,
    val minutes: Int,
    val recommended: Boolean,
    val reasons: List<String>,
    val safetyScore: Int = 0,
    val corridor: RouteCorridor = RouteCorridor.MAIN_ROAD,
    val evidence: List<RouteEvidence> = emptyList(),
    /** The real polyline (Directions API) this route walks. */
    val path: List<LatLng> = emptyList()
)

/** [safe] is the RAG-recommended corridor, [fast] is the other one (kept for screen wiring). */
data class RoutePair(val fast: RouteOption, val safe: RouteOption)

/** Projects a [RagResult] onto the two route cards the ride screen renders, real polylines attached. */
fun routePairFrom(result: RagResult, paths: Map<RouteCorridor, List<LatLng>>): RoutePair = RoutePair(
    safe = result.safest.toRouteOption(paths),
    fast = result.routes.first { !it.recommended }.toRouteOption(paths)
)

private fun RouteAssessment.toRouteOption(paths: Map<RouteCorridor, List<LatLng>>) = RouteOption(
    label = label,
    minutes = minutes,
    recommended = recommended,
    reasons = rationale,
    safetyScore = safetyScore,
    corridor = corridor,
    evidence = evidence,
    path = paths[corridor].orEmpty()
)

enum class RideStep { SEARCH, ROUTES, RIDING, ARRIVED }

data class RideState(
    val step: RideStep = RideStep.SEARCH,
    val query: String = "",
    val selectedDatasetId: String = SafetyDatasets.ALL.first().id,
    /** Real device location (or the fixed fallback), resolved once on entry; null while locating. */
    val origin: Place? = null,
    val locatingOrigin: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    /** Live Places Autocomplete results for the current query. */
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
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
)
