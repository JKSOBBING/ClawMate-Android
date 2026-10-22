package com.clawmate.app.home.tasks

import com.clawmate.app.datamanager.LocalConsolidationTypes.IndexedEvent
import com.clawmate.app.home.domain.defaultHomeDomains
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeTaskSuggestionsTest {
    private fun context(previous: List<String> = emptyList()) = HomeTaskSuggestions.context(
        listOf(IndexedEvent(eventId = "private-id", sourceStateFile = "private-state", sourceRefs = listOf("private-source"),
            eventDate = "2026-10-23", summary = "我喜欢咖啡，也关心天气和通勤路线", sources = listOf("美团记录"))),
        emptyList(), previous, "2026-10-23")
    private fun item(title: String, task: String, refs: List<String> = listOf("record-1"), category: String = "life") = buildJsonObject {
        put("title", title); put("task", task); put("reason", "根据近期记录建议"); put("category", category)
        put("qwen_supported", true); put("qwen_task", "帮我找咖啡"); put("qwen_reason", "协同信息")
        put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
    }
    private fun candidates(first: JsonObject = item("找咖啡", "搜索无糖咖啡", category = "food")) = listOf(first,
        item("查天气", "查询今日天气", category = "news"), item("查路线", "查看通勤路线", category = "travel"))
    private fun raw(items: List<JsonObject> = candidates(), key: String = "suggestions") = buildJsonObject { put(key, JsonArray(items)) }.toString()

    @Test fun `context is bounded uses local references and excludes private index identifiers`() {
        val events = (1..30).map { IndexedEvent(eventId = "private-id-$it", sourceStateFile = "private-state", sourceRefs = listOf("private-source"),
            eventDate = "2026-10-23", summary = "事实".repeat(200), sources = (1..5).map { "source-$it" }, keywords = (1..20).map { "keyword-$it" }) }
        val domains = defaultHomeDomains().map { it.copy(eventCount = 1, fullDesc = "画像事实".repeat(100), claims = (1..8).map { "claim-$it" }) }
        val prepared = HomeTaskSuggestions.context(events, domains, (1..8).map { "task-$it" }, "2026-10-23")
        val payload = prepared.payload
        assertEquals(24, payload["recent_records"]!!.jsonArray.size)
        assertEquals(4, payload["profile_summary"]!!.jsonArray.size)
        assertEquals(3, payload["avoid_repeating_tasks"]!!.jsonArray.size)
        assertFalse(payload.toString().contains("private-"))
        assertTrue(payload["recent_records"]!!.jsonArray.all { it.jsonObject["summary"]!!.jsonPrimitive.content.length <= 180 })
        assertEquals(28, prepared.evidence.size)
        assertTrue(prepared.evidence.containsKey("profile-1"))
        assertTrue(prepared.evidence.containsKey("record-24"))
        assertFalse(prepared.evidence.containsKey("record-25"))
    }

    @Test fun `three different tasks resolve only real bounded evidence`() {
        val result = HomeTaskSuggestions.parse(raw(), "batch/path", context())
        assertEquals(3, result.size)
        assertEquals(3, result.map { it.id }.toSet().size)
        assertTrue(result.all { it.id.startsWith("guess-batchpath-") && it.evidence.single().summary.contains("咖啡") })
        assertEquals("餐饮", HomeTaskSuggestions.categoryLabel(result.first().category))
        assertTrue(result.first().qwenSupported) // Evidence platform is not the requested task platform.
    }

    @Test fun `missing fabricated duplicate or previously shown tasks cannot produce success`() {
        val bad = listOf("broken", "[]", "{}", """{"action":"error","reason":"untrusted message"}""",
            raw(candidates(item("找咖啡", "搜索无糖咖啡", listOf("record-99")))), raw(candidates().take(2)),
            raw(listOf(candidates()[0], candidates()[0], candidates()[2])))
        bad.forEach { assertTrue(it.take(60), runCatching { HomeTaskSuggestions.parse(it, "batch", context()) }.isFailure) }
        assertTrue(runCatching { HomeTaskSuggestions.parse(raw(), "batch", context(listOf("搜索无糖咖啡"))) }.isFailure)
        assertTrue(runCatching { HomeTaskSuggestions.parse(raw(), "batch", HomeTaskSuggestions.context(emptyList(), emptyList(), emptyList(), "2026-10-23")) }.isFailure)
    }

    @Test fun `invalid candidate does not consume a later valid task or fabricated evidence`() {
        val invalid = item("找咖啡", "搜索无糖咖啡", listOf("fabricated"))
        val result = HomeTaskSuggestions.parse(raw(listOf(invalid) + candidates()), "batch", context())
        assertEquals(3, result.size)
        assertEquals("record-1", result.first().evidence.single().ref)
    }

    @Test fun `legacy tasks alias and wrapped response preserve real suggestions`() {
        val result = HomeTaskSuggestions.parse("<think>not displayed</think>\n```json\n${raw(key = "tasks")}\n```", "batch", context())
        assertEquals(3, result.size)
        assertFalse(result.toString().contains("not displayed"))
    }

    @Test fun `other platform and private device tasks cannot claim Qwen support`() {
        for (task in listOf("在美团查看咖啡", "在微信查看朋友消息", "查看手机相册", "查看银行卡账单")) {
            val result = HomeTaskSuggestions.parse(raw(candidates(item("查看记录", task, category = "food"))), "batch", context())
            assertFalse(result.first().qwenSupported)
            assertEquals("", result.first().qwenTask)
        }
    }

    @Test fun `execution descriptions retain targets with a noncommitting endpoint`() {
        assertEquals("购买通勤背包，不下单", HomeTaskSuggestions.executionDescription("购买通勤背包"))
        assertEquals("给朋友发送问候，不发送", HomeTaskSuggestions.executionDescription("给朋友发送问候"))
        assertEquals("查看支付宝账单", HomeTaskSuggestions.executionDescription("查看支付宝账单"))
        assertEquals("查看淘宝背包，不下单", HomeTaskSuggestions.executionDescription("查看淘宝背包，不下单。"))
    }

    @Test fun `multi action asking and oversized descriptions fail instead of cutting constraints`() {
        for (draft in listOf("", "搜索背包；查询天气", "先问我买什么", "搜索背包然后购买", "x".repeat(61))) {
            assertTrue(draft, runCatching { HomeTaskSuggestions.executionDescription(draft) }.isFailure)
        }
        val original = "在淘宝搜索" + "x".repeat(70) + "100元以内的背包"
        assertEquals("在淘宝搜索100元内背包", HomeTaskSuggestions.shortDraft(original, "搜索100元内背包"))
        assertEquals(1, HomeTaskSuggestions.compact("a😀", 2).length)
    }
}
