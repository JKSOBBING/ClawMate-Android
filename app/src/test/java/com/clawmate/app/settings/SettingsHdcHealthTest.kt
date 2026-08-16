package com.clawmate.app.settings

import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.ui.SettingsViewModel
import com.clawmate.app.task.TaskComponent
import com.clawmate.app.task.hdc.HdcBridgeClient
import com.clawmate.app.task.hdc.HdcBridgeResult
import com.clawmate.app.task.hdc.HdcBridgeHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Settings HDC bridge health flow: the health check only ever runs on an
 * explicit click, app launch performs no network, no connected state is
 * claimed until a live ok reply, and a verified bridge registers a configured
 * adapter while a failure keeps it disabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsHdcHealthTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeSettingsRepository(AppSettings.defaults())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        TaskComponent.setDeviceAdapter(null)
    }

    @After
    fun tearDown() {
        TaskComponent.setDeviceAdapter(null)
        Dispatchers.resetMain()
    }

    private fun okHealthClient() = FakeClient(
        health = HdcBridgeResult.Health(
            HdcBridgeHealth(status = "ok", hdcConnected = true, fportReady = true, rportReady = true)
        )
    )

    @Test
    fun `app launch default state has no test running and not connected`() = runTest {
        val vm = SettingsViewModel(repo, "1.0.0", 1L)
        assertFalse(vm.state.value.hdcTesting)
        assertFalse(vm.state.value.hdcTestConnected)
        assertNull(vm.state.value.hdcTestMessage)
        assertFalse(TaskComponent.currentAdapter().isConfigured)
    }

    @Test
    fun `test with no configured url reports an error and never registers adapter`() = runTest {
        val vm = SettingsViewModel(repo, "1.0.0", 1L, hdcClientFactory = { _ -> okHealthClient() })
        vm.testHdcHealth()
        val state = vm.state.value
        assertTrue(state.hdcTestMessage != null && state.hdcTestMessage!!.contains("未配置"))
        assertFalse(state.hdcTestConnected)
        assertFalse(TaskComponent.currentAdapter().isConfigured)
    }

    @Test
    fun `successful live health marks connected and registers configured adapter`() = runTest {
        val url = "http://192.168.1.30:9124"
        val vm = SettingsViewModel(
            repo,
            "1.0.0",
            1L,
            hdcClientFactory = { u -> FakeClient(health = okHealthClient().health) },
        )
        vm.updateDraft { it.copy(hdcServerUrl = url) }
        vm.testHdcHealth()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.hdcTesting)
        assertTrue(state.hdcTestConnected)
        assertTrue(TaskComponent.currentAdapter().isConfigured)
    }

    @Test
    fun `failed live health keeps not configured and no connected claim`() = runTest {
        val vm = SettingsViewModel(
            repo,
            "1.0.0",
            1L,
            hdcClientFactory = { _ ->
                FakeClient(health = HdcBridgeResult.Health(HdcBridgeHealth(status = "error", message = "hdc not connected")))
            },
        )
        vm.updateDraft { it.copy(hdcServerUrl = "http://192.168.1.30:9124") }
        vm.testHdcHealth()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.hdcTesting)
        assertFalse(state.hdcTestConnected)
        assertTrue(state.hdcTestMessage != null)
        assertFalse(TaskComponent.currentAdapter().isConfigured)
    }

    @Test
    fun `transport failure is surfaced without connecting`() = runTest {
        val vm = SettingsViewModel(
            repo,
            "1.0.0",
            1L,
            hdcClientFactory = { _ -> FakeClient(health = HdcBridgeResult.Error(com.clawmate.app.task.hdc.HdcBridgeErrorKind.TIMEOUT, "timed out")) },
        )
        vm.updateDraft { it.copy(hdcServerUrl = "http://192.168.1.30:9124") }
        vm.testHdcHealth()
        advanceUntilIdle()
        assertFalse(vm.state.value.hdcTestConnected)
        assertTrue(vm.state.value.hdcTestMessage != null)
        assertFalse(TaskComponent.currentAdapter().isConfigured)
    }

    /** Minimal stubbed client so the VM health test performs no real network. */
    private class FakeClient(var health: HdcBridgeResult) : HdcBridgeClient {
        override suspend fun health(): HdcBridgeResult = health
        override suspend fun workflow(action: String, payload: Map<String, Any?>): HdcBridgeResult =
            HdcBridgeResult.Error(com.clawmate.app.task.hdc.HdcBridgeErrorKind.UNKNOWN, "not used")
    }
}
