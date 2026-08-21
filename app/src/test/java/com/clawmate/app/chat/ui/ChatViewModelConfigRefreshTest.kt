package com.clawmate.app.chat.ui

import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.data.ChatSendEvent
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.voice.ChatVoiceControllerApi
import com.clawmate.app.chat.voice.VoiceActionResult
import com.clawmate.app.chat.voice.VoiceControllerState
import com.clawmate.app.config.EndpointConfig
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
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

    private fun viewModel(): ChatViewModel =
        ChatViewModel(FakeEmptyRepository(), FakeVoiceController())

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
