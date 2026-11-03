package com.menal.pulserelay.monitor

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.menal.pulserelay.PulseRelayApp
import com.menal.pulserelay.data.SettingsStore
import com.menal.pulserelay.data.ValidSettings
import com.menal.pulserelay.net.NetworkMonitor
import com.menal.pulserelay.net.PulseSocket
import com.menal.pulserelay.net.ReconnectPolicy
import com.menal.pulserelay.notifications.Notifications
import com.menal.pulserelay.permissions.PermissionChecks
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
    private lateinit var alarmManager: AlarmManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val reconnectPolicy = ReconnectPolicy()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Sends happen every 15-60 s. A 60 s ping keeps an idle
            // connection alive without adding traffic between every send.
            .pingInterval(60, TimeUnit.SECONDS)
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

    private var windowJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectTimeoutJob: Job? = null

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
    private var continuousMode = false

    private val samplingGeneration = AtomicInteger(0)

    @Volatile
    private var wakeLockGeneration = -1

    @Volatile
    private var lastSendElapsedMs = 0L

    @Volatile
    private var currentEndpoint = ""

    @Volatile
    private var currentToken = ""

    @Volatile
    private var currentIntervalSec = 30

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SAMPLE_WINDOW && running) {
                beginWindow()
            }
        }
    }

    private val alarmPendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            Intent(ACTION_SAMPLE_WINDOW).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PulseRelayApp
        settings = app.settingsStore
        monitorState = app.monitorState
        alarmManager = getSystemService(AlarmManager::class.java)
        Notifications.createChannel(this)
        networkMonitor = NetworkMonitor(this) { isOnline ->
            handleConnectivityChanged(isOnline)
        }
        ContextCompat.registerReceiver(
            this,
            alarmReceiver,
            IntentFilter(ACTION_SAMPLE_WINDOW),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming(explicit = true)
                return START_NOT_STICKY
            }

            ACTION_SAMPLE_WINDOW -> {
                if (running) {
                    beginWindow()
                } else {
                    stopSelf()
                }
                return START_STICKY
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
        samplingGeneration.incrementAndGet()
        cancelAlarm()
        try {
            unregisterReceiver(alarmReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
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

        Log.i(TAG, "Monitoring started: interval=${valid.intervalSec}s")
        try {
            networkMonitor.start()
        } catch (e: Exception) {
            fail("Network monitor failed: ${e.javaClass.simpleName}")
            return
        }
        startConnectionWatchdog()
        refreshNetworkState()
        restartSampling()
        updateNotification()
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

        if (intervalChanged) {
            lastSendElapsedMs = 0L
        }

        if (endpointChanged || tokenChanged || authBlocked) {
            reconnectJob?.cancel()
            connectTimeoutJob?.cancel()
            socket.close()
            connecting = false
            authBlocked = false
            refreshNetworkState()
        }

        if (intervalChanged) {
            restartSampling()
        }
        updateNotification()
    }

    /**
     * Starts either the continuous mode for short intervals or a 15-20 s
     * measurement window for longer intervals. Every call invalidates the
     * previous generation so an old window cannot schedule another alarm.
     */
    private fun restartSampling() {
        if (!running) return
        samplingGeneration.incrementAndGet()
        windowJob?.cancel()
        windowJob = null
        cancelAlarm()
        sampler?.stop()
        sampler = null

        val intervalMs = currentIntervalSec * 1000L
        continuousMode = !SamplingCycle.usesDutyCycle(intervalMs)
        Log.i(
            TAG,
            "Sampling mode=${if (continuousMode) "continuous" else "duty-cycle"} " +
                "interval=${currentIntervalSec}s",
        )
        if (continuousMode) {
            startContinuousSampler()
        } else {
            beginWindow()
        }
    }

    private fun startContinuousSampler() {
        val generation = samplingGeneration.get()
        val newSampler = HeartRateSampler(
            context = this,
            preferWakeUpSensor = true,
            onReading = { bpm, atElapsedMs ->
                if (!running || generation != samplingGeneration.get()) {
                    return@HeartRateSampler
                }
                monitorState.setBpm(bpm, atElapsedMs)
                if (continuousMode) {
                    sendFromReading(bpm)
                }
            },
            onError = { message ->
                if (running && generation == samplingGeneration.get()) {
                    fail(message)
                }
            },
        )
        sampler = newSampler
        if (!newSampler.start()) {
            return
        }
    }

    private fun sendFromReading(bpm: Int) {
        if (!running || !online || authBlocked || !socket.isOpen()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSendElapsedMs < currentIntervalSec * 1000L) return
        lastSendElapsedMs = now
        if (socket.sendByte(bpm)) {
            updateNotification()
        }
    }

    private fun beginWindow() {
        if (!running || continuousMode) return
        if (windowJob?.isActive == true) return

        val generation = samplingGeneration.get()
        val intervalMs = currentIntervalSec * 1000L
        val windowMs = SamplingCycle.windowMs(intervalMs)

        windowJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            acquireWakeLock(windowMs + WINDOW_EXTRA_MS, generation)
            var localSampler: HeartRateSampler? = null
            try {
                refreshNetworkState()

                // No server connection means no reason to power the optical
                // sensor. The next window will try again.
                if (!online) {
                    Log.d(TAG, "Sampling window skipped: offline")
                    return@launch
                }

                connectNow()
                Log.d(TAG, "Sampling window open for ${windowMs}ms")

                localSampler = HeartRateSampler(
                    context = this@HeartRateMonitorService,
                    preferWakeUpSensor = false,
                    onReading = { bpm, atElapsedMs ->
                        if (running && generation == samplingGeneration.get()) {
                            monitorState.setBpm(bpm, atElapsedMs)
                        }
                    },
                    onError = { message ->
                        if (running && generation == samplingGeneration.get()) {
                            fail(message)
                        }
                    },
                )
                sampler = localSampler
                if (!localSampler.start()) {
                    return@launch
                }

                delay(windowMs)

                val bpm = localSampler.currentBpm()
                if (bpm != null && running && online && socket.isOpen()) {
                    Log.d(TAG, "Sending window reading: $bpm bpm")
                    socket.sendByte(bpm)
                } else {
                    Log.d(TAG, "No window reading sent")
                }
                updateNotification()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (running) {
                    fail("Sampling error: ${e.javaClass.simpleName}")
                }
            } finally {
                localSampler?.stop()
                if (sampler === localSampler) {
                    sampler = null
                }
                releaseWakeLock(generation)
                if (running && !continuousMode && generation == samplingGeneration.get()) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    scheduleNextWindow(
                        SamplingCycle.nextStartDelayMs(intervalMs, elapsed),
                    )
                }
            }
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleNextWindow(delayMs: Long) {
        if (!running || continuousMode) return

        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        alarmManager.cancel(alarmPendingIntent)
        Log.d(TAG, "Next sampling window in ${delayMs}ms")

        try {
            if (Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    alarmPendingIntent,
                )
            } else {
                // Best effort when the user has not granted exact-alarm
                // access. Timing may be less precise in Doze.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    alarmPendingIntent,
                )
            }
        } catch (_: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    alarmPendingIntent,
                )
            } catch (_: SecurityException) {
                Log.w(TAG, "Could not schedule sampling window")
            }
        }
    }

    private fun cancelAlarm() {
        try {
            alarmManager.cancel(alarmPendingIntent)
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * Extra safety net for the reconnect bug: even if a network callback is
     * missed, this loop notices that the socket is not open and retries while
     * internet is available.
     */
    private fun startConnectionWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!running) continue

                val actualOnline = networkMonitor.isOnline()
                if (actualOnline != online) {
                    handleConnectivityChanged(actualOnline)
                }

                if (online && !authBlocked && !socket.isOpen() && !connecting) {
                    connectNow()
                }
            }
        }
    }

    private fun handleConnectivityChanged(isOnline: Boolean) {
        online = isOnline
        Log.i(TAG, "Network available=$isOnline")
        if (!running) return

        if (isOnline) {
            authBlocked = false
            reconnectJob?.cancel()
            connectNow()
        } else {
            reconnectJob?.cancel()
            connectTimeoutJob?.cancel()
            connecting = false
            socket.close()
            monitorState.setConnection(ConnectionState.OFFLINE)
            updateNotification()
        }
    }

    /**
     * Re-checks the actual ConnectivityManager state. Network callbacks can be
     * missed after a phone disconnects/reconnects, especially across Doze.
     */
    private fun refreshNetworkState() {
        val actualOnline = networkMonitor.isOnline()
        if (actualOnline != online) {
            handleConnectivityChanged(actualOnline)
        }
    }

    private fun connectNow() {
        if (!running || !online || authBlocked || connecting || socket.isOpen()) return
        if (currentEndpoint.isBlank() || currentToken.isBlank()) return

        connecting = true
        monitorState.setConnection(ConnectionState.CONNECTING)
        updateNotification()
        Log.i(TAG, "Connecting WebSocket")

        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (running && connecting && !socket.isOpen()) {
                Log.w(TAG, "WebSocket connect timed out; retrying")
                connecting = false
                socket.close()
                monitorState.setConnection(ConnectionState.RECONNECTING)
                scheduleReconnect()
            }
        }

        try {
            socket.connect(currentEndpoint, currentToken)
        } catch (e: Exception) {
            connecting = false
            connectTimeoutJob?.cancel()
            handleDisconnected(e.message ?: "connect failed")
        }
    }

    private fun handleConnected() {
        connecting = false
        connectTimeoutJob?.cancel()
        authBlocked = false
        reconnectPolicy.onConnected()
        reconnectJob?.cancel()
        lastSendElapsedMs = 0L
        Log.i(TAG, "WebSocket connected")
        monitorState.setConnection(ConnectionState.CONNECTED)
        updateNotification()
    }

    private fun handleDisconnected(reason: String?) {
        connecting = false
        connectTimeoutJob?.cancel()
        if (!running) return
        Log.i(TAG, "WebSocket disconnected: ${reason ?: "unknown"}")

        if (online) {
            monitorState.setConnection(ConnectionState.RECONNECTING)
            scheduleReconnect()
        } else {
            monitorState.setConnection(ConnectionState.OFFLINE)
        }
        updateNotification()
    }

    private fun handleAuthRejected() {
        connecting = false
        connectTimeoutJob?.cancel()
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
        samplingGeneration.incrementAndGet()
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
        samplingGeneration.incrementAndGet()
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
        windowJob?.cancel()
        watchdogJob?.cancel()
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        windowJob = null
        watchdogJob = null
        reconnectJob = null
        connectTimeoutJob = null

        cancelAlarm()
        sampler?.stop()
        sampler = null

        socket.close()
        networkMonitor.stop()
        connecting = false

        releaseWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(timeoutMs: Long, generation: Int) {
        releaseWakeLock()
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLockGeneration = generation
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PulseRelay:heart-rate-window",
        ).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    /**
     * Releases the wake lock only if it still belongs to [generation].
     * An old cancelled window must not release the lock of a new window.
     */
    private fun releaseWakeLock(generation: Int? = null) {
        if (generation != null && generation != wakeLockGeneration) return

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
        wakeLockGeneration = -1
    }

    companion object {
        const val ACTION_START = "com.menal.pulserelay.action.START"
        const val ACTION_STOP = "com.menal.pulserelay.action.STOP"
        const val ACTION_APPLY = "com.menal.pulserelay.action.APPLY"
        const val ACTION_SAMPLE_WINDOW = "com.menal.pulserelay.action.SAMPLE_WINDOW"

        private const val TAG = "PulseRelay"
        private const val ALARM_REQUEST_CODE = 7301
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WINDOW_EXTRA_MS = 10_000L
    }
}
