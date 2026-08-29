package com.rakshika.saathi.data

/**
 * RakshikaSaathi — the companion / guardian app. It only *reads* the trip the
 * Rakshika app publishes to Firebase Realtime Database, over the RTDB REST
 * streaming API (Server-Sent Events) — no Firebase SDK, no google-services.json.
 */
object Config {

    /** Must match Rakshika's LiveShareConfig.DATABASE_URL. No trailing slash. */
    const val DATABASE_URL: String = "https://kriger-campus-32cf7-default-rtdb.firebaseio.com"

    const val TRIP_ID: String = "demo"

    /** Whose ride this app is watching — shown in the UI and notifications. */
    const val COMPANION_NAME: String = "Priya"

    val streamUrl: String
        get() = "$DATABASE_URL/liveTrips/$TRIP_ID.json"
}
