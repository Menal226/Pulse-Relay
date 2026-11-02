package com.menal.pulserelay.net

/**
 * Pure reconnect policy used by the service and by JVM tests.
 * No attempt is made without validated internet or while auth is blocked.
 */
class ReconnectPolicy(private val backoff: Backoff = Backoff()) {
    fun onConnected() {
        backoff.reset()
    }

    fun nextDelayMs(online: Boolean, authBlocked: Boolean): Long? {
        if (!online || authBlocked) return null
        return backoff.nextDelayMs()
    }
}
