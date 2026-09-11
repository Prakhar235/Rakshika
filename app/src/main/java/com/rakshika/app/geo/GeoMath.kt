package com.rakshika.app.geo

/** Great-circle distance between two `[lat, lng]` points, in meters. */
fun haversineMeters(a: DoubleArray, b: DoubleArray): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b[0] - a[0])
    val dLng = Math.toRadians(b[1] - a[1])
    val lat1 = Math.toRadians(a[0])
    val lat2 = Math.toRadians(b[0])
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return 2 * r * Math.asin(Math.sqrt(h))
}

/** Total length of a lat/lng path, in meters. */
fun pathLengthMeters(points: List<DoubleArray>): Double {
    var total = 0.0
    for (i in 1 until points.size) total += haversineMeters(points[i - 1], points[i])
    return total
}

fun formatDistance(meters: Double): String =
    if (meters >= 950) "%.1f km".format(meters / 1000) else "${meters.toInt().coerceAtLeast(0)} m"

/** The `[lat, lng]` a fraction [t] (0..1) along a real geo path lands on, by arc length. */
fun geoPointAt(path: List<DoubleArray>, t: Float): DoubleArray {
    if (path.size < 2) return path.firstOrNull() ?: doubleArrayOf(0.0, 0.0)
    val segLens = DoubleArray(path.size - 1)
    var total = 0.0
    for (i in 1 until path.size) {
        val d = haversineMeters(path[i - 1], path[i])
        segLens[i - 1] = d
        total += d
    }
    var remaining = t.coerceIn(0f, 1f) * total
    for (i in segLens.indices) {
        val d = segLens[i]
        if (remaining <= d || i == segLens.lastIndex) {
            val local = if (d == 0.0) 0.0 else (remaining / d).coerceIn(0.0, 1.0)
            val a = path[i]
            val b = path[i + 1]
            return doubleArrayOf(a[0] + (b[0] - a[0]) * local, a[1] + (b[1] - a[1]) * local)
        }
        remaining -= d
    }
    return path.last()
}
