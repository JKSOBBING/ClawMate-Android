package com.clawmate.app.datamanager.chatmemory

import com.clawmate.app.chat.cloud.*
import com.clawmate.app.datamanager.ConsolidationModelConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ChatMemoryModelClientTest {
    private val inputs = listOf(ChatMemoryInput("ref", "2026-10-23", "cloud_agent", "我喜欢咖啡", emptyList()))
    private val valid = """{"items":[{"source_ref":"ref","facts":[{"scene_id":1,"text":"喜欢咖啡"}]}]}"""

    private fun chat(block: suspend (ChatCompletionsRequest) -> ChatCompletionResult) = object : ChatClient {
        override suspend fun completion(request: ChatCompletionsRequest) = block(request)
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = error("must not stream")
    }
    private fun reply(content: String = valid, reason: String = "stop") = ChatCompletionResult(content, reason, null)

    @Test fun `invalid output retries bounded requests without echoing invalid content`() = runBlocking {
        val requests = mutableListOf<ChatCompletionsRequest>()
        val extractor = ChatMemoryModelClient(chat { request ->
            requests += request
            if (requests.size == 1) reply("untrusted private invalid output") else reply("```json\n$valid\n```")
        }, "model")
        assertEquals(listOf(ChatMemoryFact(1, "喜欢咖啡")), extractor.extract(inputs).single().facts)
        assertEquals(2, requests.size)
        assertTrue(requests.all { !it.stream && it.tools == null && it.messages.size == 2 && it.maxTokens == 8192 })
        assertTrue(requests.none { it.toString().contains("untrusted private invalid output") })
        assertEquals(requests[0].messages[1].content, requests[1].messages[1].content)
    }

    @Test fun `persistent malformed or truncated output fails after three attempts`() = runBlocking {
        for (response in listOf(reply("broken"), reply(reason = "length"), reply(reason = "content_filter"))) {
            var calls = 0
            val result = runCatching { ChatMemoryModelClient(chat { calls++; response }, "model").extract(inputs) }
            assertTrue(result.exceptionOrNull() is ChatMemoryResponseException)
            assertEquals(3, calls)
        }
    }

    @Test fun `network access and cancellation failures are never retried`() = runBlocking {
        for (error in listOf(IOException("offline"), ChatException(ChatErrorKind.HTTP, "unauthorized", 401), CancellationException("cancelled"))) {
            var calls = 0
            val result = runCatching { ChatMemoryModelClient(chat { calls++; throw error }, "model").extract(inputs) }
            assertSame(error, result.exceptionOrNull())
            assertEquals(1, calls)
        }
    }

    @Test fun `empty or oversized input sends zero requests`() = runBlocking {
        val extractor = ChatMemoryModelClient(chat { error("must not call") }, "model")
        assertTrue(extractor.extract(emptyList()).isEmpty())
        assertTrue(runCatching { extractor.extract((0..8).map { inputs.single().copy(sourceRef = "$it") }) }.isFailure)
    }

    @Test fun `tool output is rejected without executing tools`() = runBlocking {
        var calls = 0
        val extractor = ChatMemoryModelClient(chat {
            calls++
            reply().copy(toolCalls = listOf(ToolCallWire(function = ToolCallFunctionWire("unexpected", "{}"))))
        }, "model")
        assertTrue(runCatching { extractor.extract(inputs) }.exceptionOrNull() is ChatMemoryResponseException)
        assertEquals(3, calls)
    }

    @Test fun `production factory sends actual compatible HTTP request to local test server`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject {
                    putJsonObject("message") { put("role", "assistant"); put("content", valid) }
                    put("finish_reason", "stop")
                }) }
            }.toString()))
            val config = ConsolidationModelConfig.fromValues(server.url("/").toString(), "fixture-key", "fixture-model")!!
            val results = ChatMemoryModelClientFactory.client(config, OkHttpClient()).extract(inputs)
            assertEquals("喜欢咖啡", results.single().facts.single().text)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertFalse(payload["stream"]!!.jsonPrimitive.boolean)
            assertEquals("fixture-model", payload["model"]!!.jsonPrimitive.content)
            assertTrue(payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content.contains("我喜欢咖啡"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `missing credentials cannot construct a production client`() {
        assertTrue(runCatching { ChatMemoryModelClientFactory.client(ConsolidationModelConfig("https://example.com", "", "model")) }.isFailure)
    }
}
