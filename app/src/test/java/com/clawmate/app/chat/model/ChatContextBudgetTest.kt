package com.clawmate.app.chat.model

import com.clawmate.app.settings.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the context-compression budget seam (Phase 43).
 *
 * Mirrors the HarmonyOS `ChatContextBudgetUnit.test.ets` expectations for the
 * pure, deterministic pieces: token estimation, the compress boundary, the
 * recent-turns split (skipping UI-only trace/notice messages) and the local-only
 * extractive summary. Also verifies the budget is built from Settings thresholds.
 */
class ChatContextBudgetTest {

    private fun repeated(text: String, count: Int): String = text.repeat(count)

    private fun chat(
        id: String,
        role: ChatMessageRole,
        kind: ChatMessageKind,
        content: String,
        createdAt: Long,
    ) = ChatMessage(
        id = id,
        role = role,
        kind = kind,
        content = content,
        trace = emptyList(),
        collapsed = false,
        createdAt = createdAt,
    )

    // ------------------------------------------------------------------
    // Token approximation
    // ------------------------------------------------------------------

    @Test
    fun `estimates tokens as utf16 length matching Harmony proxy`() {
        val budget = ChatContextBudget.defaults()
        assertEquals(0, budget.estimateTextTokens(""))
        assertEquals(7, budget.estimateTextTokens("你好abc😀"))
        // Non-BMP surrogate pair (4 bytes) counts as 2 UTF-16 units.
        val emoji = "😀" // U+1F600, surrogate pair
        assertEquals(2, budget.estimateTextTokens(emoji))
    }

    @Test
    fun `wire message estimation includes structure overhead and role`() {
        val budget = ChatContextBudget.defaults()
        val wire = com.clawmate.app.chat.cloud.ChatMessageWire("user", "abc")
        // 16 overhead + role("user"=4) + content("abc"=3) = 23
        assertEquals(23, budget.estimateWireMessageTokens(wire))
    }

    // ------------------------------------------------------------------
    // Compression boundary
    // ------------------------------------------------------------------

    @Test
    fun `compress fires when input reaches usable threshold after reserved output`() {
        // max=1000, threshold=85% -> thresholdTokens=850; usable=850-100=750.
        val budget = ChatContextBudget(
            thresholdPercent = 85,
            keepRecentTurns = 4,
            maxContextTokens = 1000,
            reservedOutputTokens = 100,
        )
        // One user message long enough to cross 750 tokens (>= usable presses compress).
        val message = chat("u1", ChatMessageRole.USER, ChatMessageKind.CHAT, repeated("问", 760), 1L)
        val system = com.clawmate.app.chat.cloud.ChatMessageWire("system", "规则")
        val wireList = listOf(system, com.clawmate.app.chat.cloud.ChatMessageWire("user", message.content))

        val decision = budget.decide(wireList)
        assertEquals(850, decision.thresholdTokens)
        assertEquals(750, decision.usableInputTokens)
        assertTrue(decision.shouldCompress)
    }

    @Test
    fun `does not compress when input is below usable threshold`() {
        val budget = ChatContextBudget(
            thresholdPercent = 85,
            keepRecentTurns = 4,
            maxContextTokens = 1000,
            reservedOutputTokens = 100,
        )
        val wireList = listOf(
            com.clawmate.app.chat.cloud.ChatMessageWire("system", "规则"),
            com.clawmate.app.chat.cloud.ChatMessageWire("user", "你好"),
        )
        val decision = budget.decide(wireList)
        assertFalse(decision.shouldCompress)
    }

    @Test
    fun `compress fires exactly at the boundary`() {
        // Build a message set whose estimated tokens == usable (750) exactly.
        val budget = ChatContextBudget(
            thresholdPercent = 85,
            keepRecentTurns = 4,
            maxContextTokens = 1000,
            reservedOutputTokens = 100,
        )
        // system: overhead 16 + role "system"(6) + content "sys"(3) = 25.
        // user wire: overhead 16 + role "user"(4) + content length C.
        // We need total = 750 -> 25 + 20 + C = 750 -> C = 705.
        val system = com.clawmate.app.chat.cloud.ChatMessageWire("system", "sys")
        val user = com.clawmate.app.chat.cloud.ChatMessageWire("user", repeated("a", 705))
        val decision = budget.decide(listOf(system, user))
        assertEquals(750, decision.estimatedInputTokens)
        assertEquals(750, decision.usableInputTokens)
        assertTrue(decision.shouldCompress)
    }

    // ------------------------------------------------------------------
    // Split by recent turns
    // ------------------------------------------------------------------

    @Test
    fun `splits history by recent user turns and skips trace and context notices`() {
        val budget = ChatContextBudget(thresholdPercent = 85, keepRecentTurns = 2)
        val messages = listOf(
            chat("u1", ChatMessageRole.USER, ChatMessageKind.CHAT, "第一轮问题", 1000L),
            chat("a1", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "第一轮回答", 1001L),
            chat("trace1", ChatMessageRole.TASK, ChatMessageKind.TASK_TRACE, "工具轨迹", 1002L),
            chat("notice1", ChatMessageRole.SYSTEM, ChatMessageKind.CONTEXT_NOTICE, "上下文提示", 1003L),
            chat("u2", ChatMessageRole.USER, ChatMessageKind.CHAT, "第二轮问题", 1004L),
            chat("r2", ChatMessageRole.ASSISTANT, ChatMessageKind.TASK_RESULT, "工具结果", 1005L),
            chat("u3", ChatMessageRole.USER, ChatMessageKind.CHAT, "第三轮问题", 1006L),
            chat("a3", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "第三轮回答", 1007L),
        )

        val split = budget.splitHistoryByRecentTurns(messages)

        assertEquals(listOf("u1", "a1"), split.olderMessages.map { it.id })
        assertEquals(listOf("u2", "r2", "u3", "a3"), split.retainedMessages.map { it.id })
        assertEquals(2, split.keptUserTurnCount)
        assertEquals(2, split.skippedMessageCount)
    }

    // ------------------------------------------------------------------
    // Extractive summary
    // ------------------------------------------------------------------

    @Test
    fun `extractive summary drops trace and context notices and stays under budget`() {
        val budget = ChatContextBudget.defaults()
        val messages = listOf(
            chat("u1", ChatMessageRole.USER, ChatMessageKind.CHAT, "请总结今天的截图。", 1000L),
            chat("trace1", ChatMessageRole.TASK, ChatMessageKind.TASK_TRACE, "工具轨迹不进摘要", 1001L),
            chat("notice1", ChatMessageRole.SYSTEM, ChatMessageKind.CONTEXT_NOTICE, "提示不进摘要", 1002L),
            chat("a1", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, repeated("回答", 80), 1003L),
        )

        val summary = budget.buildExtractiveSummary(messages, maxChars = 120)

        assertTrue(summary.contains("用户: 请总结今天的截图。"))
        assertTrue(!summary.contains("工具轨迹不进摘要"))
        assertTrue(!summary.contains("提示不进摘要"))
        assertTrue(summary.length <= 120)
    }

    @Test
    fun `extractive summary reports honestly when there is nothing to extract`() {
        val budget = ChatContextBudget.defaults()
        val summary = budget.buildExtractiveSummary(emptyList())
        assertTrue(summary.contains("无可抽取的早期对话内容"))
    }

    // ------------------------------------------------------------------
    // buildWireContext compression end-to-end
    // ------------------------------------------------------------------

    @Test
    fun `buildWireContext compresses long history into summary plus recent window`() {
        // Force compression with a tiny budget.
        val budget = ChatContextBudget(
            thresholdPercent = 85,
            keepRecentTurns = 2,
            maxContextTokens = 200,
            reservedOutputTokens = 0,
        )
        val history = listOf(
            chat("u1", ChatMessageRole.USER, ChatMessageKind.CHAT, repeated("old1-", 100), 1000L),
            chat("a1", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, repeated("old1a-", 100), 1001L),
            chat("u2", ChatMessageRole.USER, ChatMessageKind.CHAT, repeated("old2-", 100), 1002L),
            chat("a2", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, repeated("old2a-", 100), 1003L),
            chat("u3", ChatMessageRole.USER, ChatMessageKind.CHAT, "recent1 q", 1004L),
            chat("a3", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "recent1 a", 1005L),
            chat("u4", ChatMessageRole.USER, ChatMessageKind.CHAT, "recent2 q", 1006L),
            chat("a4", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "recent2 a", 1007L),
        )

        val result = budget.buildWireContext("You are ClawMate.", history)

        assertTrue(result.compressed)
        // system summarizes the older turns (u1..a2); recent window (u3..a4) survives.
        assertTrue(result.messages.first().role == "system")
        assertTrue(result.messages.first().content!!.contains("较早对话"))
        val remaining = result.messages.drop(1)
        assertEquals(listOf("recent1 q", "recent1 a", "recent2 q", "recent2 a"), remaining.map { it.content })
        assertTrue(result.summary.isNotEmpty())
    }

    @Test
    fun `buildWireContext leaves short history uncompressed`() {
        val budget = ChatContextBudget.defaults()
        val history = listOf(
            chat("u1", ChatMessageRole.USER, ChatMessageKind.CHAT, "short", 1000L),
            chat("a1", ChatMessageRole.ASSISTANT, ChatMessageKind.CHAT, "short", 1001L),
        )
        val result = budget.buildWireContext("You are ClawMate.", history)
        assertFalse(result.compressed)
        assertEquals(3, result.messages.size) // system + 2 history messages
    }

    // ------------------------------------------------------------------
    // Settings driven
    // ------------------------------------------------------------------

    @Test
    fun `fromSettings uses configured threshold and recent rounds within clamped bounds`() {
        val settings = AppSettings(
            chatCompressionThresholdPercent = 60,
            chatRecentRounds = 12,
        )
        val budget = ChatContextBudget.fromSettings(settings)
        assertEquals(60, budget.thresholdPercent)
        assertEquals(12, budget.keepRecentTurns)

        // Out-of-range values clamp to the Settings-normalizer bounds (50..95, 2..20).
        val extreme = AppSettings(
            chatCompressionThresholdPercent = 999,
            chatRecentRounds = 0,
        )
        val clamped = ChatContextBudget.fromSettings(extreme)
        assertEquals(95, clamped.thresholdPercent)
        assertEquals(2, clamped.keepRecentTurns)
    }
}
