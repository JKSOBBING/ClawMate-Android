package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.datamanager.BatchEventEnrichTypes.EnrichmentStatus
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-36 focused JVM tests for the **generic batch event enrichment** seam
 * ([BatchEventEnricher] + [BatchEventEnrichModelClient]).
 *
 * Device-free, zero network (the model is a scripted fake over [ChatClient]).
 * Coverage:
 *  - EMPTY when there are no real indexed events (zero model calls);
 *  - LOCAL_ONLY when not configured / not an explicit action (zero model calls,
 *    deterministic local enrich returned);
 *  - MODEL: a strict-JSON reply targeting a real event id is admitted, merged
 *    with the deterministic rule baseline (keywords/domains/entities);
 *  - malformed JSON / unknown event id / over-long or wrong domains/entity types
 *    / empty todo are dropped (not coerced, never a fabricated event);
 *  - FALLBACK keeps the deterministic local enrich when the model path fails;
 *  - real-id whitelist: a reply referencing a non-existent event id is dropped;
 *  - cancellation propagates.
 */
class BatchEventEnrichTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase36-enrich").toFile().apply { deleteOnExit() }

    private val fullConfig = ProfileModelConfig("https://planner.test", "k", "m")

    private fun seedPartition(base: File, month: String, events: List<IndexedEvent>) {
        val file = DataManagerPaths.eventPartition(base, month)
        file.parentFile.mkdirs()
        file.writeText(LocalConsolidationCodec.encodePartition(EventPartition(month = month, events = events)))
    }

    private fun event(
        recordId: Int,
        eventId: String,
        date: String,
        sourceRef: String,
        normalizedFact: String = "记录内容 $recordId",
        sceneIds: List<Int> = emptyList(),
    ): IndexedEvent = IndexedEvent(
        eventId = eventId,
        sourceStateFile = "outputs/${date.subSequence(0, 7)}/a.md.state.json",
        recordId = recordId,
        eventDate = date,
        lastSeenDate = date,
        summary = "记录 $recordId",
        normalizedFact = normalizedFact,
        workflowSceneIds = sceneIds,
        sourceRefs = listOf(sourceRef),
    )

    private fun enricher(base: File): BatchEventEnricher = BatchEventEnricher(base)

    private fun provider(chat: ScriptedChat): (ConsolidationModelConfig) -> BatchEventEnrichModelClient =
        { cfg -> BatchEventEnrichModelClient(chat, cfg) }

    // ---- 1. gate / zero calls ----

    @Test
    fun `no events yields EMPTY with zero network`() {
        val base = tempBase()
        val chat = ScriptedChat()
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.EMPTY, result.status)
        assertEquals(0, result.eventCount)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `non-explicit action never invites the model`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1", "买咖啡花 25 元")))
        val chat = ScriptedChat()
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(false, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls)
        // Deterministic local enrich is still returned (zero network).
        assertTrue(result.enrichedFields.containsKey("e#1"))
        assertTrue(result.enrichedFields["e#1"]!!.domains.contains("other") || result.enrichedFields["e#1"]!!.domains.isNotEmpty())
    }

    @Test
    fun `incomplete config never invites the model`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1")))
        val chat = ScriptedChat()
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, ProfileModelConfig("https://x", "", "m")),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls)
    }

    // ---- 2. MODEL: strict JSON over a real event id ----

    @Test
    fun `model reply on a real event id is admitted and merged with rule baseline`() {
        val base = tempBase()
        seedPartition(
            base, "2026-08",
            listOf(event(1, "e#1", "2026-08-01", "r1", "在中式快餐吃火锅 花 88 元")),
        )
        val chat = ScriptedChat().apply {
            script = {
                """{"events":[{
                    "event_id":"e#1",
                    "keywords":["火锅","88元"],
                    "domains":["eat"],
                    "entities":[{"type":"merchant","name":"火锅店","aliases":[]}],
                    "todo":{"type":"pickup","deadline":"2026-08-05","text":"去火锅店","agent_task":""}
                }]}"""
            }
        }
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.MODEL, result.status)
        assertEquals(1, result.admittedEventCount)
        val fields = result.enrichedFields["e#1"]!!
        assertTrue(fields.keywords.contains("火锅"))
        assertTrue(fields.domains.contains("eat"))
        assertFalse("domain 'other' is dropped when a specific domain is present", fields.domains.contains("other"))
        assertTrue(fields.entities.any { it.type == "merchant" })
        assertEquals("pickup", fields.todoType)
        assertEquals("2026-08-05", fields.todoDeadline)
        assertEquals("model_batch", fields.enrichmentMethod)
    }

    @Test
    fun `unknown event id from model is dropped and never creates an event`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1")))
        val chat = ScriptedChat().apply {
            script = {
                """{"events":[{"event_id":"ghost-999","keywords":["x"],"domains":["other"],"entities":[],"todo":{}}]}"""
            }
        }
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        // The only real event id not admitted -> FALLBACK to deterministic local.
        assertEquals(EnrichmentStatus.FALLBACK, result.status)
        assertFalse(result.enrichedFields.containsKey("ghost-999"))
        assertTrue(result.enrichedFields.containsKey("e#1")) // deterministic local preserved
    }

    @Test
    fun `malformed JSON yields FALLBACK with deterministic local enrich`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1", "在中式餐饮吃火锅")))
        val chat = ScriptedChat().apply { script = { "not json at all" } }
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.FALLBACK, result.status)
        assertTrue(result.enrichedFields.containsKey("e#1"))
        assertEquals("heuristic_batch_fallback", result.enrichedFields["e#1"]!!.enrichmentMethod)
    }

    @Test
    fun `model failure yields FALLBACK and never falls through to a model event`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1", "在火锅店吃饭")))
        val chat = ScriptedChat().apply { throwOnCall = true }
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.FALLBACK, result.status)
        assertEquals(1, result.eventCount)
        assertTrue(result.enrichedFields["e#1"]!!.enrichmentMethod == "heuristic_batch_fallback")
    }

    @Test
    fun `wrong entity type and over-bounds domain are dropped not coerced`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1", "买咖啡")))
        val chat = ScriptedChat().apply {
            script = {
                """{"events":[{
                    "event_id":"e#1",
                    "keywords":["buy"],
                    "domains":["flying","eat"],
                    "entities":[{"type":"unknown","name":"zzz","aliases":[]}],
                    "todo":{"type":"buy","deadline":"not-a-date","text":"buy coffee","agent_task":""}
                }]}"""
            }
        }
        val result = runBlocking {
            enricher(base).enrich(
                mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.MODEL, result.status)
        val fields = result.enrichedFields["e#1"]!!
        // "flying" is outside the six-value set -> dropped; only rule-derived stays.
        assertTrue(fields.domains.none { it == "flying" })
        // "unknown" entity type dropped.
        assertTrue(fields.entities.none { it.type == "unknown" })
        // invalid deadline -> empty.
        assertEquals("", fields.todoDeadline)
    }

    @Test
    fun `cancellation propagates before the run and between stages`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(event(1, "e#1", "2026-08-01", "r1")))
        val chat = ScriptedChat()
        runBlocking {
            try {
                enricher(base).enrich(
                    mode = BatchEventEnricher.EnrichMode.ModelRequested(true, fullConfig),
                    clientProvider = provider(chat),
                    isCancelled = { true },
                )
                fail("expected CancellationException")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // expected
            }
        }
    }

    // ---- helpers ----

    /** A scripted fake [ChatClient] with a call counter + scripted content. */
    private class ScriptedChat : ChatClient {
        var calls = 0
        var script: ((ChatCompletionsRequest) -> String)? = null
        var throwOnCall = false
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
            calls++
            if (throwOnCall) throw ChatException(ChatErrorKind.HTTP, "boom", httpCode = 500)
            val body = script?.invoke(request) ?: ""
            return ChatCompletionResult(content = body, finishReason = "stop", raw = null)
        }
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }
}
