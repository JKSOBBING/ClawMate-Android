package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.ToolCallFunctionWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Deterministic tests for [ToolRegistry]: safe adapter execution, argument
 * validation, unknown-tool handling, and the controlled-disabled adapters for
 * Harmony GUI/HDC/overlay actions (which must explain the PC bridge/permission
 * instead of faking success). No network / platform.
 */
class ToolRegistryTest {

    @Test
    fun `safe adapter runs and returns its result`() = runBlocking {
        val registry = ToolRegistry().apply {
            register(AgentTool("echo", "echoes input") { args, _ ->
                "echo:$args"
            })
        }
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("echo", """{"x":1}""")),
            ToolExecutionContext(),
        )
        assertTrue(result.success)
        assertEquals(ToolResultKind.OK, result.kind)
        assertEquals("echo:{\"x\":1}", result.content)
        assertTrue(result.traceLine.startsWith("工具：echo"))
    }

    @Test
    fun `invalid arguments produce a typed error result`() = runBlocking {
        val registry = ToolRegistry().apply {
            register(AgentTool("echo", "echoes input") { _, _ -> "ok" })
        }
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("echo", "not-json")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.ERROR, result.kind)
        assertTrue(result.errorPayload.contains("\"ok\":false"))
    }

    @Test
    fun `unknown tool produces a typed error result`() = runBlocking {
        val registry = ToolRegistry()
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("does_not_exist", "{}")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.ERROR, result.kind)
        assertTrue(result.errorPayload.contains("does not exist"))
    }

    @Test
    fun `adapter exception becomes a typed error result`() = runBlocking {
        val registry = ToolRegistry().apply {
            register(AgentTool("boom", "throws") { _, _ -> throw IllegalStateException("kaboom") })
        }
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("boom", "{}")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.ERROR, result.kind)
        assertTrue(result.errorPayload.contains("kaboom"))
    }

    @Test
    fun `default registry exposes safe adapters and disabled GUI adapters`() {
        val registry = createDefaultToolRegistry()
        assertTrue(registry.hasTools())
        assertTrue(registry.toolNames().contains("get_current_datetime"))
        assertTrue(registry.toolNames().contains("get_device_info"))
        // Controlled-disabled GUI adapters exist (no arbitrary execution).
        assertTrue(registry.toolNames().contains("gui_screen_tap"))
        assertTrue(registry.toolNames().contains("capture_screen"))
    }

    @Test
    fun `gui adapter returns a controlled-disabled result explaining the bridge`() = runBlocking {
        val result = createDefaultToolRegistry().execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("gui_screen_tap", """{"x":0.5,"y":0.5}""")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.DISABLED, result.kind)
        assertTrue(result.disabledReason.isNotEmpty())
        assertTrue(result.disabledReason.contains("PC"))
        assertTrue(result.errorPayload.contains("\"disabled\":true"))
    }

    @Test
    fun `terminal adapters stop the loop`() = runBlocking {
        val registry = ToolRegistry().apply {
            register(AgentTool("confirm_action", "asks before a device action") { _, _ ->
                throw ToolTerminalException("用户拒绝执行该操作")
            })
        }
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("confirm_action", "{}")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.TERMINAL, result.kind)
        assertEquals("用户拒绝执行该操作", result.terminalMessage)
    }

    @Test
    fun `long results are truncated to a stable bound`() = runBlocking {
        val long = "x".repeat(5000)
        val registry = ToolRegistry().apply {
            register(AgentTool("long", "returns long") { _, _ -> long })
        }
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("long", "{}")),
            ToolExecutionContext(),
        )
        assertTrue(result.success)
        assertTrue(result.truncatedContent.length <= 4000 + 3)
    }
}
