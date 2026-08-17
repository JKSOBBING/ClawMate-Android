package com.clawmate.app.settings

import com.clawmate.app.overlay.FakeOverlayPermissionDetector
import com.clawmate.app.overlay.OverlayGateStatus
import com.clawmate.app.overlay.OverlayServiceController
import com.clawmate.app.overlay.OverlayServiceStartStep
import com.clawmate.app.overlay.OverlayServiceStartPolicy
import com.clawmate.app.overlay.OverlayStateRegistry
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
 * Device-free ViewModel tests for the Phase 26 explicit overlay *service*
 * start/stop action: the pure [OverlayServiceStartStep] is derived from the live
 * gate, the real controller (recording fake) is invoked only when READY, and
 * turning opt-in off stops the service via [OverlayServiceStartPolicy.stopRequired].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOverlayServiceControlTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeSettingsRepository(AppSettings.defaults())
    private val detector = FakeOverlayPermissionDetector()
    private lateinit var controller: RecordingOverlayServiceController

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        OverlayStateRegistry.reset()
        controller = RecordingOverlayServiceController()
    }

    @After
    fun tearDown() {
        OverlayStateRegistry.reset()
        Dispatchers.resetMain()
    }

    private fun vm() = SettingsViewModel(
        repo,
        "1.0.0",
        1L,
        accessibilityDetector = com.clawmate.app.accessibility.FakeAccessibilityServiceDetector(),
        overlayDetector = detector,
        overlayServiceController = controller,
    )

    @Test
    fun `no opt-in yields KEEP_STOPPED and never starts`() = runTest {
        val viewModel = vm()
        assertTrue(viewModel.overlayServiceStartStep() is OverlayServiceStartStep.KEEP_STOPPED)
        viewModel.startOverlayService()
        assertTrue("must not start when not READY", controller.starts == 0)
        assertTrue(controller.stops == 0)
    }

    @Test
    fun `opt-in without system grant stays KEEP_STOPPED`() = runTest {
        val viewModel = vm()
        detector.setPermissionGranted(false)
        detector.setRendererConfigured(true)
        viewModel.setOverlayOptIn(true)
        assertTrue(viewModel.overlayServiceStartStep() is OverlayServiceStartStep.KEEP_STOPPED)
        viewModel.startOverlayService()
        assertTrue("must not start with permission missing", controller.starts == 0)
    }

    @Test
    fun `READY gate allows explicit start via the controller`() = runTest {
        val viewModel = vm()
        detector.setPermissionGranted(true)
        detector.setRendererConfigured(true)
        viewModel.setOverlayOptIn(true)
        assertEquals(OverlayGateStatus.READY, viewModel.overlayGate().status)
        assertEquals(OverlayServiceStartStep.START, viewModel.overlayServiceStartStep())
        OverlayStateRegistry.onPermissionGranted()
        viewModel.startOverlayService()
        assertEquals("explicit start must dispatch to the controller exactly once", 1, controller.starts)
        assertTrue("explicit opt-in must be mirrored to the boundary seed", com.clawmate.app.overlay.OverlayCapabilityFlags.overlayOptedIn)
    }

    @Test
    fun `turning opt-in off stops a running service`() = runTest {
        val viewModel = vm()
        OverlayStateRegistry.onPermissionGranted()
        OverlayStateRegistry.onOverlayPresent()
        detector.setPermissionGranted(true)
        detector.setRendererConfigured(true)
        viewModel.setOverlayOptIn(true)
        assertTrue(viewModel.overlayServiceStopRequired() == false)

        viewModel.setOverlayOptIn(false)
        assertEquals(OverlayGateStatus.NOT_CONFIGURED, viewModel.overlayGate().status)
        assertTrue("service still present + no opt-in → stop required", viewModel.overlayServiceStopRequired())
        viewModel.stopOverlayOnOptOut()
        assertTrue("opt-out must issue an explicit stop", controller.stops >= 1)
    }

    @Test
    fun `stop required decision follows the pure policy`() {
        // Pure rule check without the ViewModel.
        assertTrue(OverlayServiceStartPolicy.stopRequired(
            com.clawmate.app.overlay.OverlayGate(optedIn = false, overlayPermissionGranted = true, rendererConfigured = true),
            servicePresent = true,
        ))
        assertFalse(OverlayServiceStartPolicy.stopRequired(
            com.clawmate.app.overlay.OverlayGate(optedIn = true, overlayPermissionGranted = true, rendererConfigured = true),
            servicePresent = true,
        ))
    }

    /** Records start/stop calls and keeps default-present override for tests. */
    private class RecordingOverlayServiceController : OverlayServiceController {
        var starts = 0
        var stops = 0
        override fun startService() { starts++ }
        override fun stopService() { stops++ }
    }
}
