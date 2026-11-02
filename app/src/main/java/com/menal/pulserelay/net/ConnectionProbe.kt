package com.menal.pulserelay.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

object ConnectionProbe {
    suspend fun test(
        endpoint: String,
        token: String,
        timeoutMs: Long = 8_000L,
    ): Result<Unit> {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .build()

                fun finish(value: Result<Unit>) {
                    if (continuation.isActive) {
                        continuation.resume(value)
                    }
                    client.dispatcher.executorService.shutdown()
                }

                val socket = client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.close(1000, "probe")
                            finish(Result.success(Unit))
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            val detail = response?.let { "HTTP ${it.code}" }
                                ?: t.message
                                ?: "connection failed"
                            finish(Result.failure(IOException(detail)))
                        }
                    },
                )

                continuation.invokeOnCancellation {
                    socket.cancel()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }

        return result ?: Result.failure(IOException("Timed out"))
    }
}
