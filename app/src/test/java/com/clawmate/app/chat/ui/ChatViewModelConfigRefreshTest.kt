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
import com.clawmate.app.localmodel.LocalModelRegistry
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
 * must mirror the live [EndpointConfig] for the cloud mode, and the shared
 * [LocalModelRegistry] state for the local mode. The local tab must reflect the
 * REAL on-device model capability (never the cloud endpoint), and the chat VM
 * must only READ the shared local state (never trigger a native load). A blank
 * endpoint / unloaded model keeps the honest not-configured state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelConfigRefreshTest {

    @Before
    fun setUp() {
        // Unconfined so the ViewModel's init load() settles synchronously.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        EndpointConfig.load(Properties()) // clean slate: not configured
        LocalModelRegistry.resetForTest() // clean slate: no shared local state
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Never leak a configured endpoint or a loaded local model into other tests.
        EndpointConfig.load(Properties())
        LocalModelRegistry.resetForTest()
    }

    private fun setPlannerBaseUrl(baseUrl: String) {
        val props = Properties()
        props.setProperty("cloudPlannerBaseUrl", baseUrl)
        EndpointConfig.load(props)
    }

    private fun viewModel(filesDir: String? = null): ChatViewModel =
        ChatViewModel(FakeEmptyRepository(), FakeVoiceController(), filesDir)

    /**
     * Installs a controlled [LocalModelCapability] into the shared registry so a
     * test can deterministically drive the local-mode state, and resets the
     * registry state to the safe default.
     */
    private fun installLocalCapability(
        filesDir: String?,
        scan: (String?) -> List<LocalModelInfo> = { emptyList() },
        nativeSupported: Boolean = false,
        loadResult: () -> LocalModelNative.NativeLoadResult = {
            LocalModelNative.NativeLoadResult.Unsupported(
                LocalModelErrorKind.NATIVE_LIBRARY_MISSING,
                "no lib",
            )
        },
    ) {
        LocalModelRegistry.installForTest(
            LocalModelCapability(
                filesDir = filesDir,
                scan = scan,
                native = object : LocalModelNativeAdapter {
                    override fun supported(): Boolean = nativeSupported
                },
                loadModel = { loadResult() },
            ),
        )
    }

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
    // Mode-aware config state: the LOCAL tab must reflect the shared local
    // model capability, never the cloud endpoint.
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
        // A stub that reports a genuine READY load: the only path to
        // isConfigured=true in local mode. Proves the local gate is driven by
        // the real capability, not a fake. The load is performed through the
        // shared registry (as the Settings panel does), then the chat VM reads
        // the shared state.
        installLocalCapability(
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
            nativeSupported = true,
            loadResult = { LocalModelNative.NativeLoadResult.Success("已就绪（测试桩）") },
        )
        // Simulate the Settings panel performing the explicit load/test.
        LocalModelRegistry.loadAndTest("/fake", "test-model")

        val vm = viewModel(filesDir = "/fake")
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        assertTrue(vm.state.value.isConfigured)
        assertEquals(LocalModelStatus.READY, vm.state.value.localModelStatus!!.status)
    }

    @Test
    fun `a model loaded in settings is visible to the chat tab without re-loading`() {
        // Cross-component visibility: the chat VM only reads the shared registry
        // (no load trigger of its own). A load performed "in Settings" must be
        // visible to the chat tab via the same LocalModelRegistry.state.
        var loadInvocations = 0
        installLocalCapability(
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
            nativeSupported = true,
            loadResult = {
                loadInvocations++
                LocalModelNative.NativeLoadResult.Success("已就绪（测试桩）")
            },
        )
        // Settings panel loads the model.
        LocalModelRegistry.loadAndTest("/fake", "test-model")
        assertEquals(1, loadInvocations)

        // Chat tab enters local mode and refreshes — it must NOT trigger a load.
        val vm = viewModel(filesDir = "/fake")
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        assertEquals("chat refresh must not load", 1, loadInvocations)
        assertTrue(vm.state.value.isConfigured)
        assertEquals(LocalModelStatus.READY, vm.state.value.localModelStatus!!.status)
    }

    @Test
    fun `local refresh alone never loads a model`() {
        // The chat tab's refreshLocalModel is read-only: even with a complete,
        // loadable model on disk, merely refreshing (no Settings load) must not
        // invoke the native load, and must report NOT_CONFIGURED (not loaded).
        var loadInvocations = 0
        installLocalCapability(
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
            nativeSupported = true,
            loadResult = {
                loadInvocations++
                LocalModelNative.NativeLoadResult.Success("已就绪（测试桩）")
            },
        )
        val vm = viewModel(filesDir = "/fake")
        vm.setModeForTest(ChatMode.LOCAL_INFERENCE)
        vm.refreshConfigState()

        assertEquals("refresh must not load", 0, loadInvocations)
        // A complete model that is not yet loaded is NOT_CONFIGURED (honest), not READY.
        assertFalse(vm.state.value.isConfigured)
        assertEquals(LocalModelStatus.NOT_CONFIGURED, vm.state.value.localModelStatus!!.status)
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
