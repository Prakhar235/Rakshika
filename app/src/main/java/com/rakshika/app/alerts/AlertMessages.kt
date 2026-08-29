package com.rakshika.app.alerts

import java.util.Locale

/**
 * Plain-text SMS bodies for each alert, in the shared "[RKSH]" wire format that
 * RakshikaSaathi parses (see its `sms/SmsWire.kt`):
 *
 *   [RKSH] <TYPE> <human text> loc:<lat>,<lng> https://maps.google.com/?q=<lat>,<lng>
 *
 * Coordinates are rounded to 4 decimals (~11 m) — the location shared over SMS
 * is deliberately approximate.
 */
object AlertMessages {

    private const val MARKER = "[RKSH]"

    private fun r(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun loc(lat: Double, lng: Double) =
        "loc:${r(lat)},${r(lng)} https://maps.google.com/?q=${r(lat)},${r(lng)}"

    fun sosHome(): String =
        "$MARKER SOS - I need help now. Please call me. (no GPS fix)"

    fun sosRide(destination: String, lat: Double, lng: Double): String =
        "$MARKER SOS en route to $destination. ${loc(lat, lng)}"

    fun missedCheckIn(): String =
        "$MARKER CHECKIN missed - I did not confirm I'm safe. Please reach me."

    fun rideStarted(destination: String, routeLabel: String, etaMinutes: Int, lat: Double, lng: Double): String =
        "$MARKER RIDE started to $destination ($routeLabel, ETA ${etaMinutes}m). ${loc(lat, lng)}"

    fun arrived(destination: String): String =
        "$MARKER ARRIVED safely at $destination. Ride ended."
}
