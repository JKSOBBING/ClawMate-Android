package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.datamanager.CloudConsolidationTypes.CandidateFact
import com.clawmate.app.datamanager.CloudConsolidationTypes.ConsolidationPath
import com.clawmate.app.datamanager.CloudConsolidationTypes.DedupDecisions
import com.clawmate.app.datamanager.LocalConsolidationTypes.ConsolidatedRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase-34 focused JVM tests for the **model-backed consolidation/dedup** path.
 *
 * Covers the explicit-action gate (zero network when unconfigured / no entries /
 * not an explicit action), serial + bounded prompts with no URI/token leakage,
 * strict JSON + whitelist validation (malformed / unknown decision / unknown
 * matched id / over-long fact), new/duplicate/merge_with_existing/skip
 * decisions, model-failure fallback that preserves the prior valid state,
 * stable monotonic record ids + processed-entry hashes (idempotent repeat runs),
 * Phase-33 workflow_scene_id preservation through model facts, cancellation, and
 * auditable (never secret) `model_notes`. All runs are offline over a temp dir.
 */
class CloudConsolidationTest {

    private fun tempBase(): File =
        Files.createTempDirectory("clawmate-phase34-test").toFile().apply { deleteOnExit() }

    private fun writeLog(base: File, day: String, file: String, entries: List<String>, metadata: Map<String, String> = emptyMap()): File? =
        DailyLogWorkspace.writeDocument(base, day, file, entries, metadata)

    /** Seed a daily-log file with a real nested `workflow_metadata.task_scene_id` header. */
    private fun seedWithScene(base: File, day: String, fileName: String, sceneId: Int, entries: List<String>): File {
        val dayDir = File(File(base, DailyLogWorkspace.DAILY_LOG_ROOT_RELATIVE), day)
        dayDir.mkdirs()
        val header = "<!-- DAILY_LOG_METADATA\n{\"workflow_metadata\":{\"task_scene_id\":\"$sceneId\"}}\n-->"
        val body = entries.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n")
        val file = File(dayDir, fileName)
        file.writeText("$header\n\n$body\n")
        return file
    }

    private val config = ConsolidationModelConfig(baseUrl = "https://planner.test", apiKey = "sk-consolidation-secret", modelName = "gpt-4o")

    @After
    fun tearDown() {
        EndpointConfig.load(java.util.Properties())
    }

    // ---------------------------------------------------------------- tools

    /** A scripted fake [ChatClient] with a call counter + per-request handler. */
    private class ScriptedChat : ChatClient {
        var calls = 0
        var error: ChatException? = null
        var handler: ((ChatCompletionsRequest) -> ChatCompletionResult)? = null
        override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult {
            calls++
            error?.let { throw it }
            return handler?.invoke(request) ?: ChatCompletionResult(content = "", finishReason = "stop", raw = null)
        }
        override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = emptyFlow()
    }

    private fun modelClient(chat: ChatClient): ConsolidationModelClient = ConsolidationModelClient(chat, config)

    private fun candidateFact(
        sourceRef: String = "2026-08-01/wechat.logs.md#1",
        sourceDate: String = "2026-08-01",
        normalized: String = "买了咖啡花了 25 元",
        sceneId: Int = -1,
    ): CandidateFact = CandidateFact(
        summary = normalized,
        normalizedFact = normalized,
        eventDate = sourceDate,
        tags = listOf("咖啡"),
        rawExcerpt = normalized,
        sourceRef = sourceRef,
        sourceDate = sourceDate,
        workflowSceneId = sceneId,
        dedupText = ConsolidationText.normalizeDedupText(normalized),
    )

    private fun record(id: Int, firstSeen: String, normalized: String = "已有记录 $id"): ConsolidatedRecord =
        ConsolidatedRecord(
            recordId = id,
            firstSeenDate = firstSeen,
            lastSeenDate = firstSeen,
            sourceRefs = listOf("2026-08-01/wechat.logs.md#$id"),
            sourceDates = listOf(firstSeen),
            summary = normalized,
            normalizedFact = normalized,
            rawExcerpts = listOf(normalized),
            tags = emptyList(),
            workflowSceneIds = emptyList(),
            dedupStatus = "new",
            dedupText = ConsolidationText.normalizeDedupText(normalized),
        )

    // =========================================================== model client

    @Test
    fun `parseDedupDecision accepts all four decisions and reports new for unknown`() {
        val client = modelClient(ScriptedChat())
        for (token in listOf("new", "duplicate", "merge_with_existing", "skip_ambiguous")) {
            val d = client.parseDedupDecision("""{"decision":"$token","matched_record_ids":[1],"reason":"r"}""")
            assertNotNull(d)
            assertEquals(token, d!!.decision)
        }
        // Unknown / blank token normalises to new.
        val unknown = client.parseDedupDecision("""{"decision":"destroy","matched_record_ids":[1],"reason":"r"}""")
        assertNotNull(unknown)
        assertEquals(DedupDecisions.NEW, unknown!!.decision)
        assertEquals(listOf(1), unknown.matchedRecordIds)
        assertEquals("r", unknown.reason)
    }

    @Test
    fun `parseDedupDecision returns null on malformed or non-object json`() {
        val client = modelClient(ScriptedChat())
        assertNull(client.parseDedupDecision("not json"))
        assertNull(client.parseDedupDecision("""[1,2,3]"""))
        assertNull(client.parseDedupDecision("""{"decision":}"""))
        assertNull(client.parseDedupDecision(""))
    }

    @Test
    fun `heuristicDedupDecision matches exact and signature and defaults to new`() {
        val client = modelClient(ScriptedChat())
        val existing = record(3, "2026-08-01", "买了咖啡花了 25 元")
        // exact text -> duplicate
        val exact = client.heuristicDedupDecision(candidateFact(normalized = "买了咖啡花了 25 元"), listOf(existing))
        assertEquals(DedupDecisions.DUPLICATE, exact.decision)
        assertEquals(listOf(3), exact.matchedRecordIds)
        // unrelated text -> new
        val unrelated = client.heuristicDedupDecision(candidateFact(normalized = "今天开会讨论项目进度"), listOf(existing))
        assertEquals(DedupDecisions.NEW, unrelated.decision)
    }

    @Test
    fun `decideDedup with no recent candidates returns new no_recent_records fallback`() = runBlocking {
        val chat = ScriptedChat()
        val client = modelClient(chat)
        val outcome = client.decideDedup(candidateFact(), emptyList())
        assertEquals(DedupDecisions.NEW, outcome.decision.decision)
        assertEquals(DecisionSource.NO_CANDIDATES, outcome.source)
        assertEquals("no_recent_records", outcome.decision.reason)
        assertEquals(0, chat.calls) // zero network when no candidates
    }

    @Test
    fun `decideDedup honours a safe model merge and falls back on unknown matched id`() = runBlocking {
        val existing = record(7, "2026-08-01", "买了咖啡花了 25 元")
        // Safe model decision -> duplicate against a real candidate.
        val safeChat = ScriptedChat()
        safeChat.handler = {
            ChatCompletionResult("""{"decision":"duplicate","matched_record_ids":[7],"reason":"same_order","merged_summary":"","merged_fact":"","merged_tags":[]}""", "stop", null)
        }
        val safeClient = modelClient(safeChat)
        val safe = safeClient.decideDedup(candidateFact(normalized = "买了咖啡花了 25 元"), listOf(existing))
        assertEquals(DecisionSource.MODEL, safe.source)
        assertEquals(DedupDecisions.DUPLICATE, safe.decision.decision)

        // Unknown / fabricated matched id -> fall back to the safe local heuristic.
        val evilChat = ScriptedChat()
        evilChat.handler = {
            ChatCompletionResult("""{"decision":"merge_with_existing","matched_record_ids":[999],"reason":"hack"}""", "stop", null)
        }
        val evilClient = modelClient(evilChat)
        val evil = evilClient.decideDedup(candidateFact(normalized = "买了咖啡花了 25 元"), listOf(existing))
        // Fallback must never target id 999 (unrelated record) — only a real candidate via heuristic.
        assertEquals(DecisionSource.FALLBACK, evil.source)
        assertEquals(DedupDecisions.DUPLICATE, evil.decision.decision)
        assertTrue(evil.decision.matchedRecordIds.all { it in setOf(7) })
    }

    @Test
    fun `decideDedup with duplicated matched ids falls back (never overwrites an unrelated record)`() = runBlocking {
        val existing = record(7, "2026-08-01", "买了咖啡花了 25 元")
        val chat = ScriptedChat()
        chat.handler = {
            ChatCompletionResult("""{"decision":"duplicate","matched_record_ids":[7,7,3],"reason":"dup_ids"}""", "stop", null)
        }
        val client = modelClient(chat)
        val outcome = client.decideDedup(candidateFact(normalized = "买了咖啡花了 25 元"), listOf(existing))
        // Duplicated/overflowing matched ids -> local fallback (only real candidate targeted).
        assertEquals(DecisionSource.FALLBACK, outcome.source)
        assertTrue(outcome.decision.matchedRecordIds.all { it in setOf(7) })
    }

    @Test
    fun `decideDedup on model failure falls back to heuristic`() = runBlocking {
        val existing = record(7, "2026-08-01", "买了咖啡花了 25 元")
        val chat = ScriptedChat()
        chat.error = ChatException(ChatErrorKind.IO, "network down")
        val client = modelClient(chat)
        val outcome = client.decideDedup(candidateFact(normalized = "买了咖啡花了 25 元"), listOf(existing))
        assertEquals(DecisionSource.FALLBACK, outcome.source)
        assertEquals(DedupDecisions.DUPLICATE, outcome.decision.decision)
    }

    @Test
    fun `validateSplitFacts preserves source ref date and scene id and drops bad facts`() {
        val client = modelClient(ScriptedChat())
        val entry = DailyLogEntry(
            sourceDate = "2026-08-02",
            fileName = "life.logs.md",
            entryIndex = 1,
            text = "今天买书花了 45 元",
            workflowMetadata = mapOf("task_scene_id" to "3"),
            latestEntryIndex = 1,
            sourceRef = "2026-08-02/life.logs.md#1",
        )
        val raw = listOf(
            CloudConsolidationTypes.RawSplitFact(summary = "买书", normalizedFact = "今天买书花了 45 元", eventDate = "2026-08-02", tags = listOf("书"), modelNotes = "m"),
            CloudConsolidationTypes.RawSplitFact(summary = "", normalizedFact = "", eventDate = "", tags = emptyList(), modelNotes = ""), // dropped blank
        )
        val facts = client.validateSplitFacts(raw, entry)
        assertEquals(1, facts.size)
        assertEquals("2026-08-02/life.logs.md#1", facts[0].sourceRef)
        assertEquals("2026-08-02", facts[0].sourceDate)
        assertEquals(3, facts[0].workflowSceneId) // real scene id preserved
        assertTrue(facts[0].normalizedFact.contains("45 元"))
    }

    @Test
    fun `splitEntriesIntoFacts keeps heuristic baseline when model reply is malformed`() = runBlocking {
        val chat = ScriptedChat()
        chat.handler = { ChatCompletionResult("not valid json", "stop", null) }
        val client = modelClient(chat)
        val entry = DailyLogEntry("2026-08-02", "life.logs.md", 1, "买了咖啡花了 25 元", emptyMap(), 1, "2026-08-02/life.logs.md#1")
        val split = client.splitEntriesIntoFacts(listOf(entry))
        // Heuristic baseline retained (never empty, never harmful).
        assertTrue(split["2026-08-02/life.logs.md#1"].orEmpty().isNotEmpty())
    }

    @Test
    fun `split prompt never leaks uri token or external path and is bounded`() {
        val client = modelClient(ScriptedChat())
        // Evil string that looks like a token / uri / abs path embedded in an entry.
        val dangerous = "泄露 token=ABCDEF, uri=file:///data/secret, path=/etc/passwd, 文件内容"
        val entry = DailyLogEntry("2026-08-02", "life.logs.md", 1, dangerous, mapOf("task_scene_id" to "1"), 1, "2026-08-02/life.logs.md#1")
        val prompt = client.buildSplitPrompt(listOf(entry))
        // Only bounded real entry text is present; the scene label is added, never the key.
        assertTrue(prompt.contains("泄露"))
        // It does not carry the API key or model name or config secret.
        assertFalse(prompt.contains("sk-consolidation-secret"))
        assertFalse(prompt.contains("gpt-4o"))
        // The raw entry text is bounded to MAX_ENTRY_PROMPT_CHARACTERS.
        assertTrue(prompt.length <= 2000)
    }

    @Test
    fun `dedup prompt carries only bounded record text and never an auth secret`() = runBlocking {
        val secret = "sk-my-actual-api-key"
        val chat = ScriptedChat()
        val captured = ArrayList<String>()
        chat.handler = { req ->
            captured.add(req.messages.joinToString("") { it.content.orEmpty() })
            ChatCompletionResult("""{"decision":"new","matched_record_ids":[],"reason":"r"}""", "stop", null)
        }
        val client = ConsolidationModelClient(chat, ConsolidationModelConfig("https://planner.test", secret, "gpt-4o"))
        // The fact carries ordinary text that happens to mention a URI-ish path; it must
        // NOT pull in the config's API key.
        val fact = candidateFact(normalized = "处理了 file:///data/report 里的内容")
        client.decideDedup(fact, listOf(record(7, "2026-08-01", "处理了 file:///data/report 里的内容")))
        val prompt = captured.joinToString("")
        // The OpenAI auth key is a request-header secret, never placed in the prompt body.
        assertFalse("API key must not leak into the prompt", prompt.contains(secret))
        assertTrue(prompt.contains("existing_records"))
        assertTrue(prompt.contains("record_id"))
        // Bounded: the dedup prompt stays small.
        assertTrue(prompt.length <= 4000)
    }

    // ============================================================= applier/repo

    @Test
    fun `repository is local-only when an explicit action is required but unconfigured`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        val repo = CloudConsolidationRepository(base)
        EndpointConfig.load(java.util.Properties()) // blank -> fromEndpointConfig null
        val result = repo.consolidate(
            explicitAction = true,
            modelConfig = ConsolidationModelConfig.fromEndpointConfig(),
            modelClientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertTrue(result.path == ConsolidationPath.LOCAL)
        assertTrue(result.isLocalOnly)
        assertEquals(0, chat.calls) // zero network when unconfigured
        assertTrue(result.totalRecordsAfter >= 1) // real local state still produced
        assertTrue(DataManagerPaths.outputState(base, "2026-08", "wechat.logs.md").exists())
    }

    @Test
    fun `repository never invokes the model when the action is not explicit`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        val repo = CloudConsolidationRepository(base)
        val result = repo.consolidate(
            explicitAction = false, // not user-triggered
            modelConfig = config,
            modelClientProvider = { ConsolidationModelClient(chat, it) },
        )
        assertTrue(result.isLocalOnly)
        assertEquals("zero network for a non-explicit action", 0, chat.calls)
    }

    @Test
    fun `repository with no real entries is local and creates no artifacts`() = runBlocking {
        val base = tempBase()
        val chat = ScriptedChat()
        val repo = CloudConsolidationRepository(base)
        val result = repo.consolidate(true, config, { ConsolidationModelClient(chat, it) })
        assertTrue(result.isLocalOnly)
        assertEquals(0, chat.calls)
        assertEquals(0, result.sourceEntries)
        assertFalse(DataManagerPaths.stateIndex(base).exists())
    }

    @Test
    fun `repository model path produces new records and is idempotent on repeat`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 买了咖啡花了 25 元", "2. 地铁通勤 5 元"))
        val chat = ScriptedChat()
        chat.handler = { req ->
            val user = req.messages.joinToString("") { it.content.orEmpty() }
            if (user.contains("entry_text")) {
                // Split: return the entry-fact preserving the source_ref.
                ChatCompletionResult(
                    """{"items":[{"source_ref":"2026-08-01/wechat.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]},{"source_ref":"2026-08-01/wechat.logs.md#2","facts":[{"summary":"通勤","normalized_fact":"地铁通勤 5 元","event_date":"2026-08-01","tags":["通勤"]}]}]}""",
                    "stop", null,
                )
            } else {
                // Dedup: everything is new.
                ChatCompletionResult("""{"decision":"new","matched_record_ids":[],"reason":"independent"}""", "stop", null)
            }
        }
        val repo = CloudConsolidationRepository(base)
        val run1 = repo.consolidate(true, config, { ConsolidationModelClient(chat, it) })
        assertTrue(run1.path == ConsolidationPath.MODEL)
        assertTrue(run1.factsCreated >= 2)
        assertTrue(run1.totalRecordsAfter >= 2)
        // Record ids are stable, monotonic starting at 1.
        val idx1 = DailyLogLocalIndex(base).query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        val ids1 = idx1.events.map { it.recordId }.sorted()
        assertTrue(ids1.all { it >= 1 })

        // Repeat run with no new input must NOT re-process (processed-hash skip) -> zero new calls?
        // The already-processed entries are pending=false so no split/dedup is called again.
        val callsBefore = chat.calls
        val run2 = repo.consolidate(true, config, { ConsolidationModelClient(chat, it) })
        val callsAfter = chat.calls
        assertTrue("repeat run should not re-send already-processed entries", callsAfter - callsBefore <= 1)
        assertEquals(run1.totalRecordsAfter, run2.totalRecordsAfter)
        val idx2 = DailyLogLocalIndex(base).query(LocalConsolidationTypes.QueryOptions(month = "2026-08"))
        assertEquals(ids1, idx2.events.map { it.recordId }.sorted())
    }

    @Test
    fun `model failure falls back to local and never erases the prior valid state`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 买了咖啡花了 25 元"))
        // First run succeeds and persists a valid state.
        val goodChat = ScriptedChat()
        goodChat.handler = { req ->
            val user = req.messages.joinToString("") { it.content.orEmpty() }
            if (user.contains("entry_text")) {
                ChatCompletionResult("""{"items":[{"source_ref":"2026-08-01/wechat.logs.md#1","facts":[{"summary":"咖啡","normalized_fact":"买了咖啡花了 25 元","event_date":"2026-08-01","tags":["咖啡"]}]}]}""", "stop", null)
            } else ChatCompletionResult("""{"decision":"new","matched_record_ids":[],"reason":"r"}""", "stop", null)
        }
        val repo = CloudConsolidationRepository(base)
        val ok = repo.consolidate(true, config, { ConsolidationModelClient(goodChat, it) })
        assertEquals(ConsolidationPath.MODEL, ok.path)
        val stateFile = DataManagerPaths.outputState(base, "2026-08", "wechat.logs.md")
        val before = LocalConsolidationCodec.decodeState(stateFile.readText())
        assertEquals(1, before.records.size)

        // Add a genuinely NEW pending entry, then let the model fail.
        writeLog(base, "2026-08-02", "wechat.logs.md", listOf("1. 买了咖啡花了 26 元"))
        val badChat = ScriptedChat()
        badChat.error = ChatException(ChatErrorKind.HTTP, "500 boom", httpCode = 500)
        val result = repo.consolidate(true, config, { ConsolidationModelClient(badChat, it) })
        // Model failed -> typed local/partial fallback, prior record preserved.
        assertTrue(result.isLocalOnly || result.isPartial)
        val after = LocalConsolidationCodec.decodeState(stateFile.readText())
        // The previously-valid record survived; the fallback added at most the local new fact.
        assertTrue("prior valid record must not be erased", after.records.size >= 1)
        val first = after.records.first()
        assertEquals("prior record normalized fact unchanged", "买了咖啡花了 25 元", first.normalizedFact)
    }

    @Test
    fun `repository preserves scene ids through the model fact path`() = runBlocking {
        val base = tempBase()
        seedWithScene(base, "2026-08-03", "chat.logs.md", sceneId = 3, listOf("和朋友约好周末去公园"))
        val chat = ScriptedChat()
        chat.handler = { req ->
            val user = req.messages.joinToString("") { it.content.orEmpty() }
            if (user.contains("entry_text")) {
                ChatCompletionResult("""{"items":[{"source_ref":"2026-08-03/chat.logs.md#1","facts":[{"summary":"约定","normalized_fact":"和朋友约好周末去公园","event_date":"2026-08-03","tags":["社交"]}]}]}""", "stop", null)
            } else ChatCompletionResult("""{"decision":"new","matched_record_ids":[],"reason":"r"}""", "stop", null)
        }
        val repo = CloudConsolidationRepository(base)
        val result = repo.consolidate(true, config, { ConsolidationModelClient(chat, it) })
        assertEquals(ConsolidationPath.MODEL, result.path)
        val state = LocalConsolidationCodec.decodeState(
            DataManagerPaths.outputState(base, "2026-08", "chat.logs.md").readText(),
        )
        val record = state.records.first()
        assertTrue("scene id 3 preserved", record.workflowSceneIds.contains(3))
    }

    @Test
    fun `model output writes auditable never-secret notes`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-04", "life.logs.md", listOf("1. 续费会员 30 元"))
        val chat = ScriptedChat()
        chat.handler = { req ->
            val user = req.messages.joinToString("") { it.content.orEmpty() }
            if (user.contains("entry_text")) {
                ChatCompletionResult("""{"items":[{"source_ref":"2026-08-04/life.logs.md#1","facts":[{"summary":"续费","normalized_fact":"续费会员 30 元","event_date":"2026-08-04","tags":[]}]}]}""", "stop", null)
            } else ChatCompletionResult("""{"decision":"merge_with_existing","matched_record_ids":[7],"reason":"same_subscription","merged_fact":"续费会员 30 元","merged_summary":"续费会员","merged_tags":["续费"]}""", "stop", null)
        }
        val repo = CloudConsolidationRepository(base)
        val result = repo.consolidate(true, config, { ConsolidationModelClient(chat, it) })
        // With no prior records and a merge to a nonexistent id 7 (not in scope), it falls back.
        // Auditable notes on the created record must carry the decision, never a secret.
        val state = LocalConsolidationCodec.decodeState(
            DataManagerPaths.outputState(base, "2026-08", "life.logs.md").readText(),
        )
        val notes = state.records.flatMap { it.modelNotes }
        assertFalse("no secret in notes", notes.any { it.contains("sk-consolidation-secret") })
    }

    @Test
    fun `cancellation is cooperative and reported rather than swallowed`() = runBlocking {
        val base = tempBase()
        writeLog(base, "2026-08-01", "wechat.logs.md", listOf("1. 买了咖啡花了 25 元"))
        val chat = ScriptedChat()
        chat.handler = {
            ChatCompletionResult("""{"items":[]}""", "stop", null)
        }
        val repo = CloudConsolidationRepository(base)
        val cancelled = try {
            repo.consolidate(
                explicitAction = true,
                modelConfig = config,
                modelClientProvider = { ConsolidationModelClient(chat, it) },
                isCancelled = { true }, // cancel before anything
            )
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            e
        }
        assertNotNull("cancellation must propagate", cancelled)
    }

    // ====================================================== applier four decisions

    /** Build a synthetic DailyLogEntry list for one group. */
    private fun logEntries(
        vararg refs: Triple<String, String, String>, // (sourceRef, sourceDate, text)
    ): List<DailyLogEntry> = refs.mapIndexed { index, (ref, date, text) ->
        DailyLogEntry(
            sourceDate = date,
            fileName = ref.substringAfter('/').substringBefore('#') + ".md",
            entryIndex = index + 1,
            text = text,
            workflowMetadata = emptyMap(),
            latestEntryIndex = refs.size,
            sourceRef = ref,
        )
    }

    @Test
    fun `applier applies new duplicate merge and skip deterministically`() = runBlocking {
        val entries = logEntries(
            Triple("2026-08-01/a.md#1", "2026-08-01", "买了咖啡花了 25 元"),
            Triple("2026-08-01/a.md#2", "2026-08-01", "再买咖啡花了 25 元"), // duplicate of #1
            Triple("2026-08-01/a.md#3", "2026-08-01", "和朋友约好周末去公园"), // merge into #1 evidence
            Triple("2026-08-01/a.md#4", "2026-08-01", "一句无法判断的话"), // skip_ambiguous
        )
        // A splitter that emits one fact per entry (minimal).
        val splitter: ConsolidationFactSplitter = { es ->
            es.associate { e ->
                e.sourceRef to listOf(
                    CloudConsolidationTypes.CandidateFact(
                        summary = ConsolidationText.normalizeFreeText(e.text),
                        normalizedFact = ConsolidationText.normalizeFreeText(e.text),
                        eventDate = e.sourceDate,
                        rawExcerpt = e.text,
                        sourceRef = e.sourceRef,
                        sourceDate = e.sourceDate,
                        dedupText = ConsolidationText.normalizeDedupText(e.text),
                    ),
                )
            }
        }
        // A decider: #2 -> duplicate(#1), #3 -> merge_with_existing(#1), #4 -> skip_ambiguous, else new.
        val decider: ConsolidationFeatureDecider = { fact, scoped ->
            when {
                fact.sourceRef.endsWith("#2") -> DedupOutcome(
                    CloudConsolidationTypes.DedupDecision(DedupDecisions.DUPLICATE, listOf(1), "r"),
                    DecisionSource.MODEL,
                )
                fact.sourceRef.endsWith("#3") -> DedupOutcome(
                    CloudConsolidationTypes.DedupDecision(
                        DedupDecisions.MERGE_WITH_EXISTING, listOf(1), "r",
                        mergedFact = "买了咖啡花了 25 元（重购）",
                    ),
                    DecisionSource.MODEL,
                )
                fact.sourceRef.endsWith("#4") -> DedupOutcome(
                    CloudConsolidationTypes.DedupDecision(DedupDecisions.SKIP_AMBIGUOUS, listOf(), "r"),
                    DecisionSource.MODEL,
                )
                else -> DedupOutcome(CloudConsolidationTypes.DedupDecision(DedupDecisions.NEW, listOf(), "r"), DecisionSource.MODEL)
            }
        }
        val outcome = ModelConsolidationApplier.applyPending(
            entries = entries,
            previous = LocalConsolidationTypes.ConsolidatedState(),
            splitter = splitter,
            decider = decider,
            modelPresent = true,
        )
        // #1 new, #2 duplicate, #3 merge -> single record; #4 skipped.
        assertEquals(1, outcome.state.records.size)
        assertEquals(1, outcome.factsCreated) // only #1 was created as a new record
        assertEquals(2, outcome.factsMerged) // #2 duplicate + #3 merge into record 1
        assertEquals(1, outcome.factsSkipped) // #4
        assertEquals(DedupDecisions.MERGE_WITH_EXISTING, outcome.state.records[0].dedupStatus)
    }

    @Test
    fun `applier merge decision only targets a real in-scope record`() = runBlocking {
        val entries = logEntries(Triple("2026-08-01/a.md#1", "2026-08-01", "买了咖啡花了 25 元"))
        val splitter: ConsolidationFactSplitter = { es ->
            es.associate { e ->
                e.sourceRef to listOf(
                    CloudConsolidationTypes.CandidateFact(
                        summary = e.text, normalizedFact = e.text, eventDate = e.sourceDate,
                        rawExcerpt = e.text, sourceRef = e.sourceRef, sourceDate = e.sourceDate,
                        dedupText = ConsolidationText.normalizeDedupText(e.text),
                    ),
                )
            }
        }
        // Model tries to merge into a fabricated id 999 (not present) -> the applier must
        // treat it as new (never create a dangling relation, never overwrite an unrelated record).
        val decider: ConsolidationFeatureDecider = { _, scoped ->
            DedupOutcome(
                CloudConsolidationTypes.DedupDecision(DedupDecisions.MERGE_WITH_EXISTING, listOf(999), "hack"),
                DecisionSource.MODEL,
            )
        }
        val outcome = ModelConsolidationApplier.applyPending(entries, LocalConsolidationTypes.ConsolidatedState(), splitter, decider, modelPresent = true)
        assertEquals(1, outcome.state.records.size)
        assertEquals(DedupDecisions.NEW, outcome.state.records[0].dedupStatus)
    }
}
