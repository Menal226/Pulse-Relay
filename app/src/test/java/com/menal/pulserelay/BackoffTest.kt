package com.menal.pulserelay

import com.menal.pulserelay.net.Backoff
import com.menal.pulserelay.net.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackoffTest {
    @Test
    fun `backs off exponentially and caps`() {
        val backoff = Backoff(jitter = false)
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
        assertEquals(16_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
        assertEquals(30_000L, backoff.nextDelayMs())
    }

    @Test
    fun `reset restarts the sequence`() {
        val backoff = Backoff(jitter = false)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `reconnect policy never schedules while offline or auth-blocked`() {
        val policy = ReconnectPolicy(Backoff(jitter = false))
        assertNull(policy.nextDelayMs(online = false, authBlocked = false))
        assertNull(policy.nextDelayMs(online = true, authBlocked = true))
        assertEquals(1_000L, policy.nextDelayMs(online = true, authBlocked = false))
        policy.onConnected()
        assertEquals(1_000L, policy.nextDelayMs(online = true, authBlocked = false))
    }
}
