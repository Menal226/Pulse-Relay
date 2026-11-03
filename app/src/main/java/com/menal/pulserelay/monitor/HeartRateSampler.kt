package com.menal.pulserelay.monitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.roundToInt

class HeartRateSampler(
    context: Context,
    private val preferWakeUpSensor: Boolean,
    private val onReading: (bpm: Int, atElapsedMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var sensor: Sensor? = null

    @Volatile
    private var latestBpm: Int? = null

    @Volatile
    private var lastReadingAt = 0L

    fun start(): Boolean {
        val preferred = sensorManager.getDefaultSensor(
            Sensor.TYPE_HEART_RATE,
            preferWakeUpSensor,
        )
        val fallback = sensorManager.getDefaultSensor(
            Sensor.TYPE_HEART_RATE,
            !preferWakeUpSensor,
        )
        sensor = preferred
            ?: fallback
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val selected = sensor
        if (selected == null) {
            onError("Heart-rate sensor is not available")
            return false
        }
        val registered = sensorManager.registerListener(
            this,
            selected,
            SAMPLE_PERIOD_US,
            0,
        )
        if (!registered) {
            onError("Could not register the heart-rate sensor")
            return false
        }
        return true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun currentBpm(): Int? {
        val value = latestBpm ?: return null
        val age = SystemClock.elapsedRealtime() - lastReadingAt
        return if (age <= STALE_AFTER_MS) value else null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT) return

        val value = event.values.firstOrNull() ?: return
        if (!value.isFinite() || value < 25f || value > 250f) return

        val bpm = value.roundToInt()
        val now = SystemClock.elapsedRealtime()
        latestBpm = bpm
        lastReadingAt = now
        onReading(bpm, now)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action needed; readings with no contact are filtered above.
    }

    private companion object {
        const val SAMPLE_PERIOD_US = 1_000_000
        const val STALE_AFTER_MS = 15_000L
    }
}
