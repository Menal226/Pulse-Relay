package com.menal.pulserelay.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val sensors: Boolean,
    val backgroundSensors: Boolean,
    val notifications: Boolean,
    val batteryExempt: Boolean,
    val batteryOptimizationSupported: Boolean,
) {
    val ready: Boolean
        get() = sensors && backgroundSensors && notifications
}

object PermissionChecks {
    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private const val READ_HEALTH_BACKGROUND =
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    fun read(context: Context): PermissionStatus = PermissionStatus(
        sensors = hasSensors(context),
        backgroundSensors = hasBackgroundSensors(context),
        notifications = hasNotifications(context),
        batteryExempt = isBatteryExempt(context),
        batteryOptimizationSupported = canRequestBatteryExemption(context),
    )

    fun hasSensors(context: Context): Boolean {
        if (check(context, Manifest.permission.BODY_SENSORS)) return true
        return Build.VERSION.SDK_INT >= 36 && check(context, READ_HEART_RATE)
    }

    fun hasBackgroundSensors(context: Context): Boolean {
        if (check(context, Manifest.permission.BODY_SENSORS_BACKGROUND)) return true
        return Build.VERSION.SDK_INT >= 36 && check(context, READ_HEALTH_BACKGROUND)
    }

    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return check(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun isBatteryExempt(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun canRequestBatteryExemption(context: Context): Boolean {
        // Wear OS commonly resolves the ignore-optimizations intent to
        // FakeSettingsActivity, which does nothing. Treat watches as managed.
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            return false
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        return intent.resolveActivity(context.packageManager) != null
    }

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) {
        if (isBatteryExempt(context) || !canRequestBatteryExemption(context)) {
            openAppDetails(context)
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            openAppDetails(context)
        } catch (_: SecurityException) {
            openAppDetails(context)
        }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing else to open on this device.
        }
    }

    private fun check(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
