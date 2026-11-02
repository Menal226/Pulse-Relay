package com.menal.pulserelay.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Reports whether a validated internet connection is currently available.
 * No connection attempts are made by the app while this is false.
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
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (registered) return
        registered = true
        connectivityManager.registerDefaultNetworkCallback(callback)
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
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
