package com.menal.pulserelay.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class PulseSocket(
    private val client: OkHttpClient,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onAuthRejected()
    }

    @Volatile
    private var open = false

    @Volatile
    private var generation = 0

    private var webSocket: WebSocket? = null

    fun isOpen(): Boolean = open

    fun connect(url: String, token: String) {
        val myGeneration = ++generation
        open = false
        try {
            webSocket?.close(1000, "reconnect")
        } catch (_: Exception) {
            // old socket is being replaced
        }
        webSocket = null

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) return
                open = true
                listener.onConnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGeneration != generation) return
                open = false
                val code = response?.code
                if (code == 401 || code == 403) {
                    listener.onAuthRejected()
                } else {
                    listener.onDisconnected(t.message ?: "connection failed")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                open = false
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGeneration != generation) return
                open = false
                listener.onDisconnected("closed")
            }
        }

        try {
            val newSocket = client.newWebSocket(request, socketListener)
            webSocket = newSocket
        } catch (e: IllegalArgumentException) {
            if (myGeneration == generation) {
                open = false
                listener.onDisconnected(e.message ?: "invalid URL")
            }
        }
    }

    fun sendByte(bpm: Int): Boolean {
        val byte = HeartRateByte.encode(bpm) ?: return false
        if (!open) return false
        val sent = try {
            webSocket?.send(ByteString.of(byte)) == true
        } catch (_: Exception) {
            false
        }
        if (!sent) {
            open = false
            listener.onDisconnected("send failed")
        }
        return sent
    }

    fun close() {
        generation += 1
        open = false
        try {
            webSocket?.close(1000, "stop")
        } catch (_: Exception) {
            // ignore
        }
        webSocket = null
    }
}
