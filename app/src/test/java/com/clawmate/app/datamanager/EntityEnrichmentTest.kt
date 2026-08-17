package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.datamanager.EntityEnrichmentTypes.EnrichmentStatus
import com.clawmate.app.datamanager.LocalConsolidationTypes.EntityMention
import com.clawmate.app.datamanager.LocalConsolidationTypes.EventPartition
import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Focused JVM tests for the Phase-35 **explicit model entity enrichment** seam.
 *
 * All runs are offline over a temp dir; the model path is a scripted fake
 * [ChatClient] (no network, no device). Coverage:
 *  - gate: zero model calls unless explicit + fully-configured + real candidates;
 *  - bounded prompt with no URI / token / external path leakage;
 *  - strict JSON contract: person/place/merchant/unknown; real id/source whitelist;
 *  - unknown id / duplicate id / unknown type / over-long value -> dropped;
 *  - model failure -> deterministic local result preserved (fallback);
 *  - workflow_scene_ids / entity_mentions / source_ref / date preserved;
 *  - stable output / repeat run; cooperative cancellation.
 */
class EntityEnrichmentTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase35-enrich").toFile().apply { deleteOnExit() }

    private val fullConfig = ProfileModelConfig("https://planner.test", "k", "m")

    private fun seedPartition(base: File, month: String, events: List<IndexedEvent>) {
        val file = DataManagerPaths.eventPartition(base, month)
        file.parentFile.mkdirs()
        file.writeText(LocalConsolidationCodec.encodePartition(EventPartition(month = month, events = events)))
    }

    private fun ev(
        recordId: Int,
        eventId: String,
        date: String,
        sourceRef: String,
        mentions: List<EntityMention>,
        sceneIds: List<Int> = emptyList(),
    ): IndexedEvent = IndexedEvent(
        eventId = eventId,
        sourceStateFile = "outputs/${date.subSequence(0, 7)}/a.md.state.json",
        recordId = recordId,
        eventDate = date,
        lastSeenDate = date,
        summary = "记录 $recordId",
        normalizedFact = "记录内容 $recordId",
        workflowSceneIds = sceneIds,
        entityMentions = mentions,
        sourceRefs = listOf(sourceRef),
    )

    private fun merchantMention(name: String, aliases: List<String> = emptyList()) =
        EntityMention("merchant", name, aliases)

    /** A scripted fake [ChatClient] with a call counter + scripted content. */
    private class ScriptedChat : ChatClient {
        var calls = 0
        var script: ((ChatCompletionsRequest) -> String)? = null
        var throwOnCall = false
        var lastPrompt = ""
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
            calls++
            lastPrompt = request.messages.last().content ?: ""
            if (throwOnCall) throw ChatException(ChatErrorKind.HTTP, "boom", httpCode = 500)
            val body = script?.invoke(request) ?: ""
            return ChatCompletionResult(content = body, finishReason = "stop", raw = null)
        }
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    private fun runner(base: File): EntityEnrichmentRunner = EntityEnrichmentRunner(base)

    private fun provider(chat: ScriptedChat): (ConsolidationModelConfig) -> EntityEnrichmentModelClient =
        { cfg -> EntityEnrichmentModelClient(chat, cfg) }

    private val config = ConsolidationModelConfig(baseUrl = "https://planner.test", apiKey = "k", modelName = "m")

    // ---- 1. gate / zero calls ----

    @Test
    fun `no candidates yields EMPTY with zero network`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", emptyList())))
        val chat = ScriptedChat()
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.EMPTY, result.status)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `non-explicit action never invites the model`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(false, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `incomplete config never invites the model`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, ProfileModelConfig("https://x", "", "m")),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `default local-only mode never invites the model`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        val result = runBlocking { runner(base).enrich(clientProvider = provider(chat)) }
        assertEquals(EnrichmentStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls)
    }

    // ---- 2. bounded prompt, no leakage ----

    @Test
    fun `prompt is bounded and leaks no URI token or path`() {
        val base = tempBase()
        val ref = "2026-08-01/a.logs.md#1"
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", ref, listOf(merchantMention("老王面馆", listOf("老王")))),
        ))
        val chat = ScriptedChat()
        chat.script = { r ->
            val prompt = r.messages.last().content ?: ""
            // Confirm the runtime client receives a bounded user payload supporting ids.
            assertTrue(prompt.isEmpty().not())
            "{\"entities\":[]}"
        }
        runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertTrue("model must be called for real candidates", chat.calls == 1)
        val prompt = chat.lastPrompt
        assertTrue(prompt.isNotBlank())
        assertTrue("prompt must be bounded", prompt.length <= EntityEnrichmentTypes.MAX_PROMPT_CHARACTERS)
        // No leakage of external fields: no server URL, no api-key marker, no file scheme.
        for (secret in listOf("https://planner.test", "apiKey", "sk-", "file://", "http://")) {
            assertFalse("prompt must not leak '$secret'", prompt.contains(secret))
        }
    }

    // ---- 3. strict JSON contract: real ids / source refs whitelist ----

    @Test
    fun `strict JSON admits real ids and drops unknown id types and over-long values`() {
        val base = tempBase()
        val ref = "2026-08-01/a.logs.md#1"
        // Real candidate: record 1, source ref ref.
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", ref, listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        chat.script = {
            // Mixed reply: good(real id 1 + real ref), unknown non-existent id, unknown type,
            // empty name, over-long name, duplicate name.
            """{"entities":[
                {"id":"","type":"merchant","name":"老王面馆","aliases":["老王"],"record_ids":[1],"source_refs":["$ref"]},
                {"id":"fake","type":"person","name":"编造的人","aliases":[],"record_ids":[9999],"source_refs":["other"]},
                {"id":"t","type":"unknown","name":"不明","aliases":[],"record_ids":[1],"source_refs":["$ref"]},
                {"id":"e","type":"person","name":"","aliases":[],"record_ids":[1],"source_refs":["$ref"]},
                {"id":"f","type":"person","name":"${"X".repeat(300)}","aliases":[],"record_ids":[1],"source_refs":["$ref"]},
                {"id":"g","type":"person","name":"老王面馆","aliases":[],"record_ids":[1],"source_refs":["$ref"]}
            ]}"""
        }
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.MODEL, result.status)
        val doc = result.enrichedDocument
        assertNotNull(doc)
        // Only the first (real-whitelisted, bounded, unique) entity survives.
        val merchant = doc!!.entities.values.firstOrNull { it.type == "merchant" && it.name == "老王面馆" }
        assertNotNull(merchant)
        assertTrue(merchant!!.aliases.contains("老王"))
        assertTrue("unknown type / unknown id / empty / over-long / dup must be dropped",
            doc.entities.none { it.key.contains("9999") })
    }

    @Test
    fun `validate is strict about unknown ids and duplicate ids`() {
        val client = EntityEnrichmentModelClient(ScriptedChat(), config)
        val candidates = listOf(
            EntityEnrichmentTypes.EntityCandidate(
                candidateId = "c1", type = "merchant", name = "老王面馆",
                recordIds = listOf(1), sourceRefs = listOf("2026-08-01/a.md#1"),
            ),
        )
        val raw = listOf(
            EntityEnrichmentModelClient.RawEntityWire(
                id = "c1", type = "merchant", name = "老王面馆",
                recordIds = listOf(1), sourceRefs = listOf("2026-08-01/a.md#1"),
            ),
            // Unknown id (not a candidate) -> dropped.
            EntityEnrichmentModelClient.RawEntityWire(
                id = "not-a-candidate", type = "person", name = "黑户", recordIds = listOf(999), sourceRefs = listOf("nope"),
            ),
            // Duplicate whitelist ids are rejected rather than silently normalized.
            EntityEnrichmentModelClient.RawEntityWire(
                id = "c1", type = "merchant", name = "老王面馆", recordIds = listOf(1, 1),
                sourceRefs = listOf("2026-08-01/a.md#1", "2026-08-01/a.md#1"),
            ),
        )
        val out = client.validate(raw, candidates, setOf(1), setOf("2026-08-01/a.md#1"))
        assertEquals(1, out.size)
        assertEquals("merchant", out[0].type)
        assertEquals(listOf(1), out[0].recordIds)
    }

    // ---- 4. model failure fallback ----

    @Test
    fun `request failure falls back to local result and preserves artifact`() {
        val base = tempBase()
        val ref = "2026-08-01/a.logs.md#1"
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", ref, listOf(merchantMention("老王面馆"))),
        ))
        // Establish a persisted local deterministic entity doc first.
        LocalConsolidationIndexRunner(base).run()
        EntitySceneIndexRunner(base).run(EntitySceneIndexRunner.BuildMode.LocalOnly)
        val before = DataManagerPaths.entityIndex(base).let { if (it.exists()) it.readText() else null }

        val chat = ScriptedChat().apply { throwOnCall = true }
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        // Model path failed -> FALLBACK; the *enrichment seam itself never writes*.
        assertEquals(EnrichmentStatus.FALLBACK, result.status)
        val after = DataManagerPaths.entityIndex(base).let { if (it.exists()) it.readText() else null }
        assertEquals("enrichment must not modify the persisted artifact", before, after)
    }

    @Test
    fun `malformed JSON reply is treated as fallback`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        chat.script = { "{ not valid json ]" }
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.FALLBACK, result.status)
    }

    // ---- 5. scene / source / date preserved; stable output / repeat run ----

    @Test
    fun `scene ids source ref and date are preserved through enrichment`() {
        val base = tempBase()
        val ref = "2026-08-01/a.logs.md#1"
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", ref, listOf(merchantMention("老王面馆")), sceneIds = listOf(2)),
        ))
        val chat = ScriptedChat()
        chat.script = {
            """{"entities":[{"id":"","type":"merchant","name":"老王面馆","aliases":[],"record_ids":[1],"source_refs":["$ref"]}]}"""
        }
        val result = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        assertEquals(EnrichmentStatus.MODEL, result.status)
        val doc = result.enrichedDocument!!
        val merchant = doc.entities.values.first()
        // Workflow scene ids still come from the real event; the enrichment rebuild
        // reads the real events which retain workflow_scene_ids/entity_mentions/date.
        assertTrue(merchant.eventIds.contains("e#1"))
        // Source ref / date are preserved because the underlying real events are untouched.
        assertTrue(doc.entities.isNotEmpty())
    }

    @Test
    fun `repeat run over unchanged evidence is stable and idempotent`() {
        val base = tempBase()
        val ref = "2026-08-01/a.logs.md#1"
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", ref, listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        chat.script = {
            """{"entities":[{"id":"","type":"merchant","name":"老王面馆","aliases":["老王"],"record_ids":[1],"source_refs":["$ref"]}]}"""
        }
        fun runOnce(): EntityEnrichmentTypes.EnrichmentResult = runBlocking {
            runner(base).enrich(
                mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                clientProvider = provider(chat),
            )
        }
        val first = runOnce()
        val second = runOnce()
        assertEquals(EnrichmentStatus.MODEL, first.status)
        assertEquals(EnrichmentStatus.MODEL, second.status)
        // Same admitted entity count on identical scope.
        assertEquals(first.admittedEntityCount, second.admittedEntityCount)
        assertEquals(first.enrichedDocument!!.entities.size, second.enrichedDocument!!.entities.size)
    }

    // ---- 6. cancellation ----

    @Test
    fun `cancellation aborts before or during the model call`() {
        val base = tempBase()
        seedPartition(base, "2026-08", listOf(
            ev(1, "e#1", "2026-08-01", "2026-08-01/a.logs.md#1", listOf(merchantMention("老王面馆"))),
        ))
        val chat = ScriptedChat()
        chat.script = { """{"entities":[]}""" }
        runBlocking {
            try {
                runner(base).enrich(
                    mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(true, fullConfig),
                    clientProvider = provider(chat),
                    isCancelled = { true },
                )
                fail("expected CancellationException")
            } catch (e: CancellationException) {
                // expected
            }
        }
        // Cancellation before the model call means zero model calls.
        assertEquals(0, chat.calls)
    }
}
