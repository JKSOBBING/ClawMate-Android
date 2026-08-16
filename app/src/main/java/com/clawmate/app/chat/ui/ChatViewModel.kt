package com.clawmate.app.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole
import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.data.ChatSendEvent
import com.clawmate.app.chat.voice.ChatVoiceController
import com.clawmate.app.chat.voice.VoiceActionResult
import com.clawmate.app.chat.voice.VoiceControllerState
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the chat screen. */
data class ChatUiState(
    val sessionId: String = "",
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isConfigured: Boolean = true,
    val error: String? = null,          // user-facing error banner text when send fails
    /** Latest tool-execution trace line shown while the agent reasons. */
    val activeToolTrace: String? = null,
    /** True while the agent loop is running (tool executing / reasoning). */
    val agentRunning: Boolean = false,
)

/**
 * ViewModel for a single chat session. Owns the active session, message list,
 * input text, and the sending coroutine. Receives incremental assistant deltas
 * from [ChatRepository.sendMessage] and reflects them live in [state].
 *
 * Missing-config and error states are surfaced via [ChatUiState.isConfigured] /
 * [ChatUiState.error] so the screen can render the right controls (per AGENTS.md,
 * "do not silently drop" — a missing cloud endpoint is shown, not faked).
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val voiceController: ChatVoiceController,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var sendingJob: kotlinx.coroutines.Job? = null

    /** Active session id; a session is created lazily on first send/persist. */
    val sessionId: String get() = _state.value.sessionId

    init {
        voiceController.setHost(
            activeInputProvider = { _state.value.inputText },
            setInputSink = { text -> _state.update { it.copy(inputText = text) } },
            isBusyProvider = { _state.value.isSending || _state.value.agentRunning },
        )
        load()
    }

    override fun onCleared() {
        voiceController.dispose()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // Voice interaction surface for [ChatScreen].
    // ------------------------------------------------------------------

    /** Voice session + capability state driven by [ChatVoiceController]. */
    val voiceState: StateFlow<VoiceControllerState> = voiceController.state

    /** One-shot human-readable voice badge derived from the controller session. */
    val voiceBadge: kotlinx.coroutines.flow.Flow<String?> = voiceController.state.map { state ->
        when (state.session.status) {
            com.clawmate.app.chat.voice.VoiceInteractionStatus.LISTENING -> "正在聆听..."
            com.clawmate.app.chat.voice.VoiceInteractionStatus.TRANSCRIBING -> "正在转写..."
            com.clawmate.app.chat.voice.VoiceInteractionStatus.SPEAKING -> "正在朗读..."
            com.clawmate.app.chat.voice.VoiceInteractionStatus.PAUSED -> "已暂停朗读"
            com.clawmate.app.chat.voice.VoiceInteractionStatus.ERROR ->
                state.session.error.ifEmpty { "语音出错" }
            else -> null
        }
    }

    fun beginVoiceInput() {
        val result = voiceController.toggleVoiceInput()
        if (result is VoiceActionResult.PermissionRequired) {
            _state.update { it.copy(error = null) }
        }
    }

    fun stopVoiceInput() {
        voiceController.stopVoiceInput()
    }

    fun speakMessage(messageId: String, text: String) {
        voiceController.speakMessage(messageId, text)
    }

    fun replayMessage(messageId: String, text: String) {
        voiceController.replayMessage(messageId, text)
    }

    fun stopVoicePlayback() {
        voiceController.stopPlayback()
    }


    /** Loads session history and marks whether the cloud model is configured. */
    fun load() {
        viewModelScope.launch {
            val configured = com.clawmate.app.chat.cloud.ChatServerConfig
                .isConfigured(com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl)
            _state.update { it.copy(isConfigured = configured) }

            val sessions = runCatching { repository.sessions() }.getOrDefault(emptyList())
            val active = sessions.firstOrNull()
            if (active != null) {
                _state.update { it.copy(sessionId = active.id, title = active.displayTitle, messages = active.messages) }
            }
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * Sends the current input through [ChatRepository.sendMessage], streaming
     * the assistant reply into [ChatUiState.messages] as it arrives.
     */
    fun send() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty() || _state.value.isSending) return

        sendingJob?.cancel()
        sendingJob = viewModelScope.launch {
            _state.update { it.copy(isSending = true, error = null, agentRunning = true) }
            val sessionId = repository.ensureActiveSession()
            com.clawmate.app.log.AppLogger.cloud(
                com.clawmate.app.log.domain.LogLevel.INFO,
                "Chat",
                "用户消息已发送 session=$sessionId",
            )
            try {
                repository.sendAgentMessage(text).collect { event ->
                    when (event) {
                        is ChatSendEvent.UserMessageAccepted -> {
                            _state.update {
                                it.copy(
                                    sessionId = sessionId,
                                    messages = it.messages + event.message,
                                    inputText = "",
                                    isConfigured = true,
                                )
                            }
                        }
                        is ChatSendEvent.AssistantDelta -> {
                            _state.update { current ->
                                val streaming = current.messages
                                    .toMutableList()
                                    .also { list ->
                                        val last = list.lastOrNull()
                                        if (last != null && last.isStreaming && last.role == ChatMessageRole.ASSISTANT) {
                                            val merged = last.copy(
                                                content = last.content + event.delta,
                                                streamingVersion = last.streamingVersion + 1,
                                            )
                                            list[list.size - 1] = merged
                                        } else {
                                            list += ChatMessage(
                                                id = "msg-" + UUID.randomUUID(),
                                                role = ChatMessageRole.ASSISTANT,
                                                kind = ChatMessageKind.CHAT,
                                                content = event.delta,
                                                trace = emptyList(),
                                                collapsed = false,
                                                createdAt = System.currentTimeMillis(),
                                                streamingVersion = 1,
                                            )
                                        }
                                    }
                                current.copy(messages = streaming)
                            }
                        }
                        is ChatSendEvent.ToolExecuted -> {
                            _state.update { current ->
                                val streaming = current.messages
                                    .toMutableList()
                                    .also { list ->
                                        val idx = list.indexOfLast { it.isStreaming || it.role == ChatMessageRole.ASSISTANT }
                                        if (idx >= 0 && (list[idx].role == ChatMessageRole.ASSISTANT || list[idx].isStreaming)) {
                                            val base = list[idx]
                                            val trace = base.trace + event.traceLine
                                            val isStreamer = list[idx].isStreaming
                                            list[idx] = base.copy(trace = trace, streamingVersion = if (isStreamer) base.streamingVersion else 0)
                                        }
                                    }
                                current.copy(messages = streaming, activeToolTrace = event.traceLine)
                            }
                        }
                        is ChatSendEvent.Completed -> {
                            com.clawmate.app.log.AppLogger.cloud(
                                com.clawmate.app.log.domain.LogLevel.INFO,
                                "Chat",
                                "回复完成 session=$sessionId",
                            )
                            _state.update { current ->
                                val finalized = current.messages
                                    .toMutableList()
                                    .also { list ->
                                        val idx = list.indexOfLast { it.isStreaming && it.role == ChatMessageRole.ASSISTANT }
                                        if (idx >= 0) {
                                            list[idx] = list[idx].copy(streamingVersion = 0)
                                        }
                                    }
                                current.copy(
                                    messages = finalized,
                                    isSending = false,
                                    agentRunning = false,
                                    activeToolTrace = null,
                                )
                            }
                        }
                    }
                }
            } catch (e: ChatException) {
                // Roll back the tentative assistant bubble (if any) and show the error.
                com.clawmate.app.log.AppLogger.cloud(
                    com.clawmate.app.log.domain.LogLevel.ERROR,
                    "Chat",
                    "对话失败: ${e.userMessage}",
                )
                _state.update { current ->
                    current.copy(
                        isSending = false,
                        agentRunning = false,
                        activeToolTrace = null,
                        isConfigured = current.isConfigured && e.kind != ChatErrorKind.CONFIG,
                        error = e.userMessage,
                        messages = current.messages.filterNot {
                            it.isStreaming && it.role == ChatMessageRole.ASSISTANT
                        },
                    )
                }
            } catch (e: com.clawmate.app.chat.agent.AgentException) {
                _state.update {
                    it.copy(
                        isSending = false,
                        agentRunning = false,
                        activeToolTrace = null,
                        error = "智能体执行失败：${e.message}",
                        messages = it.messages.filterNot { msg ->
                            msg.isStreaming && msg.role == ChatMessageRole.ASSISTANT
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        agentRunning = false,
                        activeToolTrace = null,
                        error = "发送失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    /** Cancels an in-flight send. */
    fun cancelSend() {
        sendingJob?.cancel()
        _state.update { it.copy(isSending = false, agentRunning = false, activeToolTrace = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
