package com.menal.pulserelay.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.menal.pulserelay.PulseRelayApp
import com.menal.pulserelay.monitor.HeartRateMonitorService
import com.menal.pulserelay.notifications.Notifications

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext as PulseRelayApp
        if (!app.settingsStore.load().enabled) return

        Notifications.createChannel(context)
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HeartRateMonitorService::class.java)
                    .setAction(HeartRateMonitorService.ACTION_START),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not restart service: ${e.javaClass.simpleName}")
            Notifications.postResumeFallback(context)
        }
    }

    private companion object {
        const val TAG = "PulseRelay"
    }
}
