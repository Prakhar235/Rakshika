package com.rakshika.app.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import kotlin.coroutines.resume

/** The real starting point for the demo ride: device location + a human-readable label for it. */
object DeviceLocation {

    private fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Last known fix, falling back to a fresh one; null if permission is missing or none is available. */
    suspend fun lastKnown(context: Context): LatLng? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return suspendCancellableCoroutine { cont ->
            try {
                client.lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            cont.resume(LatLng(loc.latitude, loc.longitude))
                        } else {
                            // No cached fix yet — ask for one directly.
                            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                                .addOnSuccessListener { fresh ->
                                    cont.resume(fresh?.let { LatLng(it.latitude, it.longitude) })
                                }
                                .addOnFailureListener { cont.resume(null) }
                        }
                    }
                    .addOnFailureListener { cont.resume(null) }
            } catch (e: SecurityException) {
                cont.resume(null)
            }
        }
    }

    /** A short human label for a coordinate, via the Geocoding web service. Null on any failure. */
    suspend fun reverseGeocode(latLng: LatLng): String? {
        if (!MapsConfig.isConfigured) return null
        val url = "https://maps.googleapis.com/maps/api/geocode/json" +
            "?latlng=${latLng.latitude},${latLng.longitude}" +
            "&key=${URLEncoder.encode(MapsConfig.API_KEY, "UTF-8")}"
        val json = GeoHttp.getJson(url) ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optString("formatted_address").takeIf { it.isNotBlank() }
    }
}
