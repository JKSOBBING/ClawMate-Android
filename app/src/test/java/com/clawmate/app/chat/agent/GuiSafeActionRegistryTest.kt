package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.ToolCallFunctionWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Device-free tests for the Phase 47 wiring of the safe *semantic* GUI tools
 * into the default registry:
 *  - the seven safe action tools are registered and can be executed;
 *  - with no opt-in / no connected service the default (live) gateway rejects
 *    honestly (NOT_CONFIGURED / BLOCKED_SERVICE) and never fakes success;
 *  - the coordinate/gesture/extraction tools stay controlled-disabled;
 *  - results are bounded JSON with no screen content.
 */
class GuiSafeActionRegistryTest {

    private lateinit var registry: ToolRegistry

    @Before
    fun setUp() {
        com.clawmate.app.accessibility.AccessibilityOptInHolder.reset()
        com.clawmate.app.accessibility.AccessibilityStateRegistry.reset()
        com.clawmate.app.accessibility.SafeActionServiceHolder.onDisconnected()
        registry = createDefaultToolRegistry()
    }

    private fun call(name: String, args: String): ToolExecutionResult = runBlocking {
        registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire(name, args)),
            ToolExecutionContext(),
        )
    }

    @Test
    fun `safe semantic tools are registered`() {
        val names = registry.toolNames().toSet()
        listOf(
            "gui_click_text", "gui_click_content_description", "gui_back",
            "gui_scroll_forward", "gui_scroll_backward", "gui_global_back", "gui_global_home",
        ).forEach { assertTrue("$it must be registered", names.contains(it)) }
    }

    @Test
    fun `safe tool with no opt-in returns NOT_CONFIGURED and never fake success`() {
        // Default: no explicit opt-in -> REJECTED. The tool runs and reports its
        // honest gate status in the JSON body ("ok":false) — never a fake success.
        val result = call("gui_back", "{}")
        assertTrue("the tool adapter ran without error", result.success)
        assertTrue("carries NOT_CONFIGURED status", result.content.contains("NOT_CONFIGURED"))
        assertTrue(result.content.contains("\"ok\":false"))
    }

    @Test
    fun `safe tool with opt-in but no connected service returns BLOCKED_SERVICE`() {
        com.clawmate.app.accessibility.AccessibilityOptInHolder.set(true)
        com.clawmate.app.accessibility.AccessibilityStateRegistry.reset() // service down
        val result = call("gui_scroll_forward", "{}")
        assertTrue("the tool adapter ran without error", result.success)
        assertTrue("must reference the blocked service", result.content.contains("BLOCKED_SERVICE"))
        assertTrue(result.content.contains("\"ok\":false"))
    }

    @Test
    fun `safe tool result is bounded JSON with no screen content or secrets`() {
        com.clawmate.app.accessibility.AccessibilityOptInHolder.set(true)
        com.clawmate.app.accessibility.AccessibilityStateRegistry.reset()
        val result = call("gui_click_text", """{"text":"Top Secret Node"}""")
        assertTrue("the tool adapter ran without error", result.success)
        assertTrue(result.content.length <= 512)
        assertFalse(result.content.contains("Top Secret Node"))
        assertFalse(result.content.contains("AccessibilityNodeInfo"))
        assertFalse(result.content.contains("token"))
    }

    @Test
    fun `result obeys the TRUNCATE bound used by the loop`() {
        val result = call("gui_click_content_description", """{"content_description":"${"y".repeat(50)}"}""")
        assertNotNull(result)
        assertTrue(result.truncatedContent.length <= 4000 + 3)
    }

    @Test
    fun `coordinate and gesture tools remain controlled-disabled`() {
        listOf("gui_screen_tap", "gui_type_text", "gui_swipe", "capture_screen", "open_app")
            .forEach { name ->
                val result = call(name, "{}")
                assertEquals("$name must stay DISABLED", ToolResultKind.DISABLED, result.kind)
                assertTrue(result.errorPayload.contains("\"disabled\":true"))
            }
    }

    @Test
    fun `parsing failure returns a typed unsupported result, not a crash`() {
        val result = call("gui_click_text", "not-json")
        assertFalse(result.success)
        assertTrue(result.content.contains("\"ok\":false"))
        assertTrue(result.content.contains("参数不合法") || result.content.contains("non")
                || result.content.contains("JSON") || result.content.contains("json"))
    }
}
