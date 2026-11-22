package com.rovian.mood.cast.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rovian.mood.MainActivity
import com.rovian.mood.R

/**
 * Keeps Mood alive while it is connected to a screen, so files streamed from
 * this phone keep flowing and DLNA sessions stay supervised with the screen off
 * or another app in front. Runs only while a session exists.
 */
class CastSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_DEVICE) ?: "your screen"
        ensureChannel()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setContentTitle("Connected to $name")
            .setContentText("Mood is casting from this phone")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Casting session", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Mood is connected to a screen"
                },
            )
        }
    }

    companion object {
        private const val TAG = "MoodCastService"
        private const val CHANNEL = "mood_session"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_DEVICE = "device"

        fun start(context: Context, deviceName: String) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, CastSessionService::class.java).putExtra(EXTRA_DEVICE, deviceName),
                )
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when a session resumes in the background.
                Log.w(TAG, "Session service not started", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastSessionService::class.java))
        }
    }
}
