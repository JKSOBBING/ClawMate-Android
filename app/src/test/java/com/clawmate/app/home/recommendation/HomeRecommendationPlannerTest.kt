package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.home.domain.HomeRecommendationState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Device-free tests for the Phase-28 Home recommendation planner + strict
 * normalizer + config gate. Covers: no-call config gate (unconfigured),
 * prompt carrying only real event text, strict output validation (id / domain /
 * length / source-id membership), dedup/replace of source event ids, and
 * empty/error returns — all without a device or network.
 */
class HomeRecommendationPlannerTest {

    private val config = HomeRecommendationConfig(
        baseUrl = "https://planner.test",
        apiKey = "sk-planner-secret",
        modelName = "gpt-4o",
    )

    private fun event(id: String, summary: String = "摘要$id", keywords: List<String> = listOf("$id")):
        HomeRecommendationPlannerEvent =
        HomeRecommendationPlannerEvent(
            eventId = id,
            date = "2026-08-01",
            todoType = "",
            todoDeadline = "",
            todoText = "行动$id",
            summary = summary,
            normalizedFact = "事实$id",
            sources = listOf("Gallery"),
            domains = emptyList(),
            keywords = keywords,
            imagePaths = emptyList(),
        )

    @After
    fun tearDown() {
        // Never leak EndpointConfig state across tests.
        EndpointConfig.load(java.util.Properties())
    }

    // ------------------------------------------------------------ config gate

    @Test
    fun `config gate returns null and repo short-circuits to NotConfigured (zero calls)`() = runBlocking {
        EndpointConfig.load(java.util.Properties())
        assertNull(HomeRecommendationConfig.fromEndpointConfig())

        var calls = 0
        val gated = noCallRepo { calls++ }
        val result = gated.generate(listOf(galleryItem("gx_1")))
        assertTrue(result.state is HomeRecommendationGenerationState.NotConfigured)
        assertEquals("zero network calls when unconfigured", 0, calls)
    }

    /** Repo whose configLoad honours EndpointConfig (blank in the test above). */
    private fun noCallRepo(onCall: () -> Unit): HomeRecommendationRepository =
        GalleryBackedHomeRecommendationRepository(
            configLoad = { HomeRecommendationConfig.fromEndpointConfig() },
            plannerFactory = { cfg -> HomeRecommendationPlanner(fakeChatReturning("""{"recommendations":[]}""", onCall), cfg) },
            currentDateProvider = { "2026-08-17" },
        )

    // ------------------------------------------------------------ no data

    @Test
    fun `repo returns NoData with zero calls when there is no real gallery input`() = runBlocking {
        var calls = 0
        val repo = GalleryBackedHomeRecommendationRepository(
            configLoad = { config },
            plannerFactory = { cfg -> HomeRecommendationPlanner(fakeChatReturning("""{"recommendations":[]}""", { calls++ }), cfg) },
            currentDateProvider = { "2026-08-17" },
        )
        // Empty gallery → all blanks map to no events → NoData.
        val result = repo.generate(emptyList())
        assertTrue(result.state is HomeRecommendationGenerationState.NoData)
        assertEquals(0, calls)
    }

    @Test
    fun `repo returns NoData with zero calls when items carry no real text`() = runBlocking {
        var calls = 0
        val repo = GalleryBackedHomeRecommendationRepository(
            configLoad = { config },
            plannerFactory = { cfg -> HomeRecommendationPlanner(fakeChatReturning("""{"recommendations":[]}""", { calls++ }), cfg) },
            currentDateProvider = { "2026-08-17" },
        )
        val blankItem = GalleryRepoItemFactory.blank()
        val result = repo.generate(listOf(blankItem))
        assertTrue(result.state is HomeRecommendationGenerationState.NoData)
        assertEquals(0, calls)
    }

    // ------------------------------------------------------------ prompt

    @Test
    fun `user prompt includes real event text and never a URI or key`() {
        val planner = planner(chat = fakeChatReturning("""{"recommendations":[]}"""))
        val events = listOf(event("gx_1", summary = "便利店购物小票"), event("gx_2", summary = "旅行机票"))
        val messages = planner.buildMessages(events, "2026-08-17")
        assertEquals("system", messages[0].role)
        val user = messages[1].content.orEmpty()

        // Real summaries + current date survive into the bounded payload.
        assertTrue(user.contains("便利店购物小票"))
        assertTrue(user.contains("旅行机票"))
        assertTrue(user.contains("2026-08-17"))
        // event ids present.
        assertTrue(user.contains("gx_1"))
        assertTrue(user.contains("gx_2"))
        // system prompt instructs faithful, non-fabricated recommendation.
        val system = messages[0].content.orEmpty()
        assertTrue(system.contains("source_event_ids"))
        assertTrue(system.contains("不要编造"))

        // No raw URI or secret anywhere in the prompt.
        assertFalse(user.contains("content://"))
        assertFalse(user.contains("sk-planner-secret"))
        assertFalse(user.contains("sk-"))
        assertFalse(user.contains("Authorization"))
        assertFalse(user.contains("Bearer"))

        // image_paths is a faithful Harmony protocol field and may appear with
        // an empty list, but it must never carry a non-empty image path/URI
        // value. The field itself is not sensitive; the path values are.
        val userJson = Json.parseToJsonElement(user).jsonObject
        val eventsJson = userJson.getValue("events").jsonArray
        assertTrue(eventsJson.size >= 2)
        for (eventObj in eventsJson) {
            val paths = eventObj.jsonObject.getValue("image_paths").jsonArray
            // Field is required by the protocol; values must all be empty.
            paths.forEach { path ->
                assertFalse("image_paths value must be empty", path.jsonPrimitive.content.isNotBlank())
            }
        }
    }

    // ------------------------------------------------------------ strict output

    @Test
    fun `strict validation drops items with blank title or non-input ids`() {
        val events = listOf(event("gx_1", "真实摘要"), event("gx_2", "另一摘要"))
        val raw = listOf(
            mapOf(
                "title" to "有效推荐",
                "tag" to "待处理",
                "domain" to "live",
                "source_event_ids" to listOf("gx_1"),
            ),
            // blank title → dropped
            mapOf(
                "title" to "",
                "source_event_ids" to listOf("gx_2"),
            ),
            // id not in input → dropped
            mapOf(
                "title" to "伪造id推荐",
                "source_event_ids" to listOf("unknown_id"),
            ),
        )
        val out = HomeRecommendationNormalizer.normalize(raw, events)
        assertEquals(1, out.size)
        assertEquals("有效推荐", out[0].title)
        assertEquals("live", out[0].domain)
        assertEquals(listOf("gx_1"), out[0].sourceEventIds)
        assertTrue(out[0].id.startsWith("todo-"))
    }

    @Test
    fun `dedup ensures each source event appears in at most one recommendation`() {
        val events = listOf(event("gx_1"), event("gx_2"), event("gx_3"))
        val raw = listOf(
            mapOf("title" to "A", "source_event_ids" to listOf("gx_1", "gx_2")),
            // Shares gx_2 with the first; after dedup only a fresh id would keep it.
            mapOf("title" to "B", "source_event_ids" to listOf("gx_2", "gx_3")),
        )
        val out = HomeRecommendationNormalizer.normalize(raw, events)
        val ids = out.flatMap { it.sourceEventIds }
        // Every input id appears at most once across all admitted recommendations.
        val grouped = ids.groupingBy { it }.eachCount()
        grouped.forEach { (_, count) -> assertEquals("source id appears once", 1, count) }
        // The second item only has gx_3 as fresh → admitted via gx_3.
        assertTrue(out.any { it.sourceEventIds.contains("gx_3") })
        assertEquals(2, out.size)
        assertTrue(out[0].id != out[1].id)
    }

    @Test
    fun `domain is constrained and tag desc length are bounded`() {
        val events = listOf(event("gx_1"))
        val out = HomeRecommendationNormalizer.normalize(
            listOf(
                mapOf(
                    "title" to "推荐A",
                    "tag" to "非常长的超限标签标签标签",
                    "domain" to "not-a-domain",
                    "desc" to ("x".repeat(500)),
                    "source_event_ids" to listOf("gx_1"),
                ),
            ),
            events,
        )
        // Over-long tag is dropped (empty is honest); invalid domain → other; desc capped.
        assertEquals(1, out.size)
        assertEquals("other", out[0].domain)
        assertEquals("", out[0].tag)
        assertTrue(out[0].desc.length <= HomeRecommendationBounds.MAX_DESC_CHARACTERS)
        assertTrue(out[0].title.length <= HomeRecommendationBounds.MAX_TITLE_CHARACTERS)
    }

    @Test
    fun `recommendation count is capped`() {
        val events = (1..30).map { event("gx_$it") }
        val raw = (1..100).map {
            mapOf(
                "title" to "推荐$it",
                "source_event_ids" to listOf("gx_$it"),
            )
        }
        val out = HomeRecommendationNormalizer.normalize(raw, events)
        assertTrue(out.size <= HomeRecommendationBounds.MAX_RECOMMENDATIONS)
    }

    // ------------------------------------------------------------ errors / empty

    @Test
    fun `planner maps HTTP error to a typed redacted failure`() = runBlocking {
        val chat = object : ChatClient {
            override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
                throw ChatException(ChatErrorKind.HTTP, "invalid sk-planner-secret", httpCode = 401)
            }

            override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val e = try {
            planner(chat).plan(listOf(event("gx_1")), "2026-08-17")
            fail("expected HomeRecommendationException")
            null
        } catch (ex: HomeRecommendationException) {
            ex
        }
        assertEquals(HomeRecommendationErrorReason.HTTP, e!!.reason)
        assertEquals(401, e.httpCode)
        assertFalse(e.safeMessage.contains("sk-planner-secret"))
        assertFalse(e.safeMessage.contains("sk-"))
        assertTrue(e.userMessage.contains("HTTP"))
    }

    @Test
    fun `planner treats empty reply as honest empty result`() = runBlocking {
        val plan = planner(fakeChatReturning("   ")).plan(listOf(event("gx_1")), "2026-08-17")
        assertTrue(plan.recommendations.isEmpty())
    }

    @Test
    fun `planner throws NO_DATA when events are empty`() = runBlocking {
        val e = try {
            planner(fakeChatReturning("""{"recommendations":[]}""")).plan(emptyList(), "2026-08-17")
            fail("expected NO_DATA")
            null
        } catch (ex: HomeRecommendationException) {
            ex
        }
        assertEquals(HomeRecommendationErrorReason.NO_DATA, e!!.reason)
    }

    @Test
    fun `successful plan returns strictly normalized recommendations`() = runBlocking {
        val reply = """
            {"recommendations":[
              {"title":"完成旅行预订","tag":"待预订","domain":"travel","desc":"周五出发的机票待付款","source_event_ids":["gx_1"]}
            ]}
        """.trimIndent()
        val plan = planner(fakeChatReturning(reply)).plan(listOf(event("gx_1")), "2026-08-17")
        assertEquals(1, plan.recommendations.size)
        val rec: HomeRecommendationState = plan.recommendations[0]
        assertEquals("travel", rec.domain)
        assertEquals(listOf("gx_1"), rec.sourceEventIds)
        assertTrue(rec.title.isNotBlank())
        assertTrue(rec.id.startsWith("todo-"))
    }

    // ------------------------------------------------------------ helpers

    private fun planner(chat: ChatClient): HomeRecommendationPlanner =
        HomeRecommendationPlanner(chat, config)

    private fun fakeChatReturning(content: String, onCall: () -> Unit = {}): ChatClient = object : ChatClient {
        override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
            onCall()
            return ChatCompletionResult(content = content, finishReason = "stop", raw = content)
        }

        override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    private fun galleryItem(id: String): com.clawmate.app.gallery.model.GalleryPhotoItem =
        com.clawmate.app.gallery.model.GalleryPhotoItem(
            id = id,
            uri = "content://media/$id",
            date = "2026-08-01",
            title = "物品",
            category = "购物",
            summary = "摘要",
            tags = listOf("a"),
            keywords = listOf("b"),
            createdAt = 0L,
        )
}

/** Realistic fixture factory so tests exercise the real gallery-item mapping. */
private object GalleryRepoItemFactory {
    fun blank(): com.clawmate.app.gallery.model.GalleryPhotoItem =
        com.clawmate.app.gallery.model.GalleryPhotoItem(
            id = "gx_blank",
            uri = "content://media/blank",
            date = "2026-08-01",
            title = "",
            category = "",
            summary = "",
            createdAt = 0L,
        )
}
