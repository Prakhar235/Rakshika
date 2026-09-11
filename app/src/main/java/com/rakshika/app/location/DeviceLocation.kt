package com.rakshika.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * The device's last-known location, read via the plain Android [LocationManager] —
 * no Google Play Services / FusedLocationProvider dependency needed. Used only to
 * bias/restrict place search and real routing to near the user; the ride simulation
 * itself is unaffected.
 */
object DeviceLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns `[lat, lng]` from the most accurate last-known fix across all providers, or null. */
    fun lastKnown(context: Context): DoubleArray? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var best: Location? = null
        for (provider in manager.allProviders) {
            val fix = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            } ?: continue
            if (best == null || fix.accuracy < best.accuracy) best = fix
        }
        return best?.let { doubleArrayOf(it.latitude, it.longitude) }
    }
}
