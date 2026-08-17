package com.clawmate.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the pure Phase 26 service-start decision
 * ([OverlayServiceStartPolicy]): the overlay status service may be started only
 * when the live gate is READY, and must be stopped when the gate is no longer
 * READY while the service is still present (e.g. opt-in turned off).
 */
class OverlayServiceStartPolicyTest {

    @Test
    fun `start step is STOPPED when not configured`() {
        val step = OverlayServiceStartPolicy.startStep(
            OverlayGate(optedIn = false, overlayPermissionGranted = true, rendererConfigured = true),
        )
        assertTrue(step is OverlayServiceStartStep.KEEP_STOPPED)
    }

    @Test
    fun `start step is STOPPED when permission missing even if opted in`() {
        val step = OverlayServiceStartPolicy.startStep(
            OverlayGate(optedIn = true, overlayPermissionGranted = false, rendererConfigured = true),
        )
        assertTrue(step is OverlayServiceStartStep.KEEP_STOPPED)
    }

    @Test
    fun `start step is STOPPED when renderer absent`() {
        val step = OverlayServiceStartPolicy.startStep(
            OverlayGate(optedIn = true, overlayPermissionGranted = true, rendererConfigured = false),
        )
        assertTrue(step is OverlayServiceStartStep.KEEP_STOPPED)
    }

    @Test
    fun `start step is START only when gate READY`() {
        val step = OverlayServiceStartPolicy.startStep(
            OverlayGate(optedIn = true, overlayPermissionGranted = true, rendererConfigured = true),
        )
        assertEquals(OverlayServiceStartStep.START, step)
    }

    @Test
    fun `RUNNING present service must not be stopped when gate READY`() {
        assertFalse(OverlayServiceStartPolicy.stopRequired(gate(true, true, true), servicePresent = true))
    }

    @Test
    fun `present service must be stopped when opt-in is off`() {
        assertTrue(OverlayServiceStartPolicy.stopRequired(gate(false, true, true), servicePresent = true))
    }

    @Test
    fun `present service must be stopped when permission revoked`() {
        assertTrue(OverlayServiceStartPolicy.stopRequired(gate(true, false, true), servicePresent = true))
    }

    @Test
    fun `absent service is never reported as needing a stop`() {
        assertFalse(OverlayServiceStartPolicy.stopRequired(gate(false, true, true), servicePresent = false))
    }

    private fun gate(optedIn: Boolean, permission: Boolean, renderer: Boolean): OverlayGate =
        OverlayGate(optedIn, permission, renderer)
}
