package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.FactSplitTypes.FactSplitStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-37 focused JVM tests for the **explicit fact-splitting** seam
 * ([FactSplitRunner]) and the Phase-37 fidelity fixes to
 * [ConsolidationModelClient] (singular `splitEntryIntoFacts`, `event_date` bounds
 * + `source_date` fallback, and model-fact atomicization).
 *
 * Coverage:
 *  - default-disabled / zero network: constructing the runner schedules nothing;
 *    a local-only mode returns the deterministic split with zero network;
 *  - EMPTY when there are no real daily-log entries;
 *  - the model path is only reached for an explicit + fully-configured action;
 *  - strict JSON decode + real `source_ref` whitelist: unknown / duplicate refs
 *    are dropped and never create a fact or an event;
 *  - date / source scope: an out-of-bounds `event_date` (> source_date) or a
 *    non-ISO date is replaced by `source_date`; only real refs/date scope pass;
 *  - malformed / model failure -> deterministic local fallback (never fake facts);
 *  - cancellation propagates (CancellationException before / during model);
 *  - `splitEntryIntoFacts` singular mirrors the batch behavior and falls back to
 *    the heuristic on a malformed reply;
 *  - atomicization of a multi-record model fact.
 * All runs are offline over a temp dir.
 */
class FactSplitTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase37-facts").toFile().apply { deleteOnExit() }

    private fun writeLog(base: File, day: String, file: String, entries: List<String>, metadata: Map<String, String> = emptyMap()): File? =
        DailyLogWorkspace.writeDocument(base, day, file, entries, metadata)

    private val config = ConsolidationModelConfig(baseUrl = "https://planner.test", apiKey = "sk-fact-split-secret", modelName = "gpt-4o")

    @After
    fun tearDown() {
        com.clawmate.app.config.EndpointConfig.load(java.util.Properties())
    }

    /** A scripted fake [ChatClient] with a call counter + per-request handler. */
    private class ScriptedChat : ChatClient {
        var calls = 0
        var handler: ((ChatCompletionsRequest) -> ChatCompletionResult)? = null
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
            calls++
            return handler?.invoke(request) ?: ChatCompletionResult(content = "", finishReason = "stop", raw = null)
        }

        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    private fun runBaseDir(): File = tempBase()

    // ------------------------------------------------------------------
    // default-disabled / zero-network gate
    // ------------------------------------------------------------------

    @Test
    fun `constructing the runner does nothing and local-only mode is zero network`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.LocalOnly,
            clientProvider = { config -> ConsolidationModelClient(ScriptedChat(), config) },
        )
        // Deterministic local split returned, no network.
        assertEquals(FactSplitStatus.LOCAL_ONLY, result.status)
        assertTrue(result.sourceCount > 0)
        assertTrue(result.facts.values.all { it.isNotEmpty() })
    }

    @Test
    fun `EMPTY when there are no real daily-log entries`() = runBlocking {
        val base = tempBase()
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(ScriptedChat(), it) },
        )
        assertEquals(FactSplitStatus.EMPTY, result.status)
        assertEquals(0, result.sourceCount)
    }

    @Test
    fun `model path is not reached when the action is not explicit`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val chat = ScriptedChat()
        chat.handler = { ChatCompletionResult("""{"items":[]}""", "stop", null) }
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = false, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        // No model call was made; zero network; local split returned.
        assertEquals(0, chat.calls)
        assertEquals(FactSplitStatus.LOCAL_ONLY, result.status)
    }

    @Test
    fun `model path is not reached when the config is missing`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val chat = ScriptedChat()
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = null),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertEquals(0, chat.calls)
        assertEquals(FactSplitStatus.LOCAL_ONLY, result.status)
    }

    // ------------------------------------------------------------------
    // strict JSON + real source_ref whitelist + date bounds
    // ------------------------------------------------------------------

    @Test
    fun `model path admits only real source refs and rejects unknown duplicate refs`() = runBlocking {
        val base = tempBase()
        val log = writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val chat = ScriptedChat()
        // The reply includes a ref that is NOT in the real scope (fabricated).
        chat.handler = { ChatCompletionResult(
            """{"items":[{"source_ref":"2026-08-01/a.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]},""" +
                """{"source_ref":"2000-01-01/bogus.md#999","facts":[{"summary":"编造","normalized_fact":"编造的事实 99 元","event_date":"2000-01-01","tags":[]}]}]}""",
            "stop", null,
        ) }
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        // The bogus source_ref is dropped: it is not in the real scope.
        assertTrue(result.facts.keys.none { it.contains("bogus") })
        assertTrue(result.facts.keys.contains("2026-08-01/a.logs.md#1"))
        assertEquals(1, chat.calls)
    }

    @Test
    fun `duplicate source refs are collapsed to a single split`() = runBlocking {
        val base = tempBase()
        // Two entries sharing the same source_ref (overlapping day/file/index).
        val runner = FactSplitRunner(
            base,
            eventSource = object : DailyLogEventSource {
                override fun load(): DailyLogScanResult = DailyLogScanResult(
                    entries = listOf(
                        DailyLogEntry("2026-08-01", "a.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-01/a.logs.md#1"),
                        DailyLogEntry("2026-08-01", "a.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-01/a.logs.md#1"),
                    ),
                    errors = emptyList(),
                )
                override fun isEmpty(): Boolean = false
            },
        )
        val result = runner.splitFacts(FactSplitRunner.SplitMode.LocalOnly)
        // Only one scope admitted for the shared ref (refused to split the same ref twice).
        assertEquals(1, result.sourceCount)
        assertEquals(FactSplitStatus.LOCAL_ONLY, result.status)
    }

    @Test
    fun `out of bounds event_date is replaced by the source date`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val client = ConsolidationModelClient(ScriptedChat(), config)
        val entry = DailyLogEntry(
            sourceDate = "2026-08-01",
            fileName = "a.logs.md",
            entryIndex = 1,
            text = "今天买书花了 45 元",
            workflowMetadata = emptyMap(),
            latestEntryIndex = 1,
            sourceRef = "2026-08-01/a.logs.md#1",
        )
        val facts = client.validateSplitFacts(
            listOf(
                CloudConsolidationTypes.RawSplitFact(summary = "买书", normalizedFact = "今天买书花了 45 元", eventDate = "2026-08-05", tags = emptyList(), modelNotes = "m"),
            ),
            entry,
        )
        // A future event_date (> source_date) is refused: replaced by source_date.
        assertEquals(1, facts.size)
        assertEquals("2026-08-01", facts[0].eventDate)
    }

    @Test
    fun `invalid non ISO event_date is replaced by the source date`() = runBlocking {
        val base = tempBase()
        val client = ConsolidationModelClient(ScriptedChat(), config)
        val entry = DailyLogEntry("2026-08-01", "a.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-01/a.logs.md#1")
        val facts = client.validateSplitFacts(
            listOf(
                CloudConsolidationTypes.RawSplitFact(summary = "咖啡", normalizedFact = "买了咖啡花了 25 元", eventDate = "明天", tags = emptyList(), modelNotes = "m"),
            ),
            entry,
        )
        assertEquals(1, facts.size)
        assertEquals("2026-08-01", facts[0].eventDate)
    }

    // ------------------------------------------------------------------
    // singular `splitEntryIntoFacts` + atomicization + fallback
    // ------------------------------------------------------------------

    @Test
    fun `splitEntryIntoFacts already exposes the singular Harmony seam`() = runBlocking {
        val chat = ScriptedChat()
        chat.handler = { ChatCompletionResult("""{"items":[{"source_ref":"2026-08-01/a.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]}]}""", "stop", null) }
        val client = ConsolidationModelClient(chat, config)
        val entry = DailyLogEntry("2026-08-01", "a.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-01/a.logs.md#1")
        val facts = client.splitEntryIntoFacts(entry)
        assertTrue(facts.isNotEmpty())
        assertEquals("买了咖啡花了 25 元", facts.first().normalizedFact)
    }

    @Test
    fun `splitEntryIntoFacts falls back to the heuristic on a malformed reply`() = runBlocking {
        val chat = ScriptedChat()
        chat.handler = { ChatCompletionResult("not valid json", "stop", null) }
        val client = ConsolidationModelClient(chat, config)
        val entry = DailyLogEntry("2026-08-01", "a.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-01/a.logs.md#1")
        val facts = client.splitEntryIntoFacts(entry)
        // Deterministic local fallback, never empty / never fake.
        assertTrue("heuristic fallback must produce a fact", facts.isNotEmpty())
        assertTrue(facts.first().normalizedFact.isNotEmpty())
    }

    @Test
    fun `model failure or malformed json falls back to a deterministic local split never fake`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        chat.handler = { ChatCompletionResult("not valid json", "stop", null) }
        val runner = FactSplitRunner(base)
        val result = runner.splitFacts(
            mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
            clientProvider = { ConsolidationModelClient(chat, it) },
        )
        // The deterministic local split is preserved; nothing fabricated.
        assertEquals(FactSplitStatus.FALLBACK, result.status)
        assertTrue(result.facts.values.all { it.isNotEmpty() })
        assertTrue(result.facts["2026-08-01/a.logs.md#1"].orEmpty().first().normalizedFact.contains("咖啡"))
    }

    @Test
    fun `model fact atomicization splits a multi record fact`() = runBlocking {
        val base = tempBase()
        val client = ConsolidationModelClient(ScriptedChat(), config)
        val entry = DailyLogEntry("2026-08-01", "b.logs.md", 1, "账单 今天消费", emptyMap(), 1, "2026-08-01/b.logs.md#1")
        // A model fact that packs two separate atomic amount records.
        val facts = client.validateSplitFacts(
            listOf(
                CloudConsolidationTypes.RawSplitFact(
                    summary = "账单",
                    normalizedFact = "买了咖啡花了25元。地铁通勤花了5元。",
                    eventDate = "2026-08-01",
                    tags = emptyList(),
                    modelNotes = "m",
                ),
            ),
            entry,
        )
        // The multi-record fact is atomicized into 2 atomic facts.
        assertTrue("model fact should break into atomic facts", facts.size >= 2)
        assertTrue(facts.any { it.normalizedFact.contains("25元") })
        assertTrue(facts.any { it.normalizedFact.contains("5元") })
        // Atomic facts keep the real source_ref / source_date / scene id.
        assertTrue(facts.all { it.sourceRef == "2026-08-01/b.logs.md#1" })
    }

    // ------------------------------------------------------------------
    // cancellation
    // ------------------------------------------------------------------

    @Test
    fun `cancellation before start propagates a CancellationException`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买了咖啡花了 25 元"))
        val runner = FactSplitRunner(base)
        try {
            runner.splitFacts(
                mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
                clientProvider = { ConsolidationModelClient(ScriptedChat(), it) },
                isCancelled = { true },
            )
            fail("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    @Test
    fun `cancellation after a model call aborts the run`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "a.logs.md", listOf("买咖啡花了 25 元"))
        val chat = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
                throw kotlinx.coroutines.CancellationException("cancelled mid-model")
            }
            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
        }
        val runner = FactSplitRunner(base)
        try {
            runner.splitFacts(
                mode = FactSplitRunner.SplitMode.ModelRequested(explicitAction = true, config = config),
                clientProvider = { ConsolidationModelClient(chat, it) },
            )
            fail("expected CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }
}
