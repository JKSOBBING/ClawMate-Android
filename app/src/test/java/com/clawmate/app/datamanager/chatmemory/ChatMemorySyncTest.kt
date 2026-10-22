package com.clawmate.app.datamanager.chatmemory

import com.clawmate.app.chat.model.*
import com.clawmate.app.datamanager.DailyLogParser
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMemorySyncTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private fun session(vararg texts: String, mode: String = ChatMode.CLOUD_AGENT, time: Long = now) =
        ChatSession("session", "fixture", mode, time, time, texts.mapIndexed { i, text ->
            ChatMessage("message-$i", ChatMessageRole.USER, ChatMessageKind.CHAT, text, emptyList(), false, time)
        })
    private fun stateFile(base: File) = File(base, "workflows/chat-memory/state.json")
    private fun logFile(base: File, mode: String = "cloud", scene: Int = 12) =
        File(base, "workflows/daily-log/2026-10-23/app-chat__${mode}__$scene.md")
    private fun facts(batch: List<ChatMemoryInput>) = batch.map { ChatMemoryResult(it.sourceRef,
        if (it.userText == "hello") emptyList() else listOf(ChatMemoryFact(12, it.userText))) }
    private fun sync(base: File, source: List<ChatSession>, storage: ChatMemoryStorage = FileChatMemoryStorage(base),
        extract: suspend (List<ChatMemoryInput>) -> List<ChatMemoryResult> = { facts(it) }) =
        ChatMemorySync(base, ChatMemorySessionSource { source }, ChatMemoryExtractor(extract), storage, utc)

    @Test fun `successful and empty results stay idempotent across process recreation`() = runBlocking {
        val base = temp.newFolder()
        var calls = 0
        val source = listOf(session("本人喜欢咖啡", "hello"))
        val first = sync(base, source) { calls++; facts(it) }.sync(true, now)
        assertEquals(ChatMemorySyncSummary(2, 1, 1, 1), first)
        assertEquals(2, FileChatMemoryStorage(base).readState().receipts.size)
        val original = logFile(base).readText()
        val second = sync(base, source) { error("processed messages must not be sent again") }.sync(true, now)
        assertEquals(ChatMemorySyncSummary(0, 0, 1, 1), second)
        assertEquals(1, calls)
        assertEquals(original, logFile(base).readText())
    }

    @Test fun `committed facts recover failed exports after input ages out`() = runBlocking {
        val base = temp.newFolder()
        val delegate = FileChatMemoryStorage(base)
        var exports = 0
        val failAfterCommit = object : ChatMemoryStorage by delegate {
            override fun exportLogs(state: ChatMemoryState): Int {
                exports++
                if (exports == 2) throw IOException("disk unavailable")
                return delegate.exportLogs(state)
            }
        }
        assertTrue(runCatching { sync(base, listOf(session("保留已提交事实")), failAfterCommit).sync(true, now) }.isFailure)
        assertEquals(1, delegate.readState().receipts.size)
        assertFalse(logFile(base).exists())
        val recovered = sync(base, emptyList()) { error("recovery needs no model") }
            .sync(true, now + 8 * 24 * 60 * 60 * 1000L)
        assertEquals(ChatMemorySyncSummary(0, 0, 1, 1), recovered)
        assertTrue(logFile(base).readText().contains("1. 保留已提交事实"))
    }

    @Test fun `failed checkpoint does not acknowledge messages or overwrite previous state`() = runBlocking {
        val base = temp.newFolder()
        val delegate = FileChatMemoryStorage(base)
        delegate.checkpoint(ChatMemoryState.empty())
        val original = stateFile(base).readBytes()
        val fail = object : ChatMemoryStorage by delegate {
            override fun checkpoint(state: ChatMemoryState) { throw IOException("write failed") }
        }
        assertTrue(runCatching { sync(base, listOf(session("真实消息")), fail).sync(true, now) }.isFailure)
        assertArrayEquals(original, stateFile(base).readBytes())
        assertFalse(logFile(base).exists())
        assertEquals(1, sync(base, listOf(session("真实消息"))).sync(true, now).processedParts)
    }

    @Test fun `corrupt or invalid checkpoint fails before reading chats or calling model`() = runBlocking {
        val base = temp.newFolder()
        val file = stateFile(base)
        file.parentFile.mkdirs()
        for (raw in listOf("broken", "{}", """{"version":2,"receipts":[]}""",
            """{"version":1,"receipts":[{"source_ref":"ref","source_date":"../../outside","mode":"cloud_agent","facts":[]}]}""")) {
            file.writeText(raw)
            val runner = ChatMemorySync(base, ChatMemorySessionSource { error("must not load chats") },
                ChatMemoryExtractor { error("must not call model") })
            assertTrue(runCatching { runner.sync(true, now) }.exceptionOrNull() is IOException)
            assertEquals(raw, file.readText())
        }
    }

    @Test fun `invalid multi-part result splits into individuals while network failure does not`() = runBlocking {
        val base = temp.newFolder()
        val sizes = mutableListOf<Int>()
        val result = sync(base, listOf(session("first fact", "second fact"))) { batch ->
            sizes += batch.size
            if (batch.size > 1) throw ChatMemoryResponseException("invalid structured output")
            facts(batch)
        }.sync(true, now)
        assertEquals(listOf(2, 1, 1), sizes)
        assertEquals(2, result.processedParts)
        val networkBase = temp.newFolder()
        var networkCalls = 0
        val failure = runCatching { sync(networkBase, listOf(session("one", "two"))) {
            networkCalls++; throw IOException("offline")
        }.sync(true, now) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, networkCalls)
        assertFalse(stateFile(networkBase).exists())
    }

    @Test fun `missing fabricated and duplicate model results never commit receipts`() = runBlocking {
        for (bad in listOf(emptyList(), listOf(ChatMemoryResult("fabricated", emptyList())),
            listOf(ChatMemoryResult("same", emptyList()), ChatMemoryResult("same", emptyList())))) {
            val base = temp.newFolder()
            val failure = runCatching { sync(base, listOf(session("one"))) { bad }.sync(true, now) }.exceptionOrNull()
            assertTrue(failure is ChatMemoryResponseException)
            assertFalse(stateFile(base).exists())
        }
    }

    @Test fun `prior successful batch remains committed if a later batch fails`() = runBlocking {
        val base = temp.newFolder()
        val source = listOf(session(*(0..8).map { "fact $it" }.toTypedArray()))
        var calls = 0
        assertTrue(runCatching { sync(base, source) { batch ->
            calls++
            if (calls == 2) throw IOException("connection lost")
            facts(batch)
        }.sync(true, now) }.isFailure)
        assertEquals(8, FileChatMemoryStorage(base).readState().receipts.size)
        var retriedParts = 0
        val result = sync(base, source) { retriedParts += it.size; facts(it) }.sync(true, now)
        assertEquals(1, retriedParts)
        assertEquals(9, result.totalFacts)
        assertTrue(logFile(base).readText().contains("9. fact 8"))
    }

    @Test fun `exports group by date mode and scene preserving prior entry numbering`() = runBlocking {
        val base = temp.newFolder()
        val store = FileChatMemoryStorage(base)
        val first = ChatMemoryReceipt("first", "2026-10-23", ChatMode.CLOUD_AGENT, listOf(ChatMemoryFact(12, "已有\n资料")))
        val second = ChatMemoryReceipt("older-added-later", "2026-10-23", ChatMode.CLOUD_AGENT, listOf(ChatMemoryFact(12, "稍后导入")))
        val local = ChatMemoryReceipt("local", "2026-10-23", ChatMode.LOCAL_INFERENCE, listOf(ChatMemoryFact(1, "餐饮信息")))
        val state = ChatMemoryState(1, listOf(first, second, local))
        store.checkpoint(state)
        assertEquals(state, store.readState())
        assertEquals(2, store.exportLogs(state))
        val cloudLog = logFile(base).readText()
        assertTrue(cloudLog.contains("1. 已有 资料\n\n2. 稍后导入"))
        assertTrue(cloudLog.contains("\"task_scene_id\":12"))
        assertTrue(cloudLog.contains("\"source_kind\":\"app_chat\""))
        assertTrue(cloudLog.contains("\"latest_entry_index\":2"))
        assertTrue(cloudLog.contains("# 聊天足迹 / 个人信息"))
        assertTrue(logFile(base, "local", 1).readText().contains("餐饮信息"))
        assertFalse(cloudLog.contains("source_ref"))
    }

    @Test fun `no explicit confirmation means no storage chat or model access`() = runBlocking {
        val base = temp.newFolder()
        val forbidden = object : ChatMemoryStorage {
            override fun readState(): ChatMemoryState = error("no reads")
            override fun checkpoint(state: ChatMemoryState) = error("no writes")
            override fun exportLogs(state: ChatMemoryState): Int = error("no exports")
        }
        val result = runCatching { sync(base, emptyList(), forbidden) { error("no model") }.sync(false, now) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(stateFile(base).exists())
    }

    @Test fun `more than sixty facts partition into parser compatible files without renumbering`() {
        val receipts = (1..121).map { n -> ChatMemoryReceipt("ref-$n", "2026-10-23", ChatMode.CLOUD_AGENT,
            listOf(ChatMemoryFact(12, "事实 $n " + "x".repeat(1900)))) }
        val previous = FileChatMemoryStorage.exports(ChatMemoryState(1, receipts.take(120)))
        val exports = FileChatMemoryStorage.exports(ChatMemoryState(1, receipts))
        assertEquals(previous, exports.take(2))
        assertEquals(listOf("2026-10-23/app-chat__cloud__12.md", "2026-10-23/app-chat__cloud__12__2.md",
            "2026-10-23/app-chat__cloud__12__3.md"), exports.map { it.relativePath })
        val parsed = exports.map { DailyLogParser.parse("2026-10-23", it.relativePath.substringAfter('/'), it.content) }
        assertTrue(parsed.all { it.errors.isEmpty() })
        assertEquals(listOf(60, 60, 1), parsed.map { it.entries.size })
        assertTrue(parsed.last().entries.single().text.startsWith("事实 121"))
    }

    @Test fun `cancellation after model reply cannot checkpoint its result`() = runBlocking {
        val base = temp.newFolder()
        val replied = CompletableDeferred<Unit>()
        val job = launch {
            sync(base, listOf(session("取消中的消息"))) { batch ->
                currentCoroutineContext().cancel()
                replied.complete(Unit)
                facts(batch)
            }.sync(true, now)
        }
        replied.await()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(stateFile(base).exists())
    }

    @Test fun `two synchronizers for same directory cannot extract the same message twice`() = runBlocking {
        val base = temp.newFolder()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            sync(base, listOf(session("唯一事实"))) { batch ->
                calls++; entered.complete(Unit); release.await(); facts(batch)
            }.sync(true, now)
        }
        entered.await()
        val second = async { sync(base, listOf(session("唯一事实"))) { calls++; facts(it) }.sync(true, now) }
        release.complete(Unit)
        assertEquals(1, first.await().processedParts)
        assertEquals(0, second.await().processedParts)
        assertEquals(1, calls)
        assertEquals(1, FileChatMemoryStorage(base).readState().receipts.size)
    }
}
