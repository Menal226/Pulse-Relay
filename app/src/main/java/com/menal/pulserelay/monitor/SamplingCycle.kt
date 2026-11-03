package com.menal.pulserelay.monitor

/**
 * Pure scheduling policy for the heart-rate sampling cycle.
 *
 * Short intervals stay continuous; long intervals use a measurement window
 * followed by a sleep period so the optical sensor can be unregistered.
 */
object SamplingCycle {
    const val CONTINUOUS_MAX_INTERVAL_MS = 15_000L
    const val MIN_WINDOW_MS = 15_000L
    const val MAX_WINDOW_MS = 20_000L
    const val MIN_NEXT_DELAY_MS = 1_000L

    fun usesDutyCycle(intervalMs: Long): Boolean =
        intervalMs > CONTINUOUS_MAX_INTERVAL_MS

    /**
     * The sensor window length for a duty-cycled interval.
     *
     * 20 s -> 15 s, 30 s -> 15 s, 60 s -> 20 s, 5 min -> 20 s.
     * The window is capped so there is always at least one second of sleep.
     */
    fun windowMs(intervalMs: Long): Long {
        if (!usesDutyCycle(intervalMs)) return intervalMs
        val ideal = intervalMs / 2
        return ideal
            .coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            .coerceAtMost((intervalMs - MIN_NEXT_DELAY_MS).coerceAtLeast(1L))
    }

    /**
     * Delay from the end of a window to the next window start.
     * [windowElapsedMs] is the measured wall time spent in the window.
     */
    fun nextStartDelayMs(intervalMs: Long, windowElapsedMs: Long): Long =
        (intervalMs - windowElapsedMs).coerceAtLeast(MIN_NEXT_DELAY_MS)
}
