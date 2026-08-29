package com.rakshika.app.alerts

/** Plain-text SMS bodies for each alert. Kept short so most fit one SMS segment. */
object AlertMessages {

    private fun maps(lat: Double, lng: Double) = "https://maps.google.com/?q=$lat,$lng"

    fun sosHome(): String =
        "SOS from Rakshika. I need help now. Please call me immediately."

    fun sosRide(destination: String, lat: Double, lng: Double): String =
        "SOS from Rakshika. I need help on my way to $destination. " +
            "My live location: ${maps(lat, lng)}"

    fun missedCheckIn(): String =
        "Rakshika check-in missed. I did not confirm I'm safe on time. Please reach me."

    fun rideStarted(destination: String, routeLabel: String, etaMinutes: Int, lat: Double, lng: Double): String =
        "Starting a Rakshika ride to $destination ($routeLabel route, ETA $etaMinutes min). " +
            "Destination: ${maps(lat, lng)}"

    fun arrived(destination: String): String =
        "All clear — I've reached $destination safely. Rakshika ride ended."
}
