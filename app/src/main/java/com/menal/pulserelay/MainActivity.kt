package com.menal.pulserelay

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import com.menal.pulserelay.data.ValidSettings
import com.menal.pulserelay.monitor.HeartRateMonitorService
import com.menal.pulserelay.permissions.PermissionChecks
import com.menal.pulserelay.permissions.PermissionStatus
import com.menal.pulserelay.ui.AppScreen
import com.menal.pulserelay.ui.MainScreen
import com.menal.pulserelay.ui.SettingsScreen
import com.menal.pulserelay.ui.SetupScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private lateinit var app: PulseRelayApp
    private val permissions = MutableStateFlow(
        PermissionStatus(
            sensors = false,
            backgroundSensors = false,
            notifications = false,
            batteryExempt = false,
            batteryOptimizationSupported = false,
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as PulseRelayApp
        permissions.value = PermissionChecks.read(this)
        maybeRestoreService()

        setContent {
            val snapshot by app.monitorState.state.collectAsState()
            val permissionState by permissions.collectAsState()
            var screen by remember { mutableStateOf(AppScreen.MAIN) }

            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                permissions.value = PermissionChecks.read(this)
            }

            fun requestForegroundPermissions() {
                val requested = mutableListOf(Manifest.permission.BODY_SENSORS)
                if (Build.VERSION.SDK_INT >= 33) {
                    requested += Manifest.permission.POST_NOTIFICATIONS
                }
                permissionLauncher.launch(requested.toTypedArray())
            }

            fun requestBackgroundPermission() {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.BODY_SENSORS_BACKGROUND)
                    )
                } else {
                    PermissionChecks.openAppDetails(this)
                }
            }

            fun startMonitor() {
                if (app.settingsStore.validatedOrNull() == null) {
                    screen = AppScreen.SETTINGS
                    return
                }
                if (!permissionState.ready) {
                    screen = AppScreen.SETUP
                    return
                }
                try {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, HeartRateMonitorService::class.java)
                            .setAction(HeartRateMonitorService.ACTION_START),
                    )
                    screen = AppScreen.MAIN
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start service: ${e.javaClass.simpleName}")
                    Toast.makeText(this, "Could not start service", Toast.LENGTH_SHORT).show()
                }
            }

            fun stopMonitor() {
                try {
                    startService(
                        Intent(this, HeartRateMonitorService::class.java)
                            .setAction(HeartRateMonitorService.ACTION_STOP),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not stop service: ${e.javaClass.simpleName}")
                    stopService(Intent(this, HeartRateMonitorService::class.java))
                }
            }

            BackHandler(enabled = screen != AppScreen.MAIN) {
                screen = AppScreen.MAIN
            }

            MaterialTheme {
                Scaffold {
                    when (screen) {
                        AppScreen.MAIN -> MainScreen(
                            snapshot = snapshot,
                            permissions = permissionState,
                            onStart = ::startMonitor,
                            onStop = ::stopMonitor,
                            onSettings = { screen = AppScreen.SETTINGS },
                            onSetup = { screen = AppScreen.SETUP },
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            initial = app.settingsStore.load(),
                            onSaved = { valid ->
                                saveSettings(valid)
                                screen = AppScreen.MAIN
                            },
                            onBack = { screen = AppScreen.MAIN },
                        )

                        AppScreen.SETUP -> SetupScreen(
                            permissions = permissionState,
                            onRequestForeground = ::requestForegroundPermissions,
                            onRequestBackground = ::requestBackgroundPermission,
                            onOpenSettings = { PermissionChecks.openAppDetails(this) },
                            onBattery = { PermissionChecks.requestBatteryExemption(this) },
                            onBack = { screen = AppScreen.MAIN },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions.value = PermissionChecks.read(this)
    }

    private fun saveSettings(valid: ValidSettings) {
        app.settingsStore.save(valid)
        if (app.settingsStore.load().enabled) {
            try {
                startService(
                    Intent(this, HeartRateMonitorService::class.java)
                        .setAction(HeartRateMonitorService.ACTION_APPLY),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not apply settings: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun maybeRestoreService() {
        val stored = app.settingsStore.load()
        if (!stored.enabled) return
        if (app.settingsStore.validatedOrNull() == null) return
        if (!PermissionChecks.read(this).ready) return
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, HeartRateMonitorService::class.java)
                    .setAction(HeartRateMonitorService.ACTION_START),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore service: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "PulseRelay"
    }
}

