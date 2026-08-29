package com.rakshika.app.alerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Sends alert SMS in the background via [SmsManager] — no app is opened, no user
 * tap. Requires the SEND_SMS runtime permission (requested from the Contacts
 * screen). If the permission is missing, [send] is a no-op that reports it back
 * so the caller can log "SMS skipped".
 */
object SmsAlerts {

    data class Result(val sent: Int, val failed: Int, val permission: Boolean) {
        val summary: String
            get() = when {
                !permission -> "SMS skipped — permission off"
                failed == 0 -> "SMS sent to $sent contact${if (sent == 1) "" else "s"}"
                else -> "SMS sent to $sent, failed $failed"
            }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun send(context: Context, numbers: List<String>, message: String): Result {
        if (!hasPermission(context)) return Result(0, 0, permission = false)
        val recipients = numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (recipients.isEmpty()) return Result(0, 0, permission = true)

        val sms = smsManager(context)
        var sent = 0
        var failed = 0
        recipients.forEach { number ->
            try {
                val parts = sms.divideMessage(message)
                if (parts.size > 1) {
                    sms.sendMultipartTextMessage(number, null, parts, null, null)
                } else {
                    sms.sendTextMessage(number, null, message, null, null)
                }
                sent++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "SMS to $number failed: ${e.message}")
            }
        }
        Log.i(TAG, "alert SMS -> sent=$sent failed=$failed")
        return Result(sent, failed, permission = true)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private const val TAG = "SmsAlerts"
}
