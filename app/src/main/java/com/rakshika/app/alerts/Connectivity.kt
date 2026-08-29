package com.rakshika.app.alerts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Decides whether the app currently has usable internet. Alert SMS is only sent
 * as a *fallback* when this returns false — when data is up, the live location
 * goes to Firebase instead (see [com.rakshika.app.live.LiveShareRepository]).
 */
object Connectivity {

    /** The Home screen's offline toggle flips this so the fallback can be demoed without killing data. */
    @Volatile
    var demoForceOffline: Boolean = false

    fun isOnline(context: Context): Boolean {
        if (demoForceOffline) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when SMS should carry the alert because there is no usable data connection. */
    fun smsFallbackActive(context: Context): Boolean = !isOnline(context)
}
