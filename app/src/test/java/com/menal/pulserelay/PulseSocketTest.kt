package com.menal.pulserelay

import com.menal.pulserelay.net.PulseSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseSocketTest {

    @Test
    fun `sends auth header and exactly one byte`() = runBlocking {
        val server = MockWebServer()
        val received = CompletableDeferred<ByteString>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        received.complete(bytes)
                    }
                }
            )
        )
        server.start()

        val connected = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val socket = PulseSocket(
            client,
            object : PulseSocket.Listener {
                override fun onConnected() {
                    connected.countDown()
                }

                override fun onDisconnected(reason: String?) = Unit
                override fun onAuthRejected() = Unit
            },
        )

        val url = server.url("/ws").toString().replaceFirst("http", "ws")
        socket.connect(url, "secret-token")

        assertTrue(connected.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer secret-token", request?.getHeader("Authorization"))

        assertTrue(socket.sendByte(72))
        val bytes = withTimeout(5_000) { received.await() }
        assertEquals(1, bytes.size)
        assertEquals(72, bytes[0].toInt() and 0xFF)

        socket.close()
        server.shutdown()
    }

    @Test
    fun `reports auth rejection`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        val rejected = CountDownLatch(1)
        val client = OkHttpClient.Builder().build()
        val socket = PulseSocket(
            client,
            object : PulseSocket.Listener {
                override fun onConnected() = Unit
                override fun onDisconnected(reason: String?) = Unit
                override fun onAuthRejected() {
                    rejected.countDown()
                }
            },
        )

        val url = server.url("/ws").toString().replaceFirst("http", "ws")
        socket.connect(url, "bad")
        assertTrue(rejected.await(5, TimeUnit.SECONDS))

        socket.close()
        server.shutdown()
    }
}
