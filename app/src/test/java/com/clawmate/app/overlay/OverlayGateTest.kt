package com.clawmate.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Phase 20 float/edge overlay boundary (row C1):
 *  - gate status derivation from (opt-in, system overlay permission, renderer);
 *  - the system-overlay-permission detector abstraction (fake + registry);
 *  - opt-in transitions (off→on, grant present, renderer absent);
 *  - the system-settings permission-request action descriptor;
 *  - safe disabled behavior: the lifecycle-safe service policy stops rather than
 *    renders unless the gate is READY, and never fabricates a surface.
 */
class OverlayGateTest {

    // ---------------------------------------------------------- gate states

    @Test
    fun `no opt-in yields NOT_CONFIGURED even if permission and renderer present`() {
        val gate = OverlayGate(optedIn = false, overlayPermissionGranted = true, rendererConfigured = true)
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, gate.status)
        assertTrue(gate.surface() is OverlaySurfaceResult.NotConfigured)
    }

    @Test
    fun `opt-in without system grant yields BLOCKED permission`() {
        val gate = OverlayGate(optedIn = true, overlayPermissionGranted = false, rendererConfigured = true)
        assertEquals(OverlayGateStatus.BLOCKED, gate.status)
        assertTrue(gate.surface() is OverlaySurfaceResult.BlockedPermission)
    }

    @Test
    fun `opt-in with grant but no renderer stays BLOCKED no renderer`() {
        val gate = OverlayGate(optedIn = true, overlayPermissionGranted = true, rendererConfigured = false)
        assertEquals(OverlayGateStatus.BLOCKED, gate.status)
        assertTrue(gate.surface() is OverlaySurfaceResult.BlockedNoRenderer)
    }

    @Test
    fun `opt-in with grant and renderer reaches READY but renders only neutral surface`() {
        val gate = OverlayGate(optedIn = true, overlayPermissionGranted = true, rendererConfigured = true)
        assertEquals(OverlayGateStatus.READY, gate.status)
        val result = gate.surface()
        assertTrue("READY must only offer the neutral surface, never agent content", result is OverlaySurfaceResult.ReadyNeutralSurface)
        assertEquals(OverlayNeutralSurfaceText.STATUS_LINE, (result as OverlaySurfaceResult.ReadyNeutralSurface).surfaceText)
        // The READY surface is static status text that must never claim an agent surface.
        assertFalse(
            "neutral surface must not claim agent content is rendered",
            OverlayNeutralSurfaceText.STATUS_LINE.contains("运行中"),
        )
    }

    @Test
    fun `status is never ready without an explicit opt-in`() {
        val gate = OverlayGate(optedIn = false, overlayPermissionGranted = true, rendererConfigured = true)
        assertFalse(gate.status == OverlayGateStatus.READY)
    }

    @Test
    fun `default build facts keep gate BLOCKED or NOT_CONFIGURED not READY without opt-in and grant`() {
        // Phase 26 ships the status-only renderer, but READY still additionally
        // requires explicit opt-in + the observed SYSTEM_ALERT_WINDOW grant.
        assertTrue(OverlayCapabilityFlags.rendererConfigured)
        val gateNotConfigured = OverlayGate(
            optedIn = false,
            overlayPermissionGranted = true,
            rendererConfigured = OverlayCapabilityFlags.rendererConfigured,
        )
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, gateNotConfigured.status)
        // Opted in + renderer, but the system grant is not observed → BLOCKED.
        val gateBlocked = OverlayGate(
            optedIn = true,
            overlayPermissionGranted = false,
            rendererConfigured = OverlayCapabilityFlags.rendererConfigured,
        )
        assertEquals(OverlayGateStatus.BLOCKED, gateBlocked.status)
    }

    // --------------------------------------------------- opt-in transitions

    @Test
    fun `toggling opt-in on with no grant blocks, then grant moves past not-configured`() {
        // off → NOT_CONFIGURED even when permission present.
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, OverlayGate(false, true, false).status)
        // on, still no grant → BLOCKED (permission).
        assertEquals(OverlayGateStatus.BLOCKED, OverlayGate(true, false, false).status)
        // on + grant, no renderer → BLOCKED (no renderer) — never a fake surface.
        assertTrue(OverlayGate(true, true, false).surface() is OverlaySurfaceResult.BlockedNoRenderer)
    }

    @Test
    fun `opt-in is off by default in capability flags`() {
        // Safe default: the scaffold is NOT configured until a user explicitly opts in.
        assertFalse(OverlayCapabilityFlags.overlayOptedIn)
    }

    // ----------------------------------------------------------- detection

    @Test
    fun `fake detector drives gate deterministically`() {
        val detector = FakeOverlayPermissionDetector(permissionGranted = true, rendererConfigured = false)
        val gate = OverlayGate(
            optedIn = true,
            overlayPermissionGranted = detector.isOverlayPermissionGranted(),
            rendererConfigured = detector.isRendererConfigured(),
        )
        assertEquals(OverlayGateStatus.BLOCKED, gate.status)

        detector.setRendererConfigured(true)
        val gate2 = OverlayGate(
            optedIn = true,
            overlayPermissionGranted = detector.isOverlayPermissionGranted(),
            rendererConfigured = detector.isRendererConfigured(),
        )
        assertEquals(OverlayGateStatus.READY, gate2.status)
    }

    @Test
    fun `registry reports not granted but status-only renderer configured by default`() {
        OverlayStateRegistry.reset()
        assertFalse(OverlayStateRegistry.isPermissionGranted())
        assertFalse(OverlayStateRegistry.isOverlayPresent())
        val detector = RegistryOverlayPermissionDetector()
        assertFalse(detector.isOverlayPermissionGranted())
        // Phase 26 — status-only renderer is configured; READY still needs the grant.
        assertTrue(detector.isRendererConfigured())
    }

    @Test
    fun `registry tracks observed grant and overlay presence`() {
        OverlayStateRegistry.reset()
        OverlayStateRegistry.onPermissionGranted()
        OverlayStateRegistry.onOverlayPresent()
        assertTrue(OverlayStateRegistry.isPermissionGranted())
        assertTrue(OverlayStateRegistry.isOverlayPresent())
        OverlayStateRegistry.onOverlayGone()
        assertFalse(OverlayStateRegistry.isOverlayPresent())
        OverlayStateRegistry.reset()
        assertFalse(OverlayStateRegistry.isPermissionGranted())
    }

    // -------------------------------------- system-settings permission request

    @Test
    fun `permission request is actionable only when opted in and not granted`() {
        val pkg = "com.clawmate.app"
        val action = OverlayPermissionContract.ACTION_MANAGE_OVERLAY_PERMISSION
        // Not opted in → never requestable.
        assertFalse(OverlayPermissionRequest.forGate(action, pkg, overlayOptIn = false, overlayPermissionGranted = false).canRequest)
        // Opted in but already granted → no request needed.
        assertFalse(OverlayPermissionRequest.forGate(action, pkg, overlayOptIn = true, overlayPermissionGranted = true).canRequest)
        // Opted in and not yet granted → requestable.
        val requestable = OverlayPermissionRequest.forGate(action, pkg, overlayOptIn = true, overlayPermissionGranted = false)
        assertTrue(requestable.canRequest)
        assertEquals(action, requestable.action)
        assertEquals(pkg, requestable.packageName)
        // The contract carries the canonical Android overlay-permission settings action.
        assertEquals("android.settings.action.MANAGE_OVERLAY_PERMISSION", OverlayPermissionContract.ACTION_MANAGE_OVERLAY_PERMISSION)
    }

    // -------------------------------------------------- safe disabled behavior

    @Test
    fun `service policy stops rather than renders when gate not ready`() {
        // NOT_CONFIGURED → stop (refuse to start silently).
        val stopNotConfig = OverlayServicePolicy.decide(OverlayGate(false, true, true))
        assertTrue(stopNotConfig is OverlayServiceDecision.Stop)
        // opted in, permission missing → stop.
        val stopBlocked = OverlayServicePolicy.decide(OverlayGate(true, false, false))
        assertTrue(stopBlocked is OverlayServiceDecision.Stop)
        // opted in + grant, no renderer → stop (this build's only reachable path).
        val stopNoRenderer = OverlayServicePolicy.decide(OverlayGate(true, true, false))
        assertTrue(stopNoRenderer is OverlayServiceDecision.Stop)
    }

    @Test
    fun `service policy renders only neutral surface when ready`() {
        val decision = OverlayServicePolicy.decide(OverlayGate(true, true, true))
        assertTrue(decision is OverlayServiceDecision.RenderNeutralSurface)
        assertEquals(
            OverlayNeutralSurfaceText.STATUS_LINE,
            (decision as OverlayServiceDecision.RenderNeutralSurface).surfaceText,
        )
    }
}
