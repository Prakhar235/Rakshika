package com.rakshika.app.geo

import com.google.android.gms.maps.model.LatLng
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Arc-length interpolation along a real lat/lng polyline — the geo equivalent of the
 * normalized-space `pointAt` in ui/mapkit/MockMap.kt, used by the ride's moving dot and by
 * LiveShareRepository's location fixes. Distances use a flat equirectangular approximation,
 * which is accurate enough at city-block scale.
 */
object GeoPath {

    fun pointAt(path: List<LatLng>, t: Float): LatLng {
        if (path.isEmpty()) return MapsConfig.FALLBACK_ORIGIN
        if (path.size == 1) return path.first()

        val segLens = FloatArray(path.size - 1)
        var total = 0f
        for (i in 1 until path.size) {
            val d = distance(path[i - 1], path[i])
            segLens[i - 1] = d
            total += d
        }

        var remaining = t.coerceIn(0f, 1f) * total
        for (i in segLens.indices) {
            val d = segLens[i]
            if (remaining <= d || i == segLens.size - 1) {
                val local = if (d == 0f) 0f else (remaining / d).coerceIn(0f, 1f)
                val a = path[i]
                val b = path[i + 1]
                return LatLng(
                    a.latitude + (b.latitude - a.latitude) * local,
                    a.longitude + (b.longitude - a.longitude) * local
                )
            }
            remaining -= d
        }
        return path.last()
    }

    /** Rough planar distance in meters — only used to compare/interpolate, not for display. */
    private fun distance(a: LatLng, b: LatLng): Float {
        val metersPerDegLat = 111_320.0
        val metersPerDegLng = 111_320.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
        val dy = (b.latitude - a.latitude) * metersPerDegLat
        val dx = (b.longitude - a.longitude) * metersPerDegLng
        return sqrt(dx * dx + dy * dy).toFloat()
    }
}
