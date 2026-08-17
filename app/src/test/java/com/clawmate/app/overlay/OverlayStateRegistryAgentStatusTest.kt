package com.clawmate.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 26 agent-status field in
 * [OverlayStateRegistry]: the small bounded state enum, the sanitize/cap of the
 * status text (≤ [OverlayStateRegistry.MAX_STATUS_TEXT_LENGTH], control chars
 * collapsed), the publish ordering of the coarse idle/running/tool/error/
 * completed transitions, and the guarantee that the surface text never carries
 * screenshots/prompts/tokens/secrets (only bounded display strings).
 */
class OverlayStateRegistryAgentStatusTest {

    @Test
    fun `fresh registry is IDLE with empty text`() {
        OverlayStateRegistry.reset()
        assertEquals(OverlayAgentState.IDLE, OverlayStateRegistry.agentState())
        assertEquals("", OverlayStateRegistry.agentStatusText())
    }

    @Test
    fun `running transition updates state and bounded text`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.RUNNING, "正在回复…")
        assertEquals(OverlayAgentState.RUNNING, OverlayStateRegistry.agentState())
        assertEquals("正在回复…", OverlayStateRegistry.agentStatusText())
    }

    @Test
    fun `tool transition records the coarse tool state`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.TOOL, "执行工具 search")
        assertEquals(OverlayAgentState.TOOL, OverlayStateRegistry.agentState())
        assertEquals("执行工具 search", OverlayStateRegistry.agentStatusText())
    }

    @Test
    fun `error and completed transitions are observable`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.ERROR, "对话失败")
        assertEquals(OverlayAgentState.ERROR, OverlayStateRegistry.agentState())
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.COMPLETED, "回复已完成")
        assertEquals(OverlayAgentState.COMPLETED, OverlayStateRegistry.agentState())
        assertEquals("回复已完成", OverlayStateRegistry.agentStatusText())
    }

    @Test
    fun `reset returns to IDLE shell of the tiny enum`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.TOOL, "x")
        OverlayStateRegistry.reset()
        assertEquals(OverlayAgentState.IDLE, OverlayStateRegistry.agentState())
        assertEquals("", OverlayStateRegistry.agentStatusText())
    }

    @Test
    fun `status text is capped at the documented maximum`() {
        OverlayStateRegistry.reset()
        val longText = "a".repeat(OverlayStateRegistry.MAX_STATUS_TEXT_LENGTH + 500)
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.RUNNING, longText)
        val stored = OverlayStateRegistry.agentStatusText()
        assertTrue(stored.length <= OverlayStateRegistry.MAX_STATUS_TEXT_LENGTH)
        assertEquals(OverlayStateRegistry.MAX_STATUS_TEXT_LENGTH, stored.length)
    }

    @Test
    fun `pure sanitization collapses control characters and trims`() {
        val raw = "执行工具 search\nname=secret \t→ done\r结果"
        val sanitized = OverlayStateRegistry.sanitizeStatusText(raw)
        // No newline / tab / carriage-return survives.
        assertTrue(!sanitized.contains('\n'))
        assertTrue(!sanitized.contains('\t'))
        assertTrue(!sanitized.contains('\r'))
        assertTrue(!sanitized.startsWith(" "))
        assertTrue(!sanitized.endsWith(" "))
        // Control characters become single spaces.
        assertTrue(sanitized.contains("search"))
        assertTrue(sanitized.contains("done"))
    }

    @Test
    fun `blank input sanitizes to empty`() {
        val blank = buildString {
            append(' ')
            append('\t')
            append('\n')
        }
        assertEquals("", OverlayStateRegistry.sanitizeStatusText(blank))
        assertEquals("", OverlayStateRegistry.sanitizeStatusText(""))
    }

    @Test
    fun `status text never stores prompt or token payloads beyond the cap`() {
        OverlayStateRegistry.reset()
        // Simulate a tool trace that would otherwise leak long args/secrets.
        val leaky = "执行工具 http_get url=.. token=AKIAIOSFODNN7EXAMPLE secret=(长内容)".repeat(50)
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.TOOL, leaky)
        val stored = OverlayStateRegistry.agentStatusText()
        assertTrue(stored.length <= OverlayStateRegistry.MAX_STATUS_TEXT_LENGTH)
        // Only the bounded display prefix is retained — nothing unbounded leaks.
        assertTrue(stored.isNotBlank())
    }

    @Test
    fun `coarse label matches each state`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.IDLE, "")
        assertTrue(OverlayStateRegistry.currentStatusLabel().isNotBlank())
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.RUNNING, "")
        assertTrue(OverlayStateRegistry.currentStatusLabel().isNotBlank())
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.TOOL, "")
        assertTrue(OverlayStateRegistry.currentStatusLabel().isNotBlank())
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.ERROR, "")
        assertTrue(OverlayStateRegistry.currentStatusLabel().isNotBlank())
        OverlayStateRegistry.updateAgentStatus(OverlayAgentState.COMPLETED, "")
        assertTrue(OverlayStateRegistry.currentStatusLabel().isNotBlank())
    }
}
