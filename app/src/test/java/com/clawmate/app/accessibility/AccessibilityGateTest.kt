package com.clawmate.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 19 AutoAgent controlled-accessibility
 * boundary (migration row B1):
 *  - gate status derivation from (opt-in, system service, provider);
 *  - permission/service detection abstraction (registry + fake detector);
 *  - allowlist acceptance + rejection of unknown actions / bad payloads;
 *  - honest BLOCKED / NOT_CONFIGURED results (never a fake success);
 *  - backward compatibility of the default (no provider) boundary and the
 *    controlled-disabled GUI tools.
 */
class AccessibilityGateTest {

    // ---------------------------------------------------------- gate states

    @Test
    fun `no opt-in yields NOT_CONFIGURED even if system service present`() {
        val gate = AccessibilityGate(optedIn = false, systemServiceEnabled = true, providerConfigured = true)
        assertEquals(AccessibilityGateStatus.NOT_CONFIGURED, gate.status)
        val result = gate.evaluate("tap", mapOf("x" to 0.5, "y" to 0.5))
        assertTrue("no opt-in must never dispatch", result is AccessibilityActionResult.NotConfigured)
    }

    @Test
    fun `opt-in without system grant yields BLOCKED`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = false, providerConfigured = true)
        assertEquals(AccessibilityGateStatus.BLOCKED, gate.status)
        val result = gate.evaluate("tap", mapOf("x" to 0.5, "y" to 0.5))
        assertTrue("permission absent must stay BLOCKED", result is AccessibilityActionResult.BlockedPermission)
    }

    @Test
    fun `opt-in with system grant but no provider stays BLOCKED no provider`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = true, providerConfigured = false)
        assertEquals(AccessibilityGateStatus.BLOCKED, gate.status)
        val result = gate.evaluate("tap", mapOf("x" to 0.5, "y" to 0.5))
        assertTrue(result is AccessibilityActionResult.BlockedNoProvider)
    }

    @Test
    fun `opt-in with system grant and provider reaches READY boundary but never executes`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = true, providerConfigured = true)
        assertEquals(AccessibilityGateStatus.READY, gate.status)
        val result = gate.evaluate("tap", mapOf("x" to 0.5, "y" to 0.5))
        // READY is a *boundary*, not a permission to fabricate a GUI control
        // action: this build's safe scaffold still reports a no-op blocked
        // result rather than a fake success.
        assertTrue("READY must still not fabricate an action", result is AccessibilityActionResult.BlockedNoProvider)
    }

    @Test
    fun `status is never ready without an explicit opt-in`() {
        val gate = AccessibilityGate(optedIn = false, systemServiceEnabled = true, providerConfigured = true)
        assertFalse(gate.status == AccessibilityGateStatus.READY)
    }

    // -------------------------------------------------------- allowlist gates

    @Test
    fun `recognized action with allowlisted fields passes allowlist`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = false, providerConfigured = false)
        // Allowlisted + shape-valid; the outcome is the honest gate result
        // (BLOCKED here), not "unknown action".
        val result = gate.evaluate("swipe", mapOf("from_x" to 0.1, "from_y" to 0.1, "to_x" to 0.9, "to_y" to 0.9))
        assertTrue(result !is AccessibilityActionResult.RejectedNotAllowlisted)
    }

    @Test
    fun `unknown action is rejected by the allowlist`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = true, providerConfigured = true)
        val result = gate.evaluate("run_cmd", emptyMap())
        assertEquals(AccessibilityActionResult.RejectedNotAllowlisted("run_cmd"), result)
    }

    @Test
    fun `known id with disallowed payload field is rejected as unsupported`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = true, providerConfigured = true)
        val result = gate.evaluate("type_text", mapOf("text" to "hi", "secret_script" to "echo 1"))
        assertTrue(result is AccessibilityActionResult.RejectedUnsupportedPayload)
    }

    @Test
    fun `adblock arbitrary command names are not on the allowlist`() {
        val gate = AccessibilityGate(optedIn = true, systemServiceEnabled = true, providerConfigured = true)
        listOf("run_cmd", "adb_shell", "hdc_shell", "exec", "wechat_collect", "screenshot_upload", "vlm_call")
            .forEach { name ->
                assertTrue(
                    "action '$name' must never be allowlisted",
                    gate.evaluate(name, emptyMap()) is AccessibilityActionResult.RejectedNotAllowlisted,
                )
            }
    }

    // ----------------------------------------------- permission/service detection

    @Test
    fun `fake detector drives gate deterministically`() {
        val detector = FakeAccessibilityServiceDetector(serviceEnabled = true, providerConfigured = false)
        val gate = AccessibilityGate(
            optedIn = true,
            systemServiceEnabled = detector.isServiceEnabledBySystem(),
            providerConfigured = detector.isProviderConfigured(),
        )
        assertEquals(AccessibilityGateStatus.BLOCKED, gate.status)

        detector.setProviderConfigured(true)
        val gate2 = AccessibilityGate(
            optedIn = true,
            systemServiceEnabled = detector.isServiceEnabledBySystem(),
            providerConfigured = detector.isProviderConfigured(),
        )
        assertEquals(AccessibilityGateStatus.READY, gate2.status)
    }

    @Test
    fun `registry reports disconnected by default and connect then disconnect`() {
        AccessibilityStateRegistry.reset()
        assertFalse(AccessibilityStateRegistry.isServiceConnected())
        AccessibilityStateRegistry.onServiceConnected()
        assertTrue(AccessibilityStateRegistry.isServiceConnected())
        AccessibilityStateRegistry.onServiceDisconnected()
        assertFalse(AccessibilityStateRegistry.isServiceConnected())
    }

    @Test
    fun `registry-backed detector reflects registry live state`() {
        AccessibilityStateRegistry.reset()
        val detector = RegistryAccessibilityServiceDetector()
        assertFalse(detector.isServiceEnabledBySystem())
        assertFalse("no provider in this build", detector.isProviderConfigured())
        AccessibilityStateRegistry.onServiceConnected()
        assertTrue(detector.isServiceEnabledBySystem())
        AccessibilityStateRegistry.reset()
    }

    // ------------------------------------------------- backward compatibility

    @Test
    fun `default boundary with defaults stays BLOCKED not fake-ready`() {
        val detector = RegistryAccessibilityServiceDetector()
        AccessibilityStateRegistry.reset()
        val gate = AccessibilityGate(
            optedIn = true,
            systemServiceEnabled = detector.isServiceEnabledBySystem(),
            providerConfigured = detector.isProviderConfigured(),
        )
        // No opt-in (default) is NOT_CONFIGURED; even with opt-in, no provider
        // keeps it BLOCKED. Neither ever fakes READY-with-actions.
        assertEquals(AccessibilityGateStatus.BLOCKED, gate.status)
    }

    @Test
    fun `allowlist contains only the closed safe action set`() {
        assertEquals(
            setOf("tap", "type_text", "swipe", "open_app", "capture_screen"),
            AccessibilityActionAllowlist.BY_NAME.keys,
        )
        assertFalse(AccessibilityActionAllowlist.contains("run_cmd"))
        assertFalse(AccessibilityActionAllowlist.contains("wechat_collect"))
    }
}
