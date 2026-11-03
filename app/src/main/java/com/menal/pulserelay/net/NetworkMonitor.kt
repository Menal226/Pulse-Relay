package com.menal.pulserelay.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Reports whether an internet-capable network is currently available.
 * No connection attempts are made by the app while this is false.
 *
 * Validation is deliberately not required: on Wear OS a Bluetooth proxy can
 * briefly report INTERNET before VALIDATED after the phone reconnects. The
 * WebSocket failure/backoff path handles a network that is present but not
 * actually usable.
 */
class NetworkMonitor(
    context: Context,
    private val onChanged: (Boolean) -> Unit,
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private var registered = false
    private var lastOnline: Boolean? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            report()
        }

        override fun onLost(network: Network) {
            report()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            report()
        }

        override fun onUnavailable() {
            report()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        registered = true
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            registered = false
            throw e
        }
        report()
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }

    fun isOnline(): Boolean = checkOnline()

    private fun report() {
        val online = checkOnline()
        val changed = synchronized(this) {
            if (lastOnline == online) {
                false
            } else {
                lastOnline = online
                true
            }
        }
        if (changed) {
            onChanged(online)
        }
    }

    private fun checkOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
