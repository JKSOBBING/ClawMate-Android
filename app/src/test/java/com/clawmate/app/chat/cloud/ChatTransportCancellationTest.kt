package com.clawmate.app.chat.cloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ChatTransportCancellationTest {
    private val request = ChatCompletionsRequest("fixture-model", listOf(ChatMessageWire("user", "fixture prompt")), stream = false)

    @Test fun `cancelling a blocked ordinary request actually closes its HTTP call`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = AtomicReference<Call>()
            val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .eventListener(object : EventListener() { override fun callStart(value: Call) { call.set(value) } }).build()
            val client = ChatCompletionsClient(http, ChatServerConfig(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "fixture-key", "fixture-model"))
            val job = launch(Dispatchers.Default) { client.completion(request) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.get().isCanceled())
        }
    }

    @Test fun `cancelling an SSE read closes the socket without waiting for the next line`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: incomplete line").setBodyDelay(3, TimeUnit.SECONDS))
            val headers = CountDownLatch(1); val call = AtomicReference<Call>()
            val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false)
                .eventListener(object : EventListener() {
                    override fun callStart(value: Call) { call.set(value) }
                    override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
                }).build()
            val client = ChatCompletionsClient(http, ChatServerConfig(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "fixture-key", "fixture-model"))
            val job = launch(Dispatchers.Default) { client.completionStream(request).collect() }
            assertTrue(withContext(Dispatchers.IO) { headers.await(2, TimeUnit.SECONDS) })
            delay(100)
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.get().isCanceled())
        }
    }

    @Test fun `real read timeouts remain typed errors for ordinary and streaming requests`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val http = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
            val client = ChatCompletionsClient(http, ChatServerConfig(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "fixture-key", "fixture-model"))
            for (stream in listOf(false, true)) {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val result = runCatching { withTimeout(5000) { if (stream) client.completionStream(request).collect() else client.completion(request) } }
                val failure = result.exceptionOrNull()
                assertTrue("expected typed timeout for stream=$stream, got ${failure?.javaClass?.simpleName}", failure is ChatException)
                assertEquals(ChatErrorKind.TIMEOUT, (failure as ChatException).kind)
            }
        }
    }
}
