package com.rakshika.app.risk

import com.rakshika.app.routing.GeoRoute
import java.util.Calendar
import kotlin.math.roundToInt

/** Turns the real Google Directions numbers for a corridor into the named measurements the equation reads. */
object RouteFeatures {
    /** Dark hours by the phone's clock — a stand-in until the weather/daylight source gives the exact answer. */
    private const val NIGHT_START_HOUR = 19
    private const val NIGHT_END_HOUR = 6

    fun from(route: GeoRoute, hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Map<String, Double> {
        val km = (route.distanceMeters / 1000.0).coerceAtLeast(0.1)
        return mapOf(
            "avg_speed_kmh" to round1(route.avgSpeedKmh),
            "turns_per_km" to round1(route.stepCount / km),
            "has_named_road" to (if (route.summary != null) 1.0 else 0.0),
            "distance_km" to round1(route.distanceMeters / 1000.0),
            "duration_min" to (route.durationSeconds / 60.0).roundToInt().toDouble(),
            "is_night" to (if (hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR) 1.0 else 0.0)
        )
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
