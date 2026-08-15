package com.clawmate.app.chat.cloud

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [ChatCompletionsClient] against a MockWebServer.
 * Verifies non-streaming mapping, SSE streaming, HTTP errors, and malformed
 * event handling for real OkHttp requests.
 */
class ChatCompletionsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ChatCompletionsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient()
        client = ChatCompletionsClient(
            httpClient = http,
            config = ChatServerConfig(
                baseUrl = server.url("/").toString(),
                apiKey = "test-key",
                modelName = "test-model",
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `non-streaming completion maps content and finish reason`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}]}""",
                ),
        )

        val result = client.completion(
            ChatCompletionsRequest(
                model = "test-model",
                messages = listOf(ChatMessageWire("user", "hi")),
                stream = false,
            ),
        )
        assertEquals("Hello!", result.content)
        assertEquals("stop", result.finishReason)

        val recorded = server.takeRequest()
        assertTrue(recorded.path?.endsWith("/v1/chat/completions") == true)
        assertTrue(recorded.getHeader("Authorization") == "Bearer test-key")
    }

    @Test
    fun `sse stream yields deltas then done`() = runBlocking {
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val chunks = client.completionStream(
            ChatCompletionsRequest(
                model = "test-model",
                messages = listOf(ChatMessageWire("user", "hi")),
                stream = true,
            ),
        ).toList()

        val content = chunks.joinToString("") { it.contentDelta }
        assertEquals("Hello", content)
        assertTrue(chunks.last().done)
    }

    @Test
    fun `http error surfaces typed ChatException with body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key","type":"invalid_request_error","code":"401"}}"""),
        )

        val exception = try {
            client.completion(
                ChatCompletionsRequest("test-model", listOf(ChatMessageWire("user", "hi")), false),
            )
            null
        } catch (e: ChatException) {
            e
        }

        assertTrue(exception != null)
        assertEquals(ChatErrorKind.HTTP, exception!!.kind)
        assertTrue(exception.message.contains("Invalid API key"))
    }

    @Test
    fun `malformed sse payload surfaces ChatException`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {not json}\n\n"),
        )

        val exception = try {
            client.completionStream(
                ChatCompletionsRequest("test-model", listOf(ChatMessageWire("user", "hi")), true),
            ).toList()
            null
        } catch (e: ChatException) {
            e
        }

        assertTrue(exception != null)
        assertEquals(ChatErrorKind.MALFORMED_SSE, exception!!.kind)
    }
}
