package com.clawmate.app.chat.ui

import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.data.ChatSendEvent
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.voice.ChatVoiceControllerApi
import com.clawmate.app.chat.voice.VoiceActionResult
import com.clawmate.app.chat.voice.VoiceControllerState
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.localmodel.LocalModelCapability
import com.clawmate.app.localmodel.LocalModelErrorKind
import com.clawmate.app.localmodel.LocalModelInfo
import com.clawmate.app.localmodel.LocalModelNative
import com.clawmate.app.localmodel.LocalModelNativeAdapter
import com.clawmate.app.localmodel.LocalModelStatus
import com.clawmate.app.localmodel.LocalModelState
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Config-state tests for [ChatViewModel]: [ChatViewModel.refreshConfigState]
 * must mirror the live [EndpointConfig] so that an endpoint saved in the
 * Settings center (applied to the runtime config on save) is picked up when
 * the chat tab is re-entered — no app restart required. A blank endpoint keeps
 * the honest not-configured state (the screen shows the setup pane, never a
 * fake input bar).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConfigRefreshTest {

    @Before
    fun setUp() {
        // Unconfined so the ViewModel's init load() settles synchronously.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        EndpointConfig.load(Properties()) // clean slate: not configured
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Never leak a configured endpoint into other tests.
        EndpointConfig.load(Properties())
    }

    private fun setPlannerBaseUrl(baseUrl: String) {
        val props = Properties()
        props.setProperty("cloudPlannerBaseUrl", baseUrl)
        EndpointConfig.load(props)
    }

    private fun viewModel(
        filesDir: String? = null,
        capability: LocalModelCapability = LocalModelCapability(filesDir),
    ): ChatViewModel =
        ChatViewModel(FakeEmptyRepository(), FakeVoiceController(), filesDir, capability)

    /** Puts the active session into a given mode without a real session store. */
    private fun ChatViewModel.setModeForTest(mode: String) {
        val field = ChatViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(this) as MutableStateFlow<ChatUiState>
        flow.update { it.copy(mode = mode) }
    }

    @Test
    fun `load reports not configured when the endpoint is blank`() {
        val vm = viewModel()
        assertFalse(vm.state.value.isConfigured)
    }

    @Test
    fun `refreshConfigState picks up an endpoint saved through EndpointConfig`() {
        val vm = viewModel()
        assertFalse(vm.state.value.isConfigured)

        // Mirrors the Settings-center save: EndpointBridge.applyToRuntime pushes
        // the normalized value into the runtime EndpointConfig.
        setPlannerBaseUrl("https://planner.example.com/v1")
        vm.refreshConfigState()
        assertTrue(vm.state.value.isConfigured)
    }

    @Test
    fun `refreshConfigState reverts to not configured when the endpoint is cleared`() {
        setPlannerBaseUrl("https://planner.example.com/v1")
        val vm = viewModel()
        assertTrue(vm.state.value.isConfigured)

        EndpointConfig.load(Properties())
        vm.refreshConfigState()
        assertFalse(vm.state.value.isConfigured)
    }

    // ------------------------------------------------------------------
    // Mode-aware config state: the LOCAL tab must reflect the real
    // on-device model capability, never the cloud endpoint.
    // ------------------------------------------------------------------

    @Test
    fun `local mode with no model directory is not configured and exposes honest state`() {
        // No filesDir => refresh() returns NOT_CONFIGURED (the stub is never READY).
        val vm = viewModel(filesDir = null)
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        assertFalse(vm.state.value.isConfigured)
        val local = vm.state.value.localModelStatus
        assertNotNull(local)
        assertEquals(LocalModelStatus.NOT_CONFIGURED, local!!.status)
        assertEquals(LocalModelErrorKind.NOT_CONFIGURED, local!!.errorKind)
    }

    @Test
    fun `local mode uses the local capability, not the cloud endpoint`() {
        // Cloud endpoint IS configured, but local must still be not-ready.
        setPlannerBaseUrl("https://planner.example.com/v1")
        val vm = viewModel(filesDir = null)
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        // The bug this fixes: switching to 本地 while the cloud is configured
        // must NOT report isConfigured=true via the cloud check.
        assertFalse(vm.state.value.isConfigured)
        assertNotNull(vm.state.value.localModelStatus)
    }

    @Test
    fun `local mode becomes configured only when a real model is READY`() {
        // A stub that reports a genuine READY load: the only path to isConfigured=true
        // in local mode. Proves the local gate is driven by the capability, not a fake.
        val ready = LocalModelCapability(
            filesDir = "/fake",
            scan = {
                listOf(
                    LocalModelInfo(
                        id = "test-model",
                        label = "测试模型",
                        path = "/fake/model",
                        ready = true,
                        hasConfig = true,
                        hasWeightFile = true,
                        statusText = "可加载",
                    )
                )
            },
            native = object : LocalModelNativeAdapter {
                override fun supported() = true
            },
            loadModel = { LocalModelNative.NativeLoadResult.Success("已就绪（测试桩）") }
        )
        val vm = viewModel(filesDir = "/fake", capability = ready)
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        assertTrue(vm.state.value.isConfigured)
        assertEquals(LocalModelStatus.READY, vm.state.value.localModelStatus!!.status)
    }

    @Test
    fun `cloud mode is unaffected by the local capability`() {
        // Cloud configured; even with a blank local dir, CLOUD_AGENT reports configured.
        setPlannerBaseUrl("https://planner.example.com/v1")
        val vm = viewModel(filesDir = null)
        vm.setModeForTest(ChatMode.CLOUD_AGENT)
        vm.refreshConfigState()

        assertTrue(vm.state.value.isConfigured)
    }

    /** No sessions, no model output — nothing is ever faked. */
    private class FakeEmptyRepository : ChatRepository {
        override suspend fun sessions(): List<ChatSession> = emptyList()
        override suspend fun session(sessionId: String): ChatSession? = null
        override suspend fun ensureActiveSession(): String = "session-fake"
        override fun activeSessionId(): String? = null
        override suspend fun createSession(mode: String?): ChatSession =
            throw UnsupportedOperationException("not exercised")
        override suspend fun selectSession(sessionId: String): ChatSession? = null
        override suspend fun sendMessage(userText: String): Flow<ChatSendEvent> =
            throw UnsupportedOperationException("not exercised")
        override suspend fun sendAgentMessage(userText: String): Flow<ChatSendEvent> =
            throw UnsupportedOperationException("not exercised")
        override suspend fun deleteSession(sessionId: String) = Unit
    }

    /** Deterministic no-op voice surface (the real controller needs a Context). */
    private class FakeVoiceController : ChatVoiceControllerApi {
        override val state = MutableStateFlow(VoiceControllerState())
        override fun setHost(
            activeInputProvider: () -> String,
            setInputSink: (String) -> Unit,
            isBusyProvider: () -> Boolean,
        ) = Unit
        override fun toggleVoiceInput(): VoiceActionResult =
            VoiceActionResult.Unavailable("测试桩：不可用")
        override fun stopVoiceInput(): VoiceActionResult = VoiceActionResult.Ok
        override fun speakMessage(messageId: String, text: String): VoiceActionResult =
            VoiceActionResult.Ok
        override fun replayMessage(messageId: String, text: String): VoiceActionResult =
            VoiceActionResult.Ok
        override fun stopPlayback() = Unit
        override fun dispose() = Unit
    }
}
