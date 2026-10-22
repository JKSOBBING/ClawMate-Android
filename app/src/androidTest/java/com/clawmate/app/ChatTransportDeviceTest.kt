package com.clawmate.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clawmate.app.chat.cloud.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatTransportDeviceTest {
    @Test fun actualAndroidSocketsCloseOnCancellationForCompletionAndSse() = runBlocking<Unit> {
        for (stream in listOf(false, true)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val ready = CompletableDeferred<Unit>(); val disconnected = CompletableDeferred<Unit>()
                val peer = AtomicReference<Socket>()
                val worker = Thread({
                    try {
                        val socket = server.accept(); peer.set(socket); socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read(); check(byte >= 0 && header.length < 8192)
                            header.append(byte.toChar())
                        }
                        val length = Regex("(?i)Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        check(length in 0..16_000)
                        repeat(length) { check(input.read() >= 0) }
                        if (stream) {
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n6\r\ndata: \r\n"
                            socket.getOutputStream().apply { write(response.toByteArray(Charsets.UTF_8)); flush() }
                        }
                        ready.complete(Unit)
                        while (input.read() >= 0) { /* Observe the client closing; never send a complete line. */ }
                        disconnected.complete(Unit)
                    } catch (e: Exception) {
                        if (!ready.isCompleted) ready.completeExceptionally(e) else disconnected.complete(Unit)
                    } finally { peer.get()?.close() }
                }, "clawmate-loopback-test").apply { isDaemon = true; start() }
                val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                val client = ChatCompletionsClient(http, ChatServerConfig("http://127.0.0.1:${server.localPort}", "fixture-key", "fixture-model"))
                val request = ChatCompletionsRequest("fixture-model", listOf(ChatMessageWire("user", "fixture request")), stream)
                val job = launch(Dispatchers.IO) { if (stream) client.completionStream(request).collect() else client.completion(request) }
                try {
                    withTimeout(5000) { ready.await() }
                    delay(100)
                    withTimeout(2000) { job.cancelAndJoin() }
                    withTimeout(2000) { disconnected.await() }
                    assertTrue(job.isCancelled)
                } finally {
                    job.cancel(); peer.get()?.close(); server.close(); worker.join(1000)
                }
            }
        }
    }
}
