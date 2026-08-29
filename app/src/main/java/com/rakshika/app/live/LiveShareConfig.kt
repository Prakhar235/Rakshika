package com.rakshika.app.live

import androidx.compose.ui.geometry.Offset

/**
 * Config for pushing the demo ride to Firebase Realtime Database over its REST API
 * (no Firebase SDK / google-services.json needed — see tracker.html for the reader side).
 *
 * SETUP — one line to change:
 *   1. Create a Firebase project + a Realtime Database.
 *   2. Set its rules to test mode:  { "rules": { ".read": true, ".write": true } }
 *   3. Paste the database URL below (looks like https://<project>-default-rtdb.firebaseio.com
 *      or https://<project>-default-rtdb.<region>.firebasedatabase.app).
 */
object LiveShareConfig {

    /** Realtime Database URL. No trailing slash. */
    const val DATABASE_URL: String = "https://kriger-campus-32cf7-default-rtdb.firebaseio.com"

    /** All demo rides publish under this key, so tracker.html always watches one path. */
    const val TRIP_ID: String = "demo"

    /** True once a real URL has been pasted in — used to skip network work otherwise. */
    val isConfigured: Boolean
        get() = !DATABASE_URL.contains("YOUR-PROJECT")

    /**
     * The mock map uses normalised (x, y) in [0, 1] with y growing downward (south).
     * We anchor that unit square onto a real-world bounding box (central Bengaluru,
     * around MG Road) so every value we publish is genuine lat/lng a normal map can plot.
     * Change these four numbers to move the demo anywhere.
     */
    const val NORTH_LAT: Double = 12.9820
    const val SOUTH_LAT: Double = 12.9620
    const val WEST_LNG: Double = 77.5850
    const val EAST_LNG: Double = 77.6080

    /** Map a normalised map point to real coordinates: [lat, lng]. */
    fun toGeo(p: Offset): DoubleArray {
        val lat = NORTH_LAT - p.y * (NORTH_LAT - SOUTH_LAT)
        val lng = WEST_LNG + p.x * (EAST_LNG - WEST_LNG)
        return doubleArrayOf(lat, lng)
    }
}
