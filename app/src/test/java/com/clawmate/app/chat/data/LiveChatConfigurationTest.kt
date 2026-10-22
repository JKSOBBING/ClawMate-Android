package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class LiveChatConfigurationTest {
    @Test fun `ordinary and agent sends use current connection instead of stale constructor values`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"fixture reply\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")) }
            val stale = ChatServerConfig("", "old-fixture-key", "old-model")
            var current = ChatServerConfig(server.url("/").newBuilder().host("127.0.0.1").build().toString(), "new-fixture-key", "new-model")
            val unused = object : ChatClient {
                override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult = error("stale client used")
                override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = error("stale client used")
            }
            val repo = DefaultChatRepository(FakeEmptyDao(), unused, stale,
                connectionProvider = { current to ChatCompletionsClient(OkHttpClient(), current) })
            assertTrue(repo.sendMessage("first fixture turn").toList().last() is ChatSendEvent.Completed)
            current = current.copy(apiKey = "next-fixture-key", modelName = "next-model")
            assertTrue(repo.sendAgentMessage("second fixture turn").toList().last() is ChatSendEvent.Completed)
            for ((key, model) in listOf("new-fixture-key" to "new-model", "next-fixture-key" to "next-model")) {
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("Bearer $key", request.getHeader("Authorization"))
                assertEquals(model, Json.parseToJsonElement(request.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
            }
            current = current.copy(baseUrl = "")
            val failure = runCatching { repo.sendAgentMessage("must not send").toList() }.exceptionOrNull()
            assertEquals(ChatErrorKind.CONFIG, (failure as ChatException).kind)
            assertEquals(2, server.requestCount)
            assertEquals(4, repo.session(repo.ensureActiveSession())!!.messages.size)
        }
    }
}
