package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.*
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.image.PreparedChatImage
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatImageSendTest {
    @get:Rule val folder = TemporaryFolder()
    private val image = PreparedChatImage("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), 1, 1, 3)
    private fun stream() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":\"测试回复\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    @Test fun `real stream stores prepared private images and sends only current turn pixels`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(stream()); server.enqueue(stream())
            val config = ChatServerConfig(server.url("/").toString(), "fixture-key", "fixture-model")
            val store = ChatImageFileStore(folder.root)
            val dao = FakeEmptyDao()
            var currentConfig = config
            val repo = DefaultChatRepository(dao, noCalls(), config.copy(baseUrl = "https://old.invalid"), imageStore = store,
                connectionProvider = { currentConfig to ChatCompletionsClient(OkHttpClient(), currentConfig) })
            val priorDestination = repo.imageUploadInfo()
            currentConfig = config.copy(apiKey = "updated-fixture-key")
            assertNotEquals(priorDestination, repo.imageUploadInfo())
            assertFalse(repo.imageUploadInfo().toString().contains("updated-fixture-key"))
            val events = repo.sendImageMessage("", listOf(image)).toList()
            assertTrue(events.last() is ChatSendEvent.Completed)
            val stored = repo.session(repo.ensureActiveSession())!!.messages.first()
            assertEquals("请分析这张图片。", stored.content)
            assertEquals(1, stored.imagePaths.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), store.file(stored.imagePaths.single()).readBytes())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer updated-fixture-key", request.getHeader("Authorization"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertNull(body["tools"])
            val user = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
            assertEquals(image.dataUrl, user[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains(stored.imagePaths.single()))
            // A new repository restores references, without re-uploading old images on a text follow-up.
            val reopened = DefaultChatRepository(dao, ChatCompletionsClient(OkHttpClient(), config), config, imageStore = store)
            assertEquals(stored.imagePaths, reopened.session(repo.ensureActiveSession())!!.messages.first().imagePaths)
            reopened.sendMessage("继续聊").toList()
            val followup = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            assertFalse(followup.contains("data:image"))
            assertTrue(followup.contains("需用户重新附图"))
        }
    }

    @Test fun `failed provider retains accepted picture but never fabricates an assistant and delete removes it`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(400).setBody("unsupported image input"))
            val config = ChatServerConfig(server.url("/").toString(), "fixture-key", "fixture-model")
            val store = ChatImageFileStore(folder.root)
            val repo = DefaultChatRepository(FakeEmptyDao(), ChatCompletionsClient(OkHttpClient(), config), config, imageStore = store)
            assertTrue(runCatching { repo.sendImageMessage("看图", listOf(image)).toList() }.isFailure)
            val session = repo.session(repo.ensureActiveSession())!!
            assertEquals(1, session.messages.size)
            val file = store.file(session.messages.single().imagePaths.single())
            assertTrue(file.isFile)
            repo.deleteSession(session.id)
            assertFalse(file.exists())
        }
    }

    @Test fun `unaccepted database failure removes this prepared batch only`() = runBlocking<Unit> {
        val delegate = FakeEmptyDao()
        val dao = object : ChatDao by delegate {
            override suspend fun upsertMessages(messages: List<ChatMessageEntity>) { error("fixture disk failure") }
        }
        val config = ChatServerConfig("https://example.invalid", "fixture-key", "fixture-model")
        val repo = DefaultChatRepository(dao, noCalls(), config, imageStore = ChatImageFileStore(folder.root))
        assertTrue(runCatching { repo.sendImageMessage("看图", listOf(image)).toList() }.isFailure)
        assertTrue(java.io.File(folder.root, "chat-images").listFiles()!!.isEmpty())
    }

    @Test fun `invalid oversized local mode and unconfigured inputs perform no image save or network`() = runBlocking<Unit> {
        val config = ChatServerConfig("https://example.invalid", "fixture-key", "fixture-model")
        val store = ChatImageFileStore(folder.root)
        val repo = DefaultChatRepository(FakeEmptyDao(), noCalls(), config, imageStore = store)
        for (images in listOf(emptyList(), List(5) { image }, listOf(image.copy(dataUrl = "https://example.invalid/photo")))) {
            assertTrue(runCatching { repo.sendImageMessage("看图", images).toList() }.isFailure)
        }
        repo.createSession(ChatMode.LOCAL_INFERENCE)
        assertTrue(runCatching { repo.sendImageMessage("看图", listOf(image)).toList() }.isFailure)
        val missing = DefaultChatRepository(FakeEmptyDao(), noCalls(), config.copy(baseUrl = ""), imageStore = store)
        assertNull(missing.imageUploadInfo())
        assertTrue(runCatching { missing.sendImageMessage("看图", listOf(image)).toList() }.isFailure)
        assertFalse(java.io.File(folder.root, "chat-images").exists())
        assertTrue(runCatching { store.file("../../private.jpg") }.isFailure)
    }

    @Test fun `partial image response is not persisted as a successful answer`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"截断结果\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"))
            val config = ChatServerConfig(server.url("/").toString(), "fixture-key", "fixture-model")
            val repo = DefaultChatRepository(FakeEmptyDao(), ChatCompletionsClient(OkHttpClient(), config), config, imageStore = ChatImageFileStore(folder.root))
            assertTrue(runCatching { repo.sendImageMessage("看图", listOf(image)).toList() }.isFailure)
            assertEquals(listOf("user"), repo.session(repo.ensureActiveSession())!!.messages.map { it.role.wire })
        }
    }

    private fun noCalls() = object : ChatClient {
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult = error("no model call")
        override fun completionStream(request: ChatCompletionsRequest): kotlinx.coroutines.flow.Flow<CloudStreamChunk> = error("no stream call")
    }
}
