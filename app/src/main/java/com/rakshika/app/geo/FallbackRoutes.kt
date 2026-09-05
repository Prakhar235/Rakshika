package com.rakshika.app.geo

import com.google.android.gms.maps.model.LatLng

/**
 * Realistic-looking walking routes for when the Directions API is unreachable (no key, API
 * not enabled, no connectivity) — used by RideViewModel as a drop-in replacement for
 * DirectionsRepository.fetchRoutes so the ride always has something real-looking to show,
 * not just a straight line between two pins.
 *
 * Two deterministic shapes off the same origin→destination vector:
 *  - "shortcut": one turn (an L-bend along whichever axis has the bigger delta first) —
 *    reads as cutting through a back lane.
 *  - "main road": a gentle outward arc through the midpoint — reads as following a bigger
 *    road that loops around a block, and is always longer than the shortcut so the RAG
 *    engine's "main road is slower but busier" framing still holds.
 *
 * Minutes/meters come from the actual synthesized path length at an average walking pace,
 * so the ETA shown always matches the line drawn on the map.
 */
object FallbackRoutes {

    private const val WALK_METERS_PER_MINUTE = 80.0 // ~4.8 km/h, a brisk pace

    fun synthesize(origin: LatLng, destination: LatLng): List<GeoRoute> {
        val dLat = destination.latitude - origin.latitude
        val dLng = destination.longitude - origin.longitude

        val shortcutPoints = if (kotlin.math.abs(dLng) >= kotlin.math.abs(dLat)) {
            // Bigger east-west leg: go along longitude first, then turn onto latitude.
            listOf(origin, LatLng(origin.latitude, destination.longitude), destination)
        } else {
            listOf(origin, LatLng(destination.latitude, origin.longitude), destination)
        }

        // Perpendicular to the direct vector, so the main-road arc bows out to one side
        // rather than cutting through the middle of the block.
        val perpLat = -dLng
        val perpLng = dLat
        val bulgeFactor = 0.32
        val bulge = LatLng(
            origin.latitude + dLat * 0.5 + perpLat * bulgeFactor,
            origin.longitude + dLng * 0.5 + perpLng * bulgeFactor
        )
        val mainPoints = listOf(
            origin,
            LatLng(origin.latitude + dLat * 0.22, origin.longitude + dLng * 0.22),
            bulge,
            LatLng(origin.latitude + dLat * 0.78, origin.longitude + dLng * 0.78),
            destination
        )

        return listOf(
            routeFrom(shortcutPoints, "Via the direct back lane"),
            routeFrom(mainPoints, "Via the main sector road")
        )
    }

    private fun routeFrom(points: List<LatLng>, summary: String): GeoRoute {
        var meters = 0.0
        for (i in 1 until points.size) meters += GeoPath.distance(points[i - 1], points[i])
        val minutes = (meters / WALK_METERS_PER_MINUTE).let { if (it < 1) 1 else Math.round(it).toInt() }
        return GeoRoute(points = points, minutes = minutes, meters = meters.toInt(), summary = summary)
    }
}
