package com.rakshika.app.alerts

import android.net.Uri
import java.util.Locale

/**
 * Plain-text SMS bodies for each alert, in the shared "[RKSH]" wire format that
 * RakshikaSaathi parses (see its `sms/SmsWire.kt`):
 *
 *   [RKSH] <human text> rakshika://track?k=<TYPE>&lat=<lat>&lng=<lng>&d=<dest>
 *
 * The link opens RakshikaSaathi straight onto the location — no Google Maps.
 * Coordinates are rounded to 4 decimals (~11 m) — the location shared over SMS
 * is deliberately approximate (it's the last fix the phone had).
 */
object AlertMessages {

    private const val MARKER = "[RKSH]"

    /** Deep link RakshikaSaathi registers. */
    const val LINK = "rakshika://track"

    private fun r(v: Double) = String.format(Locale.US, "%.4f", v)

    private fun link(type: String, lat: Double? = null, lng: Double? = null, dest: String? = null): String {
        val sb = StringBuilder("$LINK?k=$type")
        if (lat != null && lng != null) sb.append("&lat=${r(lat)}&lng=${r(lng)}")
        if (!dest.isNullOrBlank()) sb.append("&d=").append(Uri.encode(dest))
        return sb.toString()
    }

    fun sosHome(): String =
        "$MARKER SOS - I need help now. Please call me. ${link("SOS")}"

    fun sosRide(destination: String, lat: Double, lng: Double): String =
        "$MARKER SOS en route to $destination. ${link("SOS", lat, lng, destination)}"

    fun missedCheckIn(): String =
        "$MARKER CHECKIN missed - I did not confirm I'm safe. ${link("CHECKIN")}"

    fun rideStarted(destination: String, routeLabel: String, etaMinutes: Int, lat: Double, lng: Double): String =
        "$MARKER RIDE started to $destination ($routeLabel, ETA ${etaMinutes}m). ${link("RIDE", lat, lng, destination)}"

    fun arrived(destination: String): String =
        "$MARKER ARRIVED safely at $destination. ${link("ARRIVED", dest = destination)}"
}
