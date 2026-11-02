package com.menal.pulserelay.net

import kotlin.random.Random

class Backoff(
    private val minMs: Long = 1_000L,
    private val maxMs: Long = 30_000L,
    private val jitter: Boolean = true,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val exponent = attempt.coerceAtMost(5)
        val base = (minMs * (1L shl exponent)).coerceAtMost(maxMs)
        attempt += 1

        val extra = if (jitter) {
            val quarter = (base / 4).coerceAtLeast(1L)
            random.nextLong(0L, quarter + 1L)
        } else {
            0L
        }
        return (base + extra).coerceAtMost(maxMs)
    }

    fun reset() {
        attempt = 0
    }
}
