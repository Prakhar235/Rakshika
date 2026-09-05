package com.rakshika.app.live

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
}
