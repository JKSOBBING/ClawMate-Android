package com.clawmate.app.settings

import com.clawmate.app.accessibility.AccessibilityExecutionOutcome
import com.clawmate.app.accessibility.FakeAccessibilityServiceDetector
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Device-free ViewModel tests for the Phase 22 safe semantic executor demo/test
 * boundary in Settings: the boundary reflects the live opt-in + observed system
 * service grant, and the demo dispatch reports an honest preflight outcome that
 * never fabricates SUCCESS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSafeAccessibilityExecutorTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeSettingsRepository(AppSettings.defaults())
    private val detector = FakeAccessibilityServiceDetector()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        com.clawmate.app.accessibility.AccessibilityStateRegistry.reset()
    }

    @After
    fun tearDown() {
        com.clawmate.app.accessibility.AccessibilityStateRegistry.reset()
        Dispatchers.resetMain()
    }

    private fun vm() = SettingsViewModel(
        repo,
        "1.0.0",
        1L,
        accessibilityDetector = detector,
        overlayDetector = com.clawmate.app.overlay.FakeOverlayPermissionDetector(),
    )

    @Test
    fun `boundary starts NOT_CONFIGURED without opt-in`() = runTest {
        val viewModel = vm()
        val boundary = viewModel.safeAccessibilityExecutorBoundary()
        assertEquals("NOT_CONFIGURED", boundary.readinessLabel)
        assertTrue(boundary.allowlist.contains("click_text"))
        assertTrue(boundary.allowlist.contains("global_home"))
        assertEquals(setOf("com.clawmate.app"), boundary.packageAllowlist)
    }

    @Test
    fun `demo dispatch reports NOT_CONFIGURED without opt-in and never performs`() = runTest {
        val viewModel = vm()
        val result = viewModel.safeAccessibilityDemoDispatch("back", emptyMap())
        assertTrue(result is AccessibilityExecutionOutcome.NotConfigured)
    }

    @Test
    fun `opt-in without service report BLOCKED_SERVICE and honest demo result`() = runTest {
        val viewModel = vm()
        viewModel.setAccessibilityOptIn(true)
        detector.setServiceEnabled(false)
        assertEquals("BLOCKED_SERVICE", viewModel.safeAccessibilityExecutorBoundary().readinessLabel)
        val result = viewModel.safeAccessibilityDemoDispatch("back", emptyMap())
        assertTrue(result is AccessibilityExecutionOutcome.BlockedService)
    }

    @Test
    fun `opt-in with service but non-allowlisted foreground is BLOCKED_PACKAGE`() = runTest {
        val viewModel = vm()
        viewModel.setAccessibilityOptIn(true)
        detector.setServiceEnabled(true)
        com.clawmate.app.accessibility.AccessibilityStateRegistry.onServiceConnected()
        com.clawmate.app.accessibility.AccessibilityStateRegistry.onWindowPackage("com.other.app")
        assertEquals("BLOCKED_PACKAGE", viewModel.safeAccessibilityExecutorBoundary().readinessLabel)
        val result = viewModel.safeAccessibilityDemoDispatch("global_home", emptyMap())
        assertTrue(result is AccessibilityExecutionOutcome.BlockedPackage)
    }

    @Test
    fun `ready boundary with a demo seam never reports a fake SUCCESS`() = runTest {
        val viewModel = vm()
        viewModel.setAccessibilityOptIn(true)
        detector.setServiceEnabled(true)
        com.clawmate.app.accessibility.AccessibilityStateRegistry.onServiceConnected()
        com.clawmate.app.accessibility.AccessibilityStateRegistry.onWindowPackage("com.clawmate.app")
        assertEquals("READY", viewModel.safeAccessibilityExecutorBoundary().readinessLabel)
        // The demo seam never performs, so even on a ready gate the result is a
        // truthful no-perform notice, not a fabricated SUCCESS.
        val result = viewModel.safeAccessibilityDemoDispatch("back", emptyMap())
        assertTrue(result !is AccessibilityExecutionOutcome.Success)
    }
}
