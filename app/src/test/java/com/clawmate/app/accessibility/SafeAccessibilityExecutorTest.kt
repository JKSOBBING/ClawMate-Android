package com.clawmate.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 22 safe *semantic* AccessibilityService action
 * executor boundary:
 *  - the closed typed allowlist (CLICK_TEXT / CLICK_CONTENT_DESCRIPTION / BACK /
 *    SCROLL_FORWARD / SCROLL_BACKWARD / GLOBAL_BACK / GLOBAL_HOME);
 *  - bounded text/content-description/package inputs (oversized → rejected);
 *  - readiness gating: no opt-in (NOT_CONFIGURED), service absent
 *    (BLOCKED_SERVICE), foreground package mismatch (BLOCKED_PACKAGE);
 *  - success mapping (platform seam actually performed → SUCCESS) and failure
 *    mapping (platform refused → ERROR / UNSUPPORTED), with no fake success.
 *
 * All tests are pure — the platform seam ([SafeAccessibilityActionExecutor]) is
 * injected, and no Android framework API is touched.
 */
class SafeAccessibilityExecutorTest {

    private val allowlist = setOf("com.clawmate.app")

    private fun readyGate() = SafeAccessibilityExecutionGate(
        optedIn = true,
        serviceEnabled = true,
        currentForegroundPackage = "com.clawmate.app",
        allowlistedPackages = allowlist,
    )

    // --------------------------------------------------------------- allowlist

    @Test
    fun `closed safe allowlist contains only the seven semantic actions`() {
        assertEquals(
            setOf(
                "click_text", "click_content_description", "back", "scroll_forward",
                "scroll_backward", "global_back", "global_home",
            ),
            SafeAccessibilityActionId.entries.map { it.wireName }.toSet(),
        )
        assertFalse("no coordinate/gesture action is allowlisted",
            SafeAccessibilityActionId.entries.any { it.wireName in setOf("tap", "swipe", "type_text", "open_app", "capture_screen") })
        assertTrue(SafeAccessibilityActionId.contains("click_text"))
        assertFalse(SafeAccessibilityActionId.contains("run_cmd"))
        assertFalse(SafeAccessibilityActionId.contains("tap"))
    }

    @Test
    fun `unknown action is rejected as unsupported by the dispatcher`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val result = dispatcher.dispatch("run_cmd", emptyMap())
        assertTrue("run_cmd must be rejected, never performed",
            result is AccessibilityExecutionOutcome.Unsupported)
    }

    @Test
    fun `click_text requires a non-empty text target`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        assertTrue(dispatcher.dispatch("click_text", emptyMap()) is AccessibilityExecutionOutcome.Unsupported)
        assertTrue(dispatcher.dispatch("click_text", mapOf("text" to "  ")) is AccessibilityExecutionOutcome.Unsupported)
    }

    // ----------------------------------------------------------- bounded inputs

    @Test
    fun `oversized click_text text is rejected as unsupported`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val huge = "x".repeat(SafeAccessibilityActionContract.MAX_TEXT_LENGTH + 1)
        val result = dispatcher.dispatch("click_text", mapOf("text" to huge))
        assertTrue(result is AccessibilityExecutionOutcome.Unsupported)
    }

    @Test
    fun `oversized content_description is rejected as unsupported`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val huge = "x".repeat(SafeAccessibilityActionContract.MAX_CONTENT_DESCRIPTION_LENGTH + 1)
        val result = dispatcher.dispatch("click_content_description", mapOf("content_description" to huge))
        assertTrue(result is AccessibilityExecutionOutcome.Unsupported)
    }

    @Test
    fun `oversized package_name is rejected as unsupported`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val huge = "x".repeat(SafeAccessibilityActionContract.MAX_PACKAGE_LENGTH + 1)
        val result = dispatcher.dispatch("back", mapOf("package_name" to huge))
        assertTrue(result is AccessibilityExecutionOutcome.Unsupported)
    }

    @Test
    fun `boundary text at or under the max is accepted when gate is ready`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val valid = "x".repeat(SafeAccessibilityActionContract.MAX_TEXT_LENGTH)
        val result = dispatcher.dispatch("click_text", mapOf("text" to valid))
        assertTrue("valid bounded text on READY must reach the seam and succeed",
            result is AccessibilityExecutionOutcome.Success)
    }

    // ------------------------------------------------------- readiness / gating

    @Test
    fun `no opt-in yields NOT_CONFIGURED even when service present`() {
        val gate = SafeAccessibilityExecutionGate(
            optedIn = false,
            serviceEnabled = true,
            currentForegroundPackage = "com.clawmate.app",
            allowlistedPackages = allowlist,
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(gate, FakeExecutor.Performed)
        val result = dispatcher.dispatch("back", emptyMap())
        assertTrue("no opt-in must never dispatch", result is AccessibilityExecutionOutcome.NotConfigured)
    }

    @Test
    fun `opt-in without service yields BLOCKED_SERVICE`() {
        val gate = SafeAccessibilityExecutionGate(
            optedIn = true,
            serviceEnabled = false,
            currentForegroundPackage = "com.clawmate.app",
            allowlistedPackages = allowlist,
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(gate, FakeExecutor.Performed)
        assertTrue(dispatcher.dispatch("scroll_forward", emptyMap()) is AccessibilityExecutionOutcome.BlockedService)
    }

    @Test
    fun `foreground package mismatch yields BLOCKED_PACKAGE`() {
        val gate = SafeAccessibilityExecutionGate(
            optedIn = true,
            serviceEnabled = true,
            currentForegroundPackage = "com.other.app",
            allowlistedPackages = allowlist,
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(gate, FakeExecutor.Performed)
        val result = dispatcher.dispatch("global_home", emptyMap())
        assertTrue("cross-package action must be blocked", result is AccessibilityExecutionOutcome.BlockedPackage)
    }

    @Test
    fun `null foreground package is BLOCKED_PACKAGE`() {
        val gate = SafeAccessibilityExecutionGate(
            optedIn = true,
            serviceEnabled = true,
            currentForegroundPackage = null,
            allowlistedPackages = allowlist,
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(gate, FakeExecutor.Performed)
        assertTrue(dispatcher.dispatch("back", emptyMap()) is AccessibilityExecutionOutcome.BlockedPackage)
    }

    // -------------------------------------------------------- success / failure

    @Test
    fun `ready gate with a performing seam returns SUCCESS`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        val result = dispatcher.dispatch("global_back", emptyMap())
        assertTrue("real perform must map to SUCCESS", result is AccessibilityExecutionOutcome.Success)
        assertEquals("全局返回", (result as AccessibilityExecutionOutcome.Success).performedDescription)
    }

    @Test
    fun `ready gate with a failing seam returns ERROR`() {
        val executor = SafeAccessibilityActionExecutor { _ ->
            SafeActionPerformResult.Failed("辅助功能服务拒绝执行。")
        }
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), executor)
        assertTrue(dispatcher.dispatch("back", emptyMap()) is AccessibilityExecutionOutcome.Error)
    }

    @Test
    fun `ready gate with an unsupported seam returns UNSUPPORTED`() {
        val executor = SafeAccessibilityActionExecutor { _ ->
            SafeActionPerformResult.Unsupported("未找到可点击节点。")
        }
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), executor)
        assertTrue(dispatcher.dispatch("click_text", mapOf("text" to "nope")) is AccessibilityExecutionOutcome.Unsupported)
    }

    @Test
    fun `no fake success when the seam declines even on a ready gate`() {
        val executor = SafeAccessibilityActionExecutor { _ ->
            SafeActionPerformResult.Unsupported("演示边界不执行真实动作。")
        }
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), executor)
        val result = dispatcher.dispatch("global_home", emptyMap())
        assertTrue("a declining seam must never yield SUCCESS", result !is AccessibilityExecutionOutcome.Success)
    }

    // ---------------------------------------------------------------- contract

    @Test
    fun `allowlist excludes the phase-19 gated coordinate actions`() {
        val dispatcher = ControlledSafeAccessibilityDispatcher(readyGate(), FakeExecutor.Performed)
        // The Phase-19 coordinate boundary (TAP/TYPE_TEXT/SWIPE/OPEN_APP/CAPTURE)
        // is NOT part of the safe semantic allowlist.
        listOf("tap", "swipe", "type_text", "open_app", "capture_screen", "app_start", "press_home")
            .forEach { name ->
                assertTrue("'$name' must not be a safe semantic action",
                    dispatcher.dispatch(name, emptyMap()) is AccessibilityExecutionOutcome.Unsupported)
            }
    }
}

/** Test seam that reports a real performed action. */
private val FakeExecutor = object {
    val Performed = SafeAccessibilityActionExecutor { action ->
        SafeActionPerformResult.Performed(action.performedDescription)
    }
}
