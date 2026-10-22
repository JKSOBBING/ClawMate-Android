package com.clawmate.app.home.comfort

import com.clawmate.app.chat.model.*
import com.clawmate.app.datamanager.ConsolidationModelConfig
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeComfortTopicsTest {
    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    private val dispatcher = StandardTestDispatcher()
    private val config = ConsolidationModelConfig("https://example.invalid", "fixture-key", "fixture-model")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun message(id: String, role: ChatMessageRole = ChatMessageRole.USER, time: Long = now,
        kind: ChatMessageKind = ChatMessageKind.CHAT, streaming: Int = 0) =
        ChatMessage(id, role, kind, "设备测试：最近工作很累", listOf("private trace"), false, time, streaming)
    private fun session(messages: List<ChatMessage>, id: String = "private-session") =
        ChatSession(id, "private title", ChatMode.CLOUD_AGENT, now, now, messages)
    private fun context() = HomeComfortTopics.context(listOf(session(listOf(message("private-id")))), now, ZoneId.of("UTC"))
    private fun item(title: String = "工作辛苦了", ref: String = "record-1") = buildJsonObject {
        put("title", title); put("summary", "你明确说最近工作很累"); put("opening", "愿意聊聊最近哪件事最累吗？")
        put("category", "work"); putJsonArray("evidence_refs") { add(ref) }
    }
    private fun raw(items: List<JsonObject>, key: String = "concerns") = buildJsonObject { put(key, JsonArray(items)) }.toString()
    private fun topic(batch: String, name: String = "a") = HomeComfortTopic("$batch-$name", batch, "话题$name", "测试概述", "模型生成开场$name",
        "work", listOf(HomeComfortEvidence("record-1", "2026-10-23", "依据$name")))

    @Test fun `only completed recent first party user chat becomes topic context`() {
        val input = listOf(message("private-id"), message("assistant", ChatMessageRole.ASSISTANT),
            message("tool", ChatMessageRole.TASK, kind = ChatMessageKind.TASK_RESULT), message("partial", streaming = 1),
            message("old", time = now - HomeComfortTopics.WINDOW_MILLIS - 1), message("future", time = now + 1))
        val context = HomeComfortTopics.context(listOf(session(input)), now, ZoneId.of("UTC"))
        assertEquals(1, context.evidence.size)
        assertFalse(context.payload.toString().contains("private"))
        assertEquals("设备测试：最近工作很累", context.evidence.values.single().content)
    }

    @Test fun `context caps forty eight records and preserves local message dates`() {
        val input = (1..60).map { message("id-$it", time = now - it * 1000L).copy(content = "字".repeat(500)) }
        val local = HomeComfortTopics.context(listOf(session(input)), now, ZoneId.of("Asia/Shanghai"))
        assertEquals(48, local.evidence.size)
        assertTrue(local.evidence.values.all { it.content.length == 180 && it.date == "2026-10-23" })
        val utc = HomeComfortTopics.context(listOf(session(input)), now, ZoneId.of("UTC"))
        assertTrue(utc.evidence.values.all { it.date == "2026-10-22" })
    }

    @Test fun `valid empty and partial topic batches are accepted without filling invented concerns`() {
        assertTrue(HomeComfortTopics.parse(raw(emptyList()), "batch", context()).isEmpty())
        val result = HomeComfortTopics.parse(raw(listOf(item()), "worries"), "batch", context())
        assertEquals(1, result.size)
        assertEquals(context().evidence.values.single(), result.single().evidence.single())
        assertEquals("batch", result.single().batchId)
    }

    @Test fun `fabricated evidence malformed output and diagnostic claims do not become topics`() {
        val bad = listOf("broken", "{}", "[]", raw(listOf(item(ref = "record-999"))), raw(listOf(item(title = "你患有抑郁症"))))
        bad.forEach { assertTrue(runCatching { HomeComfortTopics.parse(it, "batch", context()) }.isFailure) }
        val result = HomeComfortTopics.parse(raw(listOf(item(title = "你患有抑郁症"), item())), "batch", context())
        assertEquals(1, result.size)
        assertEquals("工作辛苦了", result.single().title)
    }

    @Test fun `duplicate topics and excess candidates cannot inflate the displayed batch`() {
        val result = HomeComfortTopics.parse(raw(listOf(item(), item(), item("换个角度聊聊"))), "batch", context())
        assertEquals(2, result.size)
        assertTrue(runCatching { HomeComfortTopics.parse(raw((1..17).map { item("$it") }), "batch", context()) }.isFailure)
    }

    @Test fun `topic background includes only selected evidence and no local topic identifiers`() {
        val selected = topic("private-batch").copy(id = "private-topic-id")
        val wire = HomeComfortPolicy.messages(listOf(HomeComfortMessage("private-message-id", HomeComfortRole.USER, "我想从这里聊")),
            "2026-10-23", selected)
        assertTrue(wire.first().content!!.contains("不是事实"))
        assertTrue(wire[1].content!!.contains("依据a"))
        assertFalse(wire.toString().contains("private-"))
        assertEquals("我想从这里聊", wire.last().content)
    }

    @Test fun `missing config and empty context send zero model requests`() = runTest {
        val missing = HomeComfortTopicsViewModel({ null }, { error("no chat read") }, { _, _ -> error("no network") })
        missing.refresh()
        assertTrue(missing.state.value.error!!.contains("填写并保存"))
        val empty = HomeComfortTopicsViewModel({ config }, { HomeComfortTopics.context(emptyList(), now) }, { _, _ -> error("no network") })
        empty.refresh()
        advanceUntilIdle()
        assertTrue(empty.state.value.generated)
        assertTrue(empty.state.value.topics.isEmpty())
    }

    @Test fun `failed refresh retains a prior grounded topic batch`() = runTest {
        var calls = 0
        val vm = HomeComfortTopicsViewModel({ config }, { context() }, { _, _ ->
            calls++; if (calls == 1) listOf(topic("batch")) else error("private provider error")
        })
        vm.refresh(); advanceUntilIdle()
        val original = vm.state.value.topics
        vm.refresh(); advanceUntilIdle()
        assertEquals(original, vm.state.value.topics)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("private provider"))
    }

    @Test fun `casual and topic conversations keep their own messages and drafts`() = runTest {
        val selected = mutableListOf<String?>()
        val vm = HomeComfortViewModel({ config }, { _, _, current -> selected += current?.id; HomeComfortReply("实际回复", false) })
        vm.open(); vm.input("随聊草稿"); vm.close()
        val a = topic("batch", "a"); val b = topic("batch", "b")
        vm.open(a); vm.input("话题a的消息"); vm.send(); advanceUntilIdle()
        vm.input("话题a草稿"); vm.close()
        vm.open(b)
        assertEquals(listOf("模型生成开场b"), vm.state.value.messages.map { it.content })
        assertEquals("", vm.state.value.input)
        vm.open(a)
        assertEquals("话题a草稿", vm.state.value.input)
        assertEquals(3, vm.state.value.messages.size)
        assertEquals(listOf(a.id), selected)
        vm.open()
        assertEquals("随聊草稿", vm.state.value.input)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test fun `a new topic batch discards old topic sessions while retaining casual drafts`() {
        val vm = HomeComfortViewModel({ config }, { _, _, _ -> error("no request") })
        val a = topic("batch-a"); val b = topic("batch-b")
        vm.open(); vm.input("随聊草稿"); vm.open(a); vm.input("旧话题草稿")
        vm.open(b); vm.open(a)
        assertEquals("", vm.state.value.input)
        assertEquals(1, vm.state.value.messages.size)
        vm.open()
        assertEquals("随聊草稿", vm.state.value.input)
    }
}
