package com.rakshika.saathi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rakshika.saathi.MainActivity
import com.rakshika.saathi.data.Config
import com.rakshika.saathi.data.FirebaseTripStream
import com.rakshika.saathi.data.TripEvent
import com.rakshika.saathi.data.TripRepository
import com.rakshika.saathi.data.TripSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the Firebase stream open while the companion is watching, mirrors it into
 * [TripRepository], and raises a system notification for START / SOS / ARRIVED.
 * Runs foreground so the stream survives the app being backgrounded.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var streamJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(FOREGROUND_ID, buildForegroundNotification(TripRepository.snapshot.value))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (streamJob == null) {
            TripRepository.reset()

            streamJob = scope.launch {
                FirebaseTripStream().events().collect { update ->
                    TripRepository.setConnected(update.connected)
                    if (update.connected) {
                        TripRepository.onTree(update.tree)
                        notificationManager.notify(
                            FOREGROUND_ID,
                            buildForegroundNotification(TripRepository.snapshot.value)
                        )
                    }
                }
            }

            scope.launch {
                TripRepository.newEvent.collect { event ->
                    if (event.notify) showEventNotification(event)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- notifications -----------------------------------------------------

    private val notificationManager get() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Live tracking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing companion tracking"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Ride updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Ride started, halfway, arrived"
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Emergency alerts from the person you are watching"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            }
        )
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildForegroundNotification(s: TripSnapshot?): Notification {
        val text = when {
            s == null || !s.active -> "Waiting for ${Config.COMPANION_NAME} to start a ride"
            s.sos -> "🚨 SOS — ${Config.COMPANION_NAME} needs help"
            s.arrived -> "${Config.COMPANION_NAME} arrived at ${s.destName}"
            else -> "${Config.COMPANION_NAME} → ${s.destName} · ${s.etaMinutesLeft} min · ${(s.progress * 100).toInt()}%"
        }
        return NotificationCompat.Builder(this, CH_STATUS)
            .setContentTitle("RakshikaSaathi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun showEventNotification(event: TripEvent) {
        val channel = if (event.urgent) CH_SOS else CH_EVENTS
        val builder = NotificationCompat.Builder(this, channel)
            .setContentTitle(event.title)
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setSmallIcon(
                if (event.urgent) android.R.drawable.ic_dialog_alert
                else android.R.drawable.ic_menu_mylocation
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setPriority(if (event.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        if (event.urgent) builder.setCategory(NotificationCompat.CATEGORY_CALL)
        notificationManager.notify(EVENT_ID_BASE + event.kind.ordinal, builder.build())
    }

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_EVENTS = "events"
        private const val CH_SOS = "sos"
        private const val FOREGROUND_ID = 1
        private const val EVENT_ID_BASE = 100

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
