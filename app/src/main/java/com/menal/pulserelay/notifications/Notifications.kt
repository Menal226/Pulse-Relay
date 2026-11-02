package com.menal.pulserelay.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.menal.pulserelay.MainActivity
import com.menal.pulserelay.R
import com.menal.pulserelay.monitor.ConnectionState
import com.menal.pulserelay.monitor.HeartRateMonitorService
import com.menal.pulserelay.permissions.PermissionChecks

object Notifications {
    const val CHANNEL_ID = "hr_stream"
    const val FOREGROUND_ID = 1
    const val FALLBACK_ID = 2
    const val ERROR_ID = 4

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        bpm: Int?,
        connection: ConnectionState,
        error: String?,
    ): Notification {
        val status = when (connection) {
            ConnectionState.DISCONNECTED -> "Disconnected"
            ConnectionState.CONNECTING -> "Connecting"
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.OFFLINE -> "Offline - waiting for internet"
            ConnectionState.RECONNECTING -> "Reconnecting"
            ConnectionState.ERROR -> "Error"
        }
        val text = if (error != null) {
            error
        } else {
            buildString {
                if (bpm != null) append("$bpm bpm • ")
                append(status)
            }
        }

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, HeartRateMonitorService::class.java)
                .setAction(HeartRateMonitorService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(
                R.drawable.ic_stat_pulse,
                context.getString(R.string.notification_stop),
                stop,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, bpm: Int?, connection: ConnectionState, error: String?) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context)
            .notify(FOREGROUND_ID, build(context, bpm, connection, error))
    }

    @SuppressLint("MissingPermission")
    fun postError(context: Context, message: String) {
        if (!canNotify(context)) return
        val openApp = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Pulse Relay stopped")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ERROR_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(FOREGROUND_ID)
        NotificationManagerCompat.from(context).cancel(FALLBACK_ID)
        NotificationManagerCompat.from(context).cancel(ERROR_ID)
    }

    @SuppressLint("MissingPermission")
    fun postResumeFallback(context: Context) {
        if (!canNotify(context)) return
        val openApp = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText("Tap to resume heart-rate streaming")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(FALLBACK_ID, notification)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
