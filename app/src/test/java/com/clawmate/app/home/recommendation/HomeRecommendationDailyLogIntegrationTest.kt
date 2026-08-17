package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.datamanager.DailyLogParser
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free integration tests for the Phase-29 mixed input path: the Home
 * repository generates recommendations from **gallery + daily-log** bounded
 * planner events together. Covers zero-call no-data, daily-log prompt inclusion
 * without a URI, and strict `source_event_ids` normalization over both id
 * namespaces (gallery `gx_*` ids and daily-log `date/file#N` source refs).
 */
class HomeRecommendationDailyLogIntegrationTest {

    private val config = HomeRecommendationConfig(
        baseUrl = "https://planner.test",
        apiKey = "sk-planner-secret",
        modelName = "gpt-4o",
    )

    @After
    fun tearDown() {
        EndpointConfig.load(java.util.Properties())
    }

    private fun galleryItem(id: String): GalleryPhotoItem = GalleryPhotoItem(
        id = id,
        uri = "content://media/$id",
        date = "2026-08-01",
        title = "收据",
        category = "购物",
        summary = "便利店购物小票",
        tags = listOf("购物"),
        keywords = listOf("便利店"),
        createdAt = 0L,
    )

    private fun dailyEntry(index: Int): DailyLogEntry {
        val text = "今天计划去买日用品"
        return DailyLogEntry(
            sourceDate = "2026-08-02",
            fileName = "wechat.logs.md",
            entryIndex = index,
            text = text,
            workflowMetadata = mapOf("workflow" to "wechat"),
            latestEntryIndex = index,
            sourceRef = DailyLogParser.sourceRef("2026-08-02", "wechat.logs.md", index),
        )
    }

    private fun repo(
        chat: ChatClient,
        noCall: () -> Unit = {},
    ): HomeRecommendationRepository = GalleryBackedHomeRecommendationRepository(
        configLoad = { config },
        plannerFactory = { cfg -> HomeRecommendationPlanner(chat, cfg) },
        currentDateProvider = { "2026-08-17" },
    )

    private fun fakeChat(onCall: () -> Unit = {}, content: String = "{\"recommendations\":[]}"): ChatClient =
        object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                onCall()
                return ChatCompletionResult(content = content, finishReason = "stop", raw = content)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }

    @Test
    fun `mixed gallery and daily log both contribute to the prompt without a uri`() = runBlocking {
        val requests = mutableListOf<ChatCompletionsRequest>()
        val capturing = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                requests += r
                return ChatCompletionResult(content = "{\"recommendations\":[]}", finishReason = "stop", raw = "")
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val r = repo(capturing)
        val result = r.generate(listOf(galleryItem("gx_1")), listOf(dailyEntry(1), dailyEntry(2)))

        val eventIdSet = result.coveredSourceIds.toSet()
        assertTrue(eventIdSet.contains("gx_1"))
        assertTrue(eventIdSet.contains("2026-08-02/wechat.logs.md#1"))
        assertTrue(eventIdSet.contains("2026-08-02/wechat.logs.md#2"))

        // The user prompt includes real daily-log text and never a content URI/token.
        val prompt = requests.first().messages.joinToString("\n") { it.content.orEmpty() }
        assertTrue(prompt.contains("今天计划去买日用品"))
        assertFalse(prompt.contains("content://"))
        assertFalse(prompt.contains("sk-planner-secret"))
    }

    @Test
    fun `no gallery and no daily log yields NoData with zero calls`() = runBlocking {
        var calls = 0
        val r = repo(fakeChat(onCall = { calls++ }))
        val result = r.generate(emptyList(), emptyList())
        assertTrue(result.state is HomeRecommendationGenerationState.NoData)
        assertEquals("zero network calls when there is no gallery nor daily-log", 0, calls)
    }

    @Test
    fun `normalizer drops daily-log ids that are not real input`() = runBlocking {
        // Model references a nonexistent daily-log id → the item is dropped.
        val chat = fakeChat(
            content = """{"recommendations":[
                {"tag":"待处理","title":"真实","desc":"真实","domain":"live","source_event_ids":["2026-08-02/wechat.logs.md#1"]},
                {"tag":"待处理","title":"伪造","desc":"伪造","domain":"live","source_event_ids":["2099-01-01/fake.md#9"]}
            ]}""",
        )
        val r = repo(chat)
        val result = r.generate(emptyList(), listOf(dailyEntry(1)))
        val state = result.state as? HomeRecommendationGenerationState.Success
        assertTrue("must return Success with the valid item", state != null)
        assertEquals(1, state!!.recommendations.size)
        assertEquals("真实", state.recommendations[0].title)
    }

    @Test
    fun `gallery-only call stays compatible via the single-arg generate`() = runBlocking {
        var calls = 0
        val chat = fakeChat(
            onCall = { calls++ },
            content = """{"recommendations":[]}""",
        )
        val r = repo(chat)
        val result = r.generate(listOf(galleryItem("gx_1")))
        assertTrue(result.state is HomeRecommendationGenerationState.Success)
        assertEquals(1, calls)
        assertTrue(result.coveredSourceIds.contains("gx_1"))
    }
}
