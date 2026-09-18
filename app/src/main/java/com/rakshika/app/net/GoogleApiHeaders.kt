package com.rakshika.app.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * When a Maps Platform API key is restricted to "Android apps" in Google Cloud Console, Google
 * only trusts a request as coming from that app if it carries these two headers with the app's
 * own package name and signing-certificate SHA-1 — the Maps SDK adds them automatically, but raw
 * REST calls (Places, Directions) must set them by hand or every call comes back REQUEST_DENIED.
 */
object GoogleApiHeaders {
    fun apply(conn: HttpURLConnection, context: Context) {
        conn.setRequestProperty("X-Android-Package", context.packageName)
        certSha1(context)?.let { conn.setRequestProperty("X-Android-Cert", it) }
    }

    private fun certSha1(context: Context): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo ?: return@runCatching null
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures
        }
        val signature = signatures?.firstOrNull() ?: return@runCatching null
        // No colons here: the web-service header wants a plain hex string (unlike the
        // colon-delimited form the Cloud Console UI shows for the same fingerprint).
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        digest.joinToString("") { "%02X".format(it) }
    }.getOrNull()
}
