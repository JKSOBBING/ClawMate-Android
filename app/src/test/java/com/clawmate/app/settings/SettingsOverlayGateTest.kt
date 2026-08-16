package com.clawmate.app.settings

import com.clawmate.app.overlay.FakeOverlayPermissionDetector
import com.clawmate.app.overlay.OverlayGateStatus
import com.clawmate.app.overlay.OverlayPermissionContract
import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-free ViewModel tests for the Phase 20 float/edge overlay boundary
 * (row C1): the overlay opt-in toggle drives the gate, the live system-grant
 * is observed through the injected (fake) detector, and the system-settings
 * permission-request action is actionable only while opted in + not granted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOverlayGateTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeSettingsRepository(AppSettings.defaults())
    private val detector = FakeOverlayPermissionDetector()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        com.clawmate.app.overlay.OverlayStateRegistry.reset()
    }

    @After
    fun tearDown() {
        com.clawmate.app.overlay.OverlayStateRegistry.reset()
        Dispatchers.resetMain()
    }

    private fun vm() = SettingsViewModel(
        repo,
        "1.0.0",
        1L,
        accessibilityDetector = com.clawmate.app.accessibility.FakeAccessibilityServiceDetector(),
        overlayDetector = detector,
    )

    @Test
    fun `overlay gate starts NOT_CONFIGURED by default`() = runTest {
        val viewModel = vm()
        assertFalse(viewModel.state.value.draft.overlayOptIn)
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, viewModel.overlayGate().status)
        // Permission request must not be actionable when not opted in.
        assertFalse(viewModel.overlayPermissionRequest().canRequest)
    }

    @Test
    fun `opt-in transition with no system grant is BLOCKED and request is actionable`() = runTest {
        val viewModel = vm()
        detector.setPermissionGranted(false)
        viewModel.setOverlayOptIn(true)
        assertEquals(OverlayGateStatus.BLOCKED, viewModel.overlayGate().status)

        val request = viewModel.overlayPermissionRequest()
        assertTrue("requestable only when opted in and not granted", request.canRequest)
        assertEquals(OverlayPermissionContract.ACTION_MANAGE_OVERLAY_PERMISSION, request.action)
    }

    @Test
    fun `opt-in with system grant blocks on missing renderer and never fakes a surface`() = runTest {
        val viewModel = vm()
        detector.setPermissionGranted(true)
        detector.setRendererConfigured(false)
        viewModel.setOverlayOptIn(true)
        // Granted but no renderer → BLOCKED, and request is no longer actionable
        // (permission already present).
        assertEquals(OverlayGateStatus.BLOCKED, viewModel.overlayGate().status)
        assertFalse(viewModel.overlayPermissionRequest().canRequest)
        assertTrue(viewModel.overlayGate().surface() is com.clawmate.app.overlay.OverlaySurfaceResult.BlockedNoRenderer)
    }

    @Test
    fun `turning opt-in off returns boundary to NOT_CONFIGURED`() = runTest {
        val viewModel = vm()
        detector.setPermissionGranted(true)
        viewModel.setOverlayOptIn(true)
        assertEquals(OverlayGateStatus.BLOCKED, viewModel.overlayGate().status) // no renderer
        viewModel.setOverlayOptIn(false)
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, viewModel.overlayGate().status)
        assertFalse(viewModel.overlayPermissionRequest().canRequest)
    }
}
