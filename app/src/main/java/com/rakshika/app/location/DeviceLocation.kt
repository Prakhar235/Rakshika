package com.rakshika.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The device's last-known location, read via the plain Android [LocationManager] —
 * no Google Play Services / FusedLocationProvider dependency needed. Biases/restricts
 * place search and real routing to near the user, and anchors the mock-map fallback
 * path (see [com.rakshika.app.live.LiveShareConfig.setLiveOrigin]) onto the device's
 * real position instead of the fixed demo spot.
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

    /**
     * Live fixes from every enabled provider (GPS for accuracy, network for a quick first fix) until
     * the collector stops. Emits nothing without location permission.
     */
    fun updates(context: Context, minTimeMs: Long = 2000L, minDistanceM: Float = 3f): Flow<Location> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasPermission(context)) {
            close()
            return@callbackFlow
        }
        val listener = LocationListener { trySend(it) }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.isProviderEnabled(provider)) continue
            try {
                manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call — nothing to listen to.
            } catch (e: IllegalArgumentException) {
                // Provider not present on this device.
            }
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}
