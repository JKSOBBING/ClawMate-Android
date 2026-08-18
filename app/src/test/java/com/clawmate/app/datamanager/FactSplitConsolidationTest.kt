package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.CloudStreamChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-38 focused JVM tests for the **fact-split → persisted model
 * consolidation** downstream wiring ([FactSplitConsolidationCoordinator] +
 * [CloudConsolidationRepository.consolidateWithSplitFacts]).
 *
 * Coverage (all offline over a temp dir):
 *  - EMPTY (no real daily-log entries) → zero network, nothing persisted;
 *  - LOCAL_ONLY when the model gate is refused (unconfigured / not explicit) →
 *    zero network, nothing persisted;
 *  - an explicit + fully-configured action consumes the Phase-37 fact split into
 *    the existing model-aware engine and **persists** per-group state.json +
 *    markdown + state-index (outputs) and re-derives the local index;
 *  - a real entry whose `source_ref` is missing from the caller's split map still
 *    gets the deterministic heuristic split (never dropped);
 *  - model failure on the split → FALLBACK; the deterministic local facts are
 *    consolidated/persisted and the prior valid state is preserved;
 *  - cancellation propagates.
 */
class FactSplitConsolidationTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase38-factscons").toFile().apply { deleteOnExit() }

    private fun writeLog(base: File, day: String, file: String, entries: List<String>) =
        DailyLogWorkspace.writeDocument(base, day, file, entries)

    private val config = ConsolidationModelConfig(baseUrl = "https://planner.test", apiKey = "sk-facts-cons-secret", modelName = "gpt-4o")

    @After
    fun tearDown() {
        com.clawmate.app.config.EndpointConfig.load(java.util.Properties())
    }

    /** Scripted [ChatClient]; distinguishes split (`entry_text`) from dedup prompts. */
    private class ScriptedChat : ChatClient {
        var calls = 0
        var error: ChatException? = null
        var splitReply: String = ""
        var dedupReply: String = """{"decision":"new","matched_record_ids":[],"reason":"r"}"""
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
            calls++
            error?.let { throw it }
            val user = request.messages.joinToString("") { it.content.orEmpty() }
            val content = if (user.contains("entry_text")) splitReply else dedupReply
            return ChatCompletionResult(content = content, finishReason = "stop", raw = null)
        }
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    // ------------------------------------------------------------------

    @Test
    fun `coordinator reports EMPTY with zero network when there are no real entries`() = runBlocking {
        val base = tempBase()
        val chat = ScriptedChat()
        val coordinator = FactSplitConsolidationCoordinator(base)
        val result = coordinator.run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertEquals(FactSplitConsolidationCoordinator.CoordinatorStatus.EMPTY, result.status)
        assertFalse(result.persisted)
        assertEquals(0, chat.calls)
        // No artifact created.
        assertFalse(DataManagerPaths.stateIndex(base).exists())
    }

    @Test
    fun `coordinator reports LOCAL_ONLY and persists nothing when unconfigured`() = runBlocking {
        com.clawmate.app.config.EndpointConfig.load(java.util.Properties())
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val chat = ScriptedChat()
        val coordinator = FactSplitConsolidationCoordinator(base)
        val result = coordinator.run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = null),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertEquals(FactSplitConsolidationCoordinator.CoordinatorStatus.LOCAL_ONLY, result.status)
        assertEquals(0, chat.calls) // zero network
        assertFalse(result.persisted)
    }

    @Test
    fun `explicit configured action persists outputs index and profile from the fact split`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        chat.splitReply =
            """{"items":[{"source_ref":"2026-08-01/a.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]}]}"""
        val coordinator = FactSplitConsolidationCoordinator(base)
        val result = coordinator.run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertEquals(FactSplitConsolidationCoordinator.CoordinatorStatus.MODEL, result.status)
        assertTrue(result.persisted)
        assertTrue(chat.calls >= 1) // model was invited (split + dedup)
        // Persisted outputs: state.json + markdown + a state index + an event index.
        val stateFile = DataManagerPaths.outputState(base, "2026-08", "a.logs.md")
        assertTrue("state.json missing", stateFile.exists())
        val state = LocalConsolidationCodec.decodeState(stateFile.readText())
        assertTrue("records were persisted", state.records.size >= 1)
        assertTrue(DataManagerPaths.stateIndex(base).exists())
        // The local index was re-derived from the persisted states.
        val idx = DailyLogLocalIndex(base).query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        assertTrue("indexed events were rebuilt", idx.totalEvents >= 1)
    }

    @Test
    fun `missing real ref in the split map still persists a heuristic fact for that entry`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-02", "b.logs.md", listOf("地铁通勤 5 元"))
        val chat = ScriptedChat()
        // Model returns a split for a fabricated/unknown ref only; the real ref is absent.
        chat.splitReply = """{"items":[{"source_ref":"not-real","facts":[]}]}"""
        val coordinator = FactSplitConsolidationCoordinator(base)
        val result = coordinator.run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        // The real entry's facts fall back to the deterministic heuristic (never dropped).
        assertTrue(result.persisted)
        val stateFile = DataManagerPaths.outputState(base, "2026-08", "b.logs.md")
        assertTrue(stateFile.exists())
        val state = LocalConsolidationCodec.decodeState(stateFile.readText())
        assertTrue("real entry produced at least one heuristic record", state.records.size >= 1)
        assertTrue(state.records.first().normalizedFact.contains("通勤"))
    }

    @Test
    fun `model failure on the split falls back and preserves prior valid state`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))

        // First: an explicit MODEL success persists a valid state.
        val goodChat = ScriptedChat()
        goodChat.splitReply =
            """{"items":[{"source_ref":"2026-08-01/a.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]}]}"""
        val good = FactSplitConsolidationCoordinator(base).run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(goodChat, it) },
        )
        assertEquals(FactSplitConsolidationCoordinator.CoordinatorStatus.MODEL, good.status)
        val before = LocalConsolidationCodec.decodeState(
            DataManagerPaths.outputState(base, "2026-08", "a.logs.md").readText(),
        )
        assertEquals(1, before.records.size)

        // Then add a NEW pending entry and let the model fail.
        writeLog(base, "2026-08-02", "a.logs.md", listOf("再买咖啡花了 26 元"))
        val badChat = ScriptedChat()
        badChat.error = ChatException(ChatErrorKind.HTTP, "500 boom", httpCode = 500)
        val after = FactSplitConsolidationCoordinator(base).run(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(badChat, it) },
        )
        assertEquals(FactSplitConsolidationCoordinator.CoordinatorStatus.FALLBACK, after.status)
        assertTrue(after.persisted)
        val stateAfter = LocalConsolidationCodec.decodeState(
            DataManagerPaths.outputState(base, "2026-08", "a.logs.md").readText(),
        )
        // The prior valid record is never erased; the new entry's local fact was added.
        assertTrue("prior valid record must survive", stateAfter.records.size >= 1)
        assertTrue(stateAfter.records.any { it.normalizedFact == "买了咖啡花了 25 元" })
    }

    @Test
    fun `cancellation propagates from the coordinator`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        val coordinator = FactSplitConsolidationCoordinator(base)
        val cancelled = try {
            coordinator.run(
                mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
                clientProvider = { ConsolidationModelClient(chat, it) },
                isCancelled = { true }, // cancel before anything
            )
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            e
        }
        assertNotNull("cancellation must propagate", cancelled)
    }
}
