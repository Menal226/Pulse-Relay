package com.menal.pulserelay.monitor

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.menal.pulserelay.PulseRelayApp
import com.menal.pulserelay.data.SettingsStore
import com.menal.pulserelay.data.ValidSettings
import com.menal.pulserelay.net.PulseSocket
import com.menal.pulserelay.net.NetworkMonitor
import com.menal.pulserelay.net.ReconnectPolicy
import com.menal.pulserelay.notifications.Notifications
import com.menal.pulserelay.permissions.PermissionChecks
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class HeartRateMonitorService : Service() {

    private lateinit var settings: SettingsStore
    private lateinit var monitorState: MonitorState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val reconnectPolicy = ReconnectPolicy()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val socket: PulseSocket by lazy {
        PulseSocket(
            client = httpClient,
            listener = object : PulseSocket.Listener {
                override fun onConnected() = handleConnected()
                override fun onDisconnected(reason: String?) = handleDisconnected(reason)
                override fun onAuthRejected() = handleAuthRejected()
            },
        )
    }

    private lateinit var networkMonitor: NetworkMonitor
    private var sampler: HeartRateSampler? = null

    private var sendJob: Job? = null
    private var reconnectJob: Job? = null
    private var notificationJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false

    @Volatile
    private var online = false

    @Volatile
    private var connecting = false

    @Volatile
    private var authBlocked = false

    @Volatile
    private var currentEndpoint = ""

    @Volatile
    private var currentToken = ""

    @Volatile
    private var currentIntervalSec = 1

    override fun onCreate() {
        super.onCreate()
        val app = application as PulseRelayApp
        settings = app.settingsStore
        monitorState = app.monitorState
        Notifications.createChannel(this)
        networkMonitor = NetworkMonitor(this) { isOnline ->
            handleConnectivityChanged(isOnline)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming(explicit = true)
                return START_NOT_STICKY
            }

            ACTION_START -> settings.setEnabled(true)
            ACTION_APPLY -> {
                if (running) {
                    applySettings()
                    return START_STICKY
                }
            }
        }

        if (!settings.load().enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        val valid = settings.validatedOrNull()
        if (valid == null) {
            fail("Settings are invalid")
            return START_NOT_STICKY
        }

        if (!permissionsReady()) {
            fail("Sensor, background sensor, or notification permission is missing")
            return START_NOT_STICKY
        }

        if (!ensureForeground()) {
            fail("Could not start the foreground service")
            return START_NOT_STICKY
        }

        if (!running) {
            startMonitoring(valid)
        } else {
            applySettings()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        cleanup()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring(valid: ValidSettings) {
        running = true
        authBlocked = false
        connecting = false
        currentEndpoint = valid.endpoint
        currentToken = valid.token
        currentIntervalSec = valid.intervalSec

        monitorState.setEnabled(true)
        monitorState.setInterval(valid.intervalSec)
        monitorState.setBpm(null)
        monitorState.setConnection(ConnectionState.CONNECTING)

        acquireWakeLock()

        sampler = HeartRateSampler(
            context = this,
            onReading = { bpm, atElapsedMs ->
                if (running) {
                    monitorState.setBpm(bpm, atElapsedMs)
                }
            },
            onError = { message ->
                if (running) {
                    fail(message)
                }
            },
        )
        if (!sampler!!.start()) {
            return
        }

        networkMonitor.start()

        startSendLoop()
        startNotificationLoop()
        updateNotification()

        if (online) {
            connectNow()
        } else {
            monitorState.setConnection(ConnectionState.OFFLINE)
            updateNotification()
        }
    }

    private fun applySettings() {
        val valid = settings.validatedOrNull()
        if (valid == null) {
            fail("Settings are invalid")
            return
        }
        if (!permissionsReady()) {
            fail("Sensor, background sensor, or notification permission is missing")
            return
        }

        val endpointChanged = valid.endpoint != currentEndpoint
        val tokenChanged = valid.token != currentToken
        val intervalChanged = valid.intervalSec != currentIntervalSec

        currentEndpoint = valid.endpoint
        currentToken = valid.token
        currentIntervalSec = valid.intervalSec
        monitorState.setInterval(valid.intervalSec)

        if (endpointChanged || tokenChanged || authBlocked) {
            reconnectJob?.cancel()
            socket.close()
            connecting = false
            authBlocked = false
            if (online) {
                connectNow()
            } else {
                monitorState.setConnection(ConnectionState.OFFLINE)
            }
        }

        if (intervalChanged) {
            startSendLoop()
        }
        updateNotification()
    }

    private fun startSendLoop() {
        sendJob?.cancel()
        val intervalMs = currentIntervalSec * 1000L
        sendJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (!running || !online) continue
                if (!socket.isOpen()) continue
                val bpm = sampler?.currentBpm() ?: continue
                socket.sendByte(bpm)
            }
        }
    }

    private fun startNotificationLoop() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            while (isActive) {
                delay(10_000L)
                if (!running) continue
                val fresh = sampler?.currentBpm()
                monitorState.setBpm(fresh, SystemClock.elapsedRealtime())
                updateNotification()
            }
        }
    }

    private fun handleConnectivityChanged(isOnline: Boolean) {
        online = isOnline
        if (!running) return

        if (isOnline) {
            authBlocked = false
            reconnectJob?.cancel()
            connectNow()
        } else {
            reconnectJob?.cancel()
            connecting = false
            socket.close()
            monitorState.setConnection(ConnectionState.OFFLINE)
            updateNotification()
        }
    }

    private fun connectNow() {
        if (!running || !online || authBlocked || connecting || socket.isOpen()) return
        if (currentEndpoint.isBlank() || currentToken.isBlank()) return

        connecting = true
        monitorState.setConnection(ConnectionState.CONNECTING)
        updateNotification()
        socket.connect(currentEndpoint, currentToken)
    }

    private fun handleConnected() {
        connecting = false
        authBlocked = false
        reconnectPolicy.onConnected()
        reconnectJob?.cancel()
        monitorState.setConnection(ConnectionState.CONNECTED)
        updateNotification()
    }

    private fun handleDisconnected(reason: String?) {
        connecting = false
        if (!running) return

        if (online) {
            monitorState.setConnection(ConnectionState.RECONNECTING)
            scheduleReconnect()
        } else {
            monitorState.setConnection(ConnectionState.OFFLINE)
        }
        updateNotification()
        if (reason != null) {
            Log.d(TAG, "websocket disconnected: $reason")
        }
    }

    private fun handleAuthRejected() {
        connecting = false
        reconnectJob?.cancel()
        authBlocked = true
        monitorState.setConnection(ConnectionState.ERROR, "Auth rejected - check token")
        updateNotification()
    }

    private fun scheduleReconnect() {
        if (!running || !online || authBlocked) return
        reconnectJob?.cancel()
        val delayMs = reconnectPolicy.nextDelayMs(online, authBlocked) ?: return
        monitorState.setConnection(ConnectionState.RECONNECTING)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (running && online && !authBlocked) {
                connectNow()
            }
        }
    }

    private fun permissionsReady(): Boolean {
        return PermissionChecks.hasSensors(this) &&
            PermissionChecks.hasBackgroundSensors(this) &&
            PermissionChecks.hasNotifications(this)
    }

    private fun ensureForeground(): Boolean {
        val snapshot = monitorState.snapshot()
        val notification = Notifications.build(
            this,
            snapshot.bpm,
            snapshot.connection,
            snapshot.error,
        )
        val foregroundType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                Notifications.FOREGROUND_ID,
                notification,
                foregroundType,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun updateNotification() {
        val snapshot = monitorState.snapshot()
        Notifications.update(
            this,
            snapshot.bpm,
            snapshot.connection,
            snapshot.error,
        )
    }

    private fun stopStreaming(explicit: Boolean) {
        running = false
        if (explicit) {
            settings.setEnabled(false)
            monitorState.setEnabled(false)
            monitorState.setConnection(ConnectionState.DISCONNECTED)
        }
        cleanup()
        if (explicit) {
            Notifications.cancel(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        val wasRunning = running
        running = false
        settings.setEnabled(false)
        monitorState.setEnabled(false)
        monitorState.setConnection(ConnectionState.ERROR, message)
        cleanup()
        if (wasRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        Notifications.postError(this, message)
        stopSelf()
    }

    private fun cleanup() {
        sendJob?.cancel()
        reconnectJob?.cancel()
        notificationJob?.cancel()
        sendJob = null
        reconnectJob = null
        notificationJob = null

        sampler?.stop()
        sampler = null

        socket.close()
        networkMonitor.stop()
        connecting = false

        releaseWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PulseRelay:heart-rate",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                try {
                    lock.release()
                } catch (_: RuntimeException) {
                    // ignore
                }
            }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.menal.pulserelay.action.START"
        const val ACTION_STOP = "com.menal.pulserelay.action.STOP"
        const val ACTION_APPLY = "com.menal.pulserelay.action.APPLY"
        private const val TAG = "PulseRelay"
    }
}
