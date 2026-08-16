package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.ToolCallFunctionWire
import com.clawmate.app.overlay.OverlayCapabilityFlags
import com.clawmate.app.overlay.OverlayStateRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Device-free tests for wiring the existing float-overlay tool to the Phase 20
 * overlay gate (row C1): the `float_overlay_status` tool remains controlled-
 * disabled (never draws a fake overlay) but its disabled result reflects the
 * *live* overlay gate — NOT_CONFIGURED by default, never a fake surface.
 */
class OverlayToolNoteTest {

    @Test
    fun `float_overlay_status is present and controlled-disabled`() = runBlocking {
        OverlayCapabilityFlags.overlayOptedIn = false
        OverlayStateRegistry.reset()
        val registry = createDefaultToolRegistry()
        assertTrue(registry.toolNames().contains("float_overlay_status"))
        val result = registry.execute(
            ToolCallWire(id = "c1", function = ToolCallFunctionWire("float_overlay_status", "{}")),
            ToolExecutionContext(),
        )
        assertFalse(result.success)
        assertEquals(ToolResultKind.DISABLED, result.kind)
        assertTrue(result.errorPayload.contains("\"disabled\":true"))
        // Never claims an overlay rendered.
        assertFalse(result.disabledReason.contains("已渲染"))
        // Default (not configured) state is surfaced honestly.
        assertTrue(result.disabledReason.contains("NOT_CONFIGURED"))
    }

    @Test
    fun `overlay availability note reflects opted-in but blocked permission`() {
        OverlayCapabilityFlags.overlayOptedIn = true
        OverlayStateRegistry.reset() // permission not observed granted
        assertTrue(OverlayStateRegistry.isPermissionGranted().not())
        val note = overlayAvailabilityNote()
        assertTrue(note.contains("BLOCKED"))
        assertFalse("must never claim an overlay rendered", note.contains("已渲染"))
        OverlayCapabilityFlags.overlayOptedIn = false
        OverlayStateRegistry.reset()
    }
}
