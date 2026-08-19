package com.clawmate.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 47 [GuiSafeActionBridge] that maps the
 * agent's GUI tool boundary to the existing safe semantic executor:
 *  - only the seven SAFE_ACTION_ALLOWLIST actions are reachable (no tap/swipe/
 *    type/open/capture, no run_cmd);
 *  - every gate rejects correctly: no opt-in (NOT_CONFIGURED), no service
 *    (BLOCKED_SERVICE), foreground-package mismatch (BLOCKED_PACKAGE);
 *  - allowlisted actions succeed / fail through the injected seam (no fake
 *    success);
 *  - the result is always bounded and de-identified — never the clicked node's
 *    text/description, screen content, URI or secrets.
 *
 * All pure — [SafeAccessibilityActionExecutor] is injected; no Android API.
 */
class GuiSafeActionBridgeTest {

    private val allowlist = setOf("com.clawmate.app")

    private val performingExecutor = SafeAccessibilityActionExecutor { action ->
        SafeActionPerformResult.Performed(action.performedDescription)
    }

    private var defaultForeground: String? = "com.clawmate.app"

    private fun dispatch(
        toolName: String,
        params: Map<String, Any?> = emptyMap(),
        optedIn: Boolean = true,
        serviceEnabled: Boolean = true,
        foreground: String? = defaultForeground,
        executor: SafeAccessibilityActionExecutor? = performingExecutor,
        packageAllowlist: Set<String> = allowlist,
    ): SafeActionToolResult = GuiSafeActionBridge.dispatch(
        toolName = toolName,
        params = params,
        optedIn = optedIn,
        serviceEnabled = serviceEnabled,
        foregroundPackage = foreground,
        allowlist = packageAllowlist,
        executor = executor,
    )

    // --------------------------------------------------------------- allowlist

    @Test
    fun `bridge exposes exactly the seven safe semantic tool names`() {
        assertEquals(
            setOf(
                "gui_click_text", "gui_click_content_description", "gui_back",
                "gui_scroll_forward", "gui_scroll_backward", "gui_global_back", "gui_global_home",
            ),
            GuiSafeActionBridge.toolNames(),
        )
    }

    @Test
    fun `coordinate gesture and extraction tool names resolve to null (never reachable)`() {
        listOf("gui_screen_tap", "gui_type_text", "gui_swipe", "capture_screen", "open_app", "run_cmd", "tap")
            .forEach { name ->
                assertEquals("'$name' must not resolve to a safe action", null, GuiSafeActionBridge.actionIdForToolName(name))
                val result = dispatch(toolName = name, optedIn = true, serviceEnabled = true)
                assertEquals("UNSUPPORTED", result.status)
                assertFalse(result.ok)
            }
    }

    // ------------------------------------------------------------ gate checks

    @Test
    fun `no explicit opt-in rejects as NOT_CONFIGURED even when everything else is ready`() {
        val result = dispatch(toolName = "gui_global_home", optedIn = false)
        assertEquals("NOT_CONFIGURED", result.status)
        assertFalse(result.ok)
    }

    @Test
    fun `opt-in without a service or executor rejects as BLOCKED_SERVICE`() {
        val result = dispatch(toolName = "gui_back", serviceEnabled = false, executor = null)
        assertEquals("BLOCKED_SERVICE", result.status)
        assertFalse(result.ok)
    }

    @Test
    fun `foreground package mismatch rejects as BLOCKED_PACKAGE`() {
        val result = dispatch(toolName = "gui_scroll_forward", foreground = "com.other.app")
        assertEquals("BLOCKED_PACKAGE", result.status)
        assertFalse(result.ok)
        assertTrue(result.message.contains("com.other.app") || result.message.contains("白名单"))
    }

    @Test
    fun `null foreground package rejects as BLOCKED_PACKAGE`() {
        val result = dispatch(toolName = "gui_back", foreground = null)
        assertEquals("BLOCKED_PACKAGE", result.status)
        assertFalse(result.ok)
    }

    @Test
    fun `service enabled but no executor rejects as BLOCKED_SERVICE`() {
        val result = GuiSafeActionBridge.dispatch(
            toolName = "gui_back", params = emptyMap(),
            optedIn = true, serviceEnabled = true, foregroundPackage = "com.clawmate.app",
            allowlist = allowlist, executor = null,
        )
        assertEquals("BLOCKED_SERVICE", result.status)
        assertFalse(result.ok)
    }

    // ------------------------------------------------------------ success edge

    @Test
    fun `ready gate with a performing seam returns a bounded SUCCESS with a de-identified label`() {
        val result = dispatch(toolName = "gui_back", params = emptyMap())
        assertTrue(result.ok)
        assertEquals("SUCCESS", result.status)
        // De-identified: a click must NOT echo the actual on-screen text.
        val click = dispatch(toolName = "gui_click_text", params = mapOf("text" to "确认按钮"))
        assertTrue(click.ok)
        assertEquals("SUCCESS", click.status)
        assertFalse("screen text must never reach the model", click.message.contains("确认按钮"))
        assertFalse(click.message.contains("确认按钮"))
    }

    @Test
    fun `oversized click params are rejected as UNSUPPORTED`() {
        val result = dispatch(
            toolName = "gui_click_text",
            params = mapOf("text" to "x".repeat(SafeAccessibilityActionContract.MAX_TEXT_LENGTH + 1)),
        )
        assertEquals("UNSUPPORTED", result.status)
        assertFalse(result.ok)
    }

    @Test
    fun `unknown param field is rejected as UNSUPPORTED`() {
        val result = dispatch(
            toolName = "gui_click_text",
            params = mapOf("text" to "ok", "arbitrary" to "payload"),
        )
        assertEquals("UNSUPPORTED", result.status)
        assertFalse(result.ok)
    }

    @Test
    fun `click_text without a text target is rejected as UNSUPPORTED`() {
        val result = dispatch(toolName = "gui_click_text", params = emptyMap())
        assertEquals("UNSUPPORTED", result.status)
        assertFalse(result.ok)
    }

    // ----------------------------------------------------------- no fake magic

    @Test
    fun `a declining seam never yields a fake SUCCESS`() {
        val declining = SafeAccessibilityActionExecutor { _ ->
            SafeActionPerformResult.Failed("拒绝执行")
        }
        val result = dispatch(toolName = "gui_global_home", executor = declining)
        assertFalse(result.ok)
        assertEquals("ERROR", result.status)
    }

    @Test
    fun `an unsupported seam maps to UNSUPPORTED, never SUCCESS`() {
        val unsupported = SafeAccessibilityActionExecutor { _ ->
            SafeActionPerformResult.Unsupported("未找到目标节点")
        }
        val result = dispatch(toolName = "gui_click_text", params = mapOf("text" to "nope"), executor = unsupported)
        assertEquals("UNSUPPORTED", result.status)
        assertFalse(result.ok)
    }

    // ------------------------------------------------------- sanitization size

    @Test
    fun `every message is bounded and never leaks the long target`() {
        val longTarget = "secret-target-${"z".repeat(300)}"
        val result = dispatch(toolName = "gui_click_text", params = mapOf("text" to longTarget))
        assertTrue("must be bounded", result.message.length <= SafeActionToolResult.MAX_MESSAGE_LENGTH)
        assertFalse("target text must not appear in success summary", result.message.contains(longTarget))
    }

    @Test
    fun `safe success label never contains node text or descriptions`() {
        val click = dispatch(toolName = "gui_click_text", params = mapOf("text" to "Top Secret Label"))
        assertTrue(click.ok)
        assertFalse(click.message.contains("Top Secret Label"))
        val cd = dispatch(
            toolName = "gui_click_content_description",
            params = mapOf("content_description" to "Secret description"),
        )
        assertTrue(cd.ok)
        assertFalse(cd.message.contains("Secret description"))
    }

    @Test
    fun `json result is bounded and shape-stable`() {
        val result = dispatch(toolName = "gui_scroll_backward")
        val json = result.toJsonString()
        assertTrue(json.startsWith("{\"ok\":true,\"status\":\"SUCCESS\",\"message\":\""))
        assertTrue(json.length <= 512)
        assertFalse(json.contains("AccessibilityNodeInfo"))
        assertFalse(json.contains("screenshot"))
        assertFalse(json.contains("token"))
    }

    // --------------------------------------------------- timeout / exception map

    @Test
    fun `timeout maps to a bounded ERROR result, never a fake success`() {
        val result = GuiSafeActionBridge.timeoutResult(10_000L)
        assertFalse(result.ok)
        assertEquals("ERROR", result.status)
        assertTrue(result.message.length <= SafeActionToolResult.MAX_MESSAGE_LENGTH)
        assertTrue(result.message.contains("超时"))
    }

    @Test
    fun `unexpected exception maps to a bounded ERROR result with the class name`() {
        val result = GuiSafeActionBridge.exceptionResult(IllegalStateException("boom"))
        assertFalse(result.ok)
        assertEquals("ERROR", result.status)
        assertTrue("must reference the exception type", result.message.contains("IllegalStateException"))
        assertFalse("must not leak the raw message/secret", result.message.contains("boom"))
    }
}
