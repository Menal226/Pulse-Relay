package com.menal.pulserelay

import com.menal.pulserelay.monitor.SamplingCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingCycleTest {
    @Test
    fun `short intervals stay continuous`() {
        assertFalse(SamplingCycle.usesDutyCycle(1_000L))
        assertFalse(SamplingCycle.usesDutyCycle(15_000L))
        assertTrue(SamplingCycle.usesDutyCycle(20_000L))
    }

    @Test
    fun `window is 15 s for 20 and 30 s intervals`() {
        assertEquals(15_000L, SamplingCycle.windowMs(20_000L))
        assertEquals(15_000L, SamplingCycle.windowMs(30_000L))
    }

    @Test
    fun `window is capped at 20 s for long intervals`() {
        assertEquals(20_000L, SamplingCycle.windowMs(60_000L))
        assertEquals(20_000L, SamplingCycle.windowMs(300_000L))
    }

    @Test
    fun `next delay preserves interval and always leaves a sleep gap`() {
        assertEquals(15_000L, SamplingCycle.nextStartDelayMs(30_000L, 15_000L))
        assertEquals(10_000L, SamplingCycle.nextStartDelayMs(30_000L, 20_000L))
        assertEquals(1_000L, SamplingCycle.nextStartDelayMs(30_000L, 30_000L))
        assertEquals(1_000L, SamplingCycle.nextStartDelayMs(30_000L, 45_000L))
    }
}
