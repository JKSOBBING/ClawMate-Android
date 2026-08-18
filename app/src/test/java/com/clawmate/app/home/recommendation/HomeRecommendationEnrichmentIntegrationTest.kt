package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.datamanager.DailyLogEntry
import com.clawmate.app.datamanager.DailyLogParser
import com.clawmate.app.datamanager.DailyLogPlannerEnrichment
import com.clawmate.app.datamanager.DailyLogPlannerEventMapper
import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-39 focused JVM tests for the Home planner's consumption of the Phase-38
 * persisted batch event-enrichment fields.
 *
 * The repository accepts a `dailyLogEnrichment` resolver (in production bound to
 * [com.clawmate.app.datamanager.DailyLogEnrichmentResolver]); these tests drive
 * that seam directly so behaviour is deterministic and offline:
 *  - with a real enrichment for an entry's sourceRef, the planner input carries
 *    the enriched keywords / domains / todo fields;
 *  - with no enrichment (no artifact / unknown id) the planner input keeps the
 *    raw daily-log mapping (old behaviour);
 *  - [DailyLogPlannerEventMapper.applyEnrichment] only overlays by real eventId
 *    (= sourceRef) and never lets an unknown id change an event.
 */
class HomeRecommendationEnrichmentIntegrationTest {

    private val config = HomeRecommendationConfig(
        baseUrl = "https://planner.test",
        apiKey = "sk-planner-secret",
        modelName = "gpt-4o",
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

    private fun capturingRepo(
        requests: MutableList<ChatCompletionsRequest>,
        enrichment: Map<String, DailyLogPlannerEnrichment>,
    ): HomeRecommendationRepository = GalleryBackedHomeRecommendationRepository(
        configLoad = { config },
        plannerFactory = { cfg -> HomeRecommendationPlanner(capturingChat(requests), cfg) },
        currentDateProvider = { "2026-08-17" },
        dailyLogEnrichment = { entries ->
            entries.associate { it.sourceRef to (enrichment[it.sourceRef] ?: DailyLogPlannerEnrichment()) }
                .filterValues { !it.isEmpty }
        },
    )

    private fun capturingChat(requests: MutableList<ChatCompletionsRequest>): ChatClient = object : ChatClient {
        override suspend fun completion(r: ChatCompletionsRequest): ChatCompletionResult {
            requests += r
            return ChatCompletionResult(content = "{\"recommendations\":[]}", finishReason = "stop", raw = "")
        }

        override fun completionStream(r: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    /** The prompt payload for a given event_id, from a captured request. */
    private fun promptFor(requests: List<ChatCompletionsRequest>, eventId: String): String {
        val prompt = requests.first().messages.joinToString("\n") { it.content.orEmpty() }
        return prompt.substringAfter("\"event_id\" : \"$eventId\"")
    }

    @Test
    fun `planner input carries enriched keywords domains and todo when artifact present`() = runBlocking {
        val req = mutableListOf<ChatCompletionsRequest>()
        val entry1 = dailyEntry(1)
        val r = capturingRepo(
            req,
            mapOf(
                entry1.sourceRef to DailyLogPlannerEnrichment(
                    keywords = listOf("日用品", "超市"),
                    domains = listOf("live"),
                    todoType = "buy",
                    todoDeadline = "2026-08-05",
                    todoText = "去超市买日用品",
                ),
            ),
        )
        val result = r.generate(emptyList(), listOf(entry1))
        assertTrue(result.state is HomeRecommendationGenerationState.Success)

        val p = promptFor(req, entry1.sourceRef)
        assertTrue("keywords should appear", p.contains("日用品"))
        assertTrue("domains should appear", p.contains("\"live\""))
        assertTrue("todo_type should appear", p.contains("\"buy\""))
        assertTrue("todo_deadline should appear", p.contains("2026-08-05"))
        assertTrue("todo_text should appear", p.contains("去超市买日用品"))
    }

    @Test
    fun `planner keeps raw mapping when no enrichment (old behaviour)`() = runBlocking {
        val req = mutableListOf<ChatCompletionsRequest>()
        val entry1 = dailyEntry(1)
        // No enrichment at all -> raw daily-log mapping (workflow keywords, no todo).
        val r = capturingRepo(req, emptyMap())
        val result = r.generate(emptyList(), listOf(entry1))
        assertTrue(result.state is HomeRecommendationGenerationState.Success)

        val p = promptFor(req, entry1.sourceRef)
        // Raw text present; raw metadata keyword from the entry mapping.
        assertTrue(p.contains("今天计划去买日用品"))
        assertTrue(p.contains("\"workflow=wechat\""))
        // No enriched fields (no artifact / no match): todo_type stays blank and no
        // enriched todo/deadline/domains are injected.
        assertTrue(!p.contains("\"buy\""))
        assertTrue(!p.contains("2026-08-05"))
        assertTrue(!p.contains("去超市买日用品"))
    }

    @Test
    fun `unknown id enrichment never changes a planner event`() {
        val mapperEvents = DailyLogPlannerEventMapper.toPlannerEvents(listOf(dailyEntry(1)))
        // Enrichment keyed by a sourceRef that is NOT an input event id.
        val bogus = mapOf("2099-01-01/fake.md#9" to DailyLogPlannerEnrichment(
            keywords = listOf("伪造"),
            todoText = "不应注入",
        ))
        val overlaid = DailyLogPlannerEventMapper.applyEnrichment(mapperEvents, bogus)
        assertEquals(mapperEvents.size, overlaid.size)
        val event = overlaid.first()
        // The raw mapping's provenance keyword is untouched and no bogus fields were injected.
        assertTrue(event.keywords.contains("workflow=wechat"))
        assertTrue(!event.keywords.contains("伪造"))
        // Raw mapping always sets todoText from the entry text; the bogus id's text
        // ("不应注入") must never replace it.
        assertEquals("今天计划去买日用品", event.todoText)
    }

    @Test
    fun `real enrichment overlay replaces raw keywords by real event id`() {
        val entry = dailyEntry(1)
        val mapperEvents = DailyLogPlannerEventMapper.toPlannerEvents(listOf(entry))
        val realEnrichment = mapOf(
            entry.sourceRef to DailyLogPlannerEnrichment(
                keywords = listOf("日用品", "超市"),
                domains = listOf("live"),
                todoType = "buy",
            ),
        )
        val overlaid = DailyLogPlannerEventMapper.applyEnrichment(mapperEvents, realEnrichment)
        val event = overlaid.first()
        assertEquals(listOf("日用品", "超市"), event.keywords.let { it.take(2) })
        assertEquals(listOf("live"), event.domains)
        assertEquals("buy", event.todoType)
    }
}
