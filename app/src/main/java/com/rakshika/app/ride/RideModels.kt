package com.rakshika.app.ride

import com.rakshika.app.data.model.ContactStatus
import com.rakshika.app.geo.pathLengthMeters
import com.rakshika.app.geo.remainingGeoPath
import com.rakshika.app.live.LiveShareConfig
import com.rakshika.app.routing.RouteComparison
import com.rakshika.app.routing.RouteCorridor
import com.rakshika.app.routing.RouteFact
import com.rakshika.app.routing.RouteScore
import com.rakshika.app.routing.RoutingResult
import com.rakshika.app.ui.mapkit.mockPathFor
import kotlin.math.roundToInt

/** [lat]/[lng] are only set for a live (Places API) search result — the static [PLACES] have none. */
data class Place(val name: String, val area: String, val lat: Double? = null, val lng: Double? = null)

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

/** Static offline fallback used when live search hasn't run yet, or the network call failed. */
fun localPlaces(query: String): List<Place> = PLACES.filter {
    it.name.contains(query, ignoreCase = true) || it.area.contains(query, ignoreCase = true)
}

/** Assumed walking pace for the offline demo path, when Directions found no live route to time it with. */
private const val FALLBACK_WALK_SPEED_KMH = 4.5

data class RouteOption(
    val label: String,
    val minutes: Int,
    val recommended: Boolean,
    val reasons: List<String>,
    val safetyScore: Int = 0,
    val corridor: RouteCorridor = RouteCorridor.MAIN_ROAD,
    /** Real, per-corridor facts from the live Directions response behind [safetyScore] — see [RouteScoring]. */
    val facts: List<RouteFact> = emptyList(),
    /** The routing service's real road-following polyline for this corridor, `[lat, lng]` pairs — null if none was found. */
    val geoPath: List<DoubleArray>? = null
)

/** The real geo path for this option: live road-following polyline when found, else the fixed mock-map stand-in. */
fun RouteOption.resolvedGeoPath(): List<DoubleArray> = geoPath ?: LiveShareConfig.toGeoPath(mockPathFor(corridor))

/**
 * Re-bases this option onto wherever the marker currently is (a mid-ride reroute), keeping its
 * safety scoring. Uses [geoRoutes]' matching corridor when Google Directions found one; otherwise
 * — no live alternate for this corridor, or no live routing at all — falls back to just the
 * remaining stretch of this option's own current path, so every route is always redrawn from the
 * marker's position, never left showing a stale line back to the original start.
 */
fun RouteOption.rerouted(geoRoutes: RoutingResult?, progress: Float): RouteOption {
    val real = when (corridor) {
        RouteCorridor.MAIN_ROAD -> geoRoutes?.mainRoad
        RouteCorridor.BACK_LANE -> geoRoutes?.backLane
    }
    return if (real != null) {
        copy(minutes = (real.durationSeconds / 60).roundToInt().coerceAtLeast(1), geoPath = real.points)
    } else {
        copy(geoPath = remainingGeoPath(resolvedGeoPath(), progress))
    }
}

/** [safe] is the higher-scoring corridor, [fast] is the other one; [summary] explains the score gap in plain text. */
data class RoutePair(val fast: RouteOption, val safe: RouteOption, val summary: String)

/** Projects a [RouteComparison] onto the two route cards the ride screen renders, attaching real
 *  road geometry per corridor when [geoRoutes] found any (see [com.rakshika.app.routing.GoogleRouting]). */
fun routePairFrom(comparison: RouteComparison, geoRoutes: RoutingResult?): RoutePair {
    val safe = comparison.safe.toRouteOption(geoRoutes)
    val fast = comparison.fast.toRouteOption(geoRoutes)
    return RoutePair(fast = fast, safe = safe, summary = summaryText(safe, fast))
}

private fun RouteScore.toRouteOption(geoRoutes: RoutingResult?): RouteOption {
    val real = when (corridor) {
        RouteCorridor.MAIN_ROAD -> geoRoutes?.mainRoad
        RouteCorridor.BACK_LANE -> geoRoutes?.backLane
    }
    val minutes = real?.let { (it.durationSeconds / 60).roundToInt().coerceAtLeast(1) } ?: fallbackMinutes(corridor)
    return RouteOption(
        label = label,
        minutes = minutes,
        recommended = recommended,
        reasons = facts.map { it.text },
        safetyScore = safetyScore,
        corridor = corridor,
        facts = facts,
        geoPath = real?.points
    )
}

/** A real distance-based ETA for the offline mock path (no live Directions duration to use), not a random guess. */
private fun fallbackMinutes(corridor: RouteCorridor): Int {
    val meters = pathLengthMeters(LiveShareConfig.toGeoPath(mockPathFor(corridor)))
    return (meters / 1000.0 / FALLBACK_WALK_SPEED_KMH * 60).roundToInt().coerceAtLeast(1)
}

private fun summaryText(safe: RouteOption, fast: RouteOption): String {
    val diff = safe.minutes - fast.minutes
    val etaText = when {
        diff <= -1 -> "and it's ${-diff} min faster"
        diff == 0 -> "at the same ETA"
        diff <= 3 -> "for only $diff min more"
        else -> "though it adds $diff min"
    }
    val lead = safe.reasons.firstOrNull() ?: "it scored higher on the live route data"
    return "From Google's live routing: the ${safe.label.lowercase()} scores ${safe.safetyScore}/100 vs " +
        "${fast.safetyScore}/100 — ${lead.replaceFirstChar { it.lowercase() }} — $etaText."
}

enum class RideStep { SEARCH, ROUTES, RIDING, ARRIVED }

data class RideState(
    val step: RideStep = RideStep.SEARCH,
    val query: String = "",
    val assessing: Boolean = false,
    val destination: Place? = null,
    val routes: RoutePair? = null,
    /** Live Places API results for [query]; null while unsearched/blank, or if the last call failed. */
    val searchResults: List<Place>? = null,
    val searching: Boolean = false,
    /** Live Places API "nearby" results near the device, shown as "Nearby" before the user types anything. */
    val nearbyResults: List<Place>? = null,
    /** Whether search/routing are biased to the device's real location vs. the demo's fixed default. */
    val usingDeviceLocation: Boolean = false,
    val safeSelected: Boolean = true,
    val rideProgress: Float = 0f,
    val etaMinutesLeft: Int = 0,
    val sharingLive: Boolean = false,
    val contactAmma: ContactStatus? = null,
    val contactRohan: ContactStatus? = null,
    val sosActive: Boolean = false,
    /** True while a mid-ride reroute is being recalculated from the marker's current position. */
    val rerouting: Boolean = false
) {
    val suggestions: List<Place>
        get() = when {
            // Real places actually near the device once the Places API has answered; the static
            // coordinate-less list is only a fallback for offline/no-permission/no-fix.
            query.isBlank() -> nearbyResults ?: PLACES.take(5)
            // While a live search is in flight, don't show the (coordinate-less) local
            // fallback — tapping it before the Places API replies would silently lose lat/lng
            // and fall back to the mock route instead of a real routed one.
            searching -> emptyList()
            else -> searchResults ?: localPlaces(query)
        }
}
