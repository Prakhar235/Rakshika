package com.rakshika.saathi.data

import org.json.JSONObject

data class GeoPoint(val lat: Double, val lng: Double)

/** One immutable view of the watched ride, parsed from the Firebase tree. */
data class TripSnapshot(
    val status: String,          // "riding" | "arrived" | "ended" | ""
    val sos: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val destName: String,
    val destArea: String,
    val routeLabel: String,
    val routeKind: String,       // "safe" | "fast"
    val safetyScore: Int,
    val reasons: List<String>,
    val polyline: List<GeoPoint>,
    val current: GeoPoint?,
    val progress: Double,
    val etaMinutesLeft: Int
) {
    val riding get() = status == "riding"
    val arrived get() = status == "arrived"
    val active get() = riding || arrived

    companion object {
        /** Returns null when there is no live trip to show. */
        fun from(tree: JSONObject?): TripSnapshot? {
            if (tree == null || tree.length() == 0) return null
            val status = tree.optString("status", "")
            if (status == "ended") return null
            val loc = tree.optJSONObject("location")

            val poly = tree.optJSONArray("polyline")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.let { p -> GeoPoint(p.optDouble("lat"), p.optDouble("lng")) }
                }
            } ?: emptyList()

            val route = tree.optJSONObject("route")
            val reasons = route?.optJSONArray("reasons")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()

            val dest = tree.optJSONObject("destination")

            return TripSnapshot(
                status = status,
                sos = tree.optBoolean("sos", false),
                startedAt = tree.optLong("startedAt", 0L),
                updatedAt = tree.optLong("updatedAt", 0L),
                destName = dest?.optString("name", "destination") ?: "destination",
                destArea = dest?.optString("area", "") ?: "",
                routeLabel = route?.optString("label", "") ?: "",
                routeKind = route?.optString("kind", "safe") ?: "safe",
                safetyScore = route?.optInt("safetyScore", 0) ?: 0,
                reasons = reasons,
                polyline = poly,
                current = loc?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lng")) },
                progress = loc?.optDouble("progress", 0.0) ?: 0.0,
                etaMinutesLeft = loc?.optInt("etaMinutesLeft", 0) ?: 0
            )
        }
    }
}

/** A derived, human-readable moment in the ride — shown in the log and (some) as notifications. */
data class TripEvent(
    val time: Long,
    val kind: Kind,
    val title: String,
    val detail: String
) {
    enum class Kind { START, PROGRESS, SOS, ARRIVED, ENDED, INFO }

    /** Whether the service should raise a system notification for this event. */
    val notify: Boolean get() = kind == Kind.START || kind == Kind.SOS || kind == Kind.ARRIVED
    val urgent: Boolean get() = kind == Kind.SOS
}
