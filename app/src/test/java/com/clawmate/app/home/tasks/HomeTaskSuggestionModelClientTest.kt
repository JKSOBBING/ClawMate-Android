package com.clawmate.app.home.tasks

import com.clawmate.app.chat.cloud.*
import com.clawmate.app.datamanager.ConsolidationModelConfig
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HomeTaskSuggestionModelClientTest {
    @Test fun `production client sends compatible text completion and validates real evidence`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val output = buildJsonObject { putJsonArray("suggestions") { (1..3).forEach { n -> add(buildJsonObject {
                put("title", "任务$n"); put("task", "查询资讯$n"); put("reason", "近期依据"); put("category", "news")
                putJsonArray("evidence_refs") { add("record-1") }
            }) } } }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject {
                    putJsonObject("message") { put("role", "assistant"); put("content", output.toString()) }
                    put("finish_reason", "stop")
                }) }
            }.toString()))
            val config = ConsolidationModelConfig.fromValues(server.url("/").toString(), "fixture-key", "fixture-model")!!
            val context = HomeTaskSuggestions.context(listOf(IndexedEvent(eventId = "private-id", eventDate = "2026-10-23", summary = "设备测试依据")),
                emptyList(), emptyList(), "2026-10-23")
            val result = HomeTaskSuggestionModelClient.fromConfig(config, OkHttpClient()).generate(context)
            assertEquals(3, result.size)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertFalse(payload["stream"]!!.jsonPrimitive.boolean)
            assertFalse(payload.toString().contains("private-id"))
            assertEquals(2, payload["messages"]!!.jsonArray.size)
        }
    }

    @Test fun `no input and truncated output cannot return a successful batch`() = runBlocking {
        var calls = 0
        val chat = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
                calls++; return ChatCompletionResult("{}", "length", null)
            }
            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = error("must not stream")
        }
        val client = HomeTaskSuggestionModelClient(chat, "fixture")
        val empty = HomeTaskSuggestions.context(emptyList(), emptyList(), emptyList(), "2026-10-23")
        assertTrue(runCatching { client.generate(empty) }.isFailure)
        assertEquals(0, calls)
        val input = HomeTaskSuggestions.context(listOf(IndexedEvent(summary = "真实依据")), emptyList(), emptyList(), "2026-10-23")
        assertTrue(runCatching { client.generate(input) }.isFailure)
        assertEquals(1, calls)
    }
}
