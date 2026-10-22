package com.clawmate.app.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.model.AttachmentSource
import com.clawmate.app.chat.model.AttachmentUploadCapability
import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.chat.model.PendingChatAttachment
import com.clawmate.app.chat.model.chatAttachmentSendPlan
import com.clawmate.app.chat.model.toPendingAttachments
import com.clawmate.app.chat.data.ChatRepository
import com.clawmate.app.chat.data.ChatSendEvent
import com.clawmate.app.chat.voice.ChatVoiceControllerApi
import com.clawmate.app.chat.voice.VoiceActionResult
import com.clawmate.app.chat.voice.VoiceControllerState
import com.clawmate.app.localmodel.LocalModelCapability
import com.clawmate.app.localmodel.LocalModelStatus
import com.clawmate.app.localmodel.LocalModelState
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ChatImageSendConsent(val text: String, val attachments: List<PendingChatAttachment>,
    val sessionId: String, val destination: com.clawmate.app.chat.data.ChatImageUploadInfo)

/** UI state for the chat screen. */
data class ChatUiState(
    val sessionId: String = "",
    val title: String = "",
    val mode: String = ChatMode.CLOUD_AGENT,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isConfigured: Boolean = true,
    val error: String? = null,          // user-facing error banner text when send fails
    /** Latest tool-execution trace line shown while the agent reasons. */
    val activeToolTrace: String? = null,
    /** True while the agent loop is running (tool executing / reasoning). */
    val agentRunning: Boolean = false,
    /** All persisted sessions (most recently updated first) for the list/drawer. */
    val sessions: List<ChatSession> = emptyList(),
    /** Whether the session list / drawer is currently open. */
    val drawerOpen: Boolean = false,
    /**
     * Pending image attachments for the current composer turn (in-memory / UI
     * projection only). The confirmed send prepares pixels and persists only
     * app-private prepared-file references; source URIs never enter the wire.
     */
    val pendingAttachments: List<PendingChatAttachment> = emptyList(),
    /**
     * Actionable attachment preparation/configuration/capability feedback.
     */
    val attachmentNote: String? = null,
    val imageConsent: ChatImageSendConsent? = null,
    /**
     * The real on-device local-model capability state for the LOCAL_INFERENCE
     * mode. Drives the honest "本地推理" not-ready pane (status + message)
     * instead of the cloud-configured copy. Never fabricated: it is read from
     * [LocalModelCapability] (disk scan + native adapter), which only reports
     * READY on a genuine native load.
     */
    val localModelStatus: LocalModelState? = null,
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
    private val voiceController: ChatVoiceControllerApi,
    /** App-private data dir used to query the shared local-model registry. */
    private val filesDir: String? = null,
    private val prepareImage: (suspend (String) -> com.clawmate.app.image.PreparedChatImage)? = null,
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


    /**
     * Re-checks the **current mode's** backend and updates
     * [ChatUiState.isConfigured]. The screen calls this on every tab entry and
     * on mode switch, so a backend change saved in the Settings center (applied
     * to the runtime [com.clawmate.app.config.EndpointConfig] on save) takes
     * effect on return to the chat page — no app restart is required.
     *
     * **Mode-aware** (fixes the "本地" tab wrongly showing "云端模型尚未配置"):
     *  - `CLOUD_AGENT`      → usable when the cloud planner endpoint is configured.
     *  - `LOCAL_INFERENCE`  → usable only when the real on-device model is READY
     *    (per [LocalModelCapability]; the JNI stub honestly reports
     *    NOT_CONFIGURED/UNSUPPORTED, never READY, until a genuine native load).
     */
    fun refreshConfigState() {
        val mode = _state.value.mode
        when (mode) {
            ChatMode.LOCAL_INFERENCE -> refreshLocalModel()
            else -> {
                val configured = com.clawmate.app.chat.cloud.ChatServerConfig
                    .isConfigured(com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl)
                _state.update { it.copy(isConfigured = configured) }
            }
        }
    }

    /**
     * Reflects the shared local-model state in both [ChatUiState.isConfigured]
     * (READY only) and [ChatUiState.localModelStatus] (the honest state + message
     * the LOCAL pane renders).
     *
     * **Read-only by design.** It only calls [LocalModelRegistry.refresh] (a disk
     * scan merged with the shared loaded state) and never triggers a native load.
     * Loading is a deliberate, user-driven action that belongs to the Settings
     * "本地模型" panel (its "加载/测试" button), which publishes the result to the
     * same shared [LocalModelRegistry.state] this reads. So a model loaded in
     * Settings is now visible here without the chat tab re-loading it — and
     * tapping 本地 in the chat tab has no load side effect. The honest
     * consequence: until a model is actually loaded, the local tab reports
     * NOT_CONFIGURED / UNSUPPORTED and is not usable — never a faked READY.
     */
    private fun refreshLocalModel() {
        val scanned = runCatching {
            com.clawmate.app.localmodel.LocalModelRegistry.refresh(filesDir)
            com.clawmate.app.localmodel.LocalModelRegistry.state.value
        }.getOrNull()
            ?: LocalModelState(
                status = LocalModelStatus.NOT_CONFIGURED,
                errorKind = com.clawmate.app.localmodel.LocalModelErrorKind.NOT_CONFIGURED,
                message = "无法读取本地模型状态",
            )
        _state.update { it.copy(isConfigured = scanned.isReady, localModelStatus = scanned) }
    }

    /** Loads the session list and restores the active (most recently selected) session. */
    fun load() {
        viewModelScope.launch {
            refreshConfigState()

            val sessions = runCatching { repository.sessions() }.getOrDefault(emptyList())
            _state.update { it.copy(sessions = sessions) }

            // Restart recovery: pick the most recently updated (last selected) session.
            val active = runCatching { repository.session(repository.activeSessionId().orEmpty()) }
                .getOrNull()
                ?: sessions.firstOrNull()
            if (active != null) {
                _state.update {
                    it.copy(
                        sessionId = active.id,
                        title = active.displayTitle,
                        mode = ChatMode.normalize(active.mode),
                        messages = active.messages,
                    )
                }
            }
        }
    }

    fun setDrawerOpen(open: Boolean) {
        _state.update { it.copy(drawerOpen = open) }
    }

    /** Creates a brand-new empty session and switches to it. */
    fun newSession(mode: String? = null) {
        if (_state.value.isSending) return
        viewModelScope.launch {
            val session = runCatching { repository.createSession(mode) }.getOrElse { error ->
                _state.update { it.copy(error = "新建会话失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            _state.update {
                it.copy(
                    sessionId = session.id,
                    title = session.displayTitle,
                    mode = ChatMode.normalize(session.mode),
                    messages = emptyList(),
                    inputText = "",
                    error = null,
                    activeToolTrace = null,
                    pendingAttachments = emptyList(),
                    attachmentNote = null,
                    imageConsent = null,
                )
            }
            // Mode may have changed (the top-bar 云端/本地 switch calls this with a
            // different mode); re-evaluate the *new* mode's backend so the pane
            // reflects local vs cloud readiness, not the stale prior value.
            refreshConfigState()
            refreshSessions()
        }
    }

    /** Switches to an existing session, restoring its persisted message history. */
    fun selectSession(sessionId: String) {
        if (_state.value.isSending) return
        viewModelScope.launch {
            val session = runCatching { repository.selectSession(sessionId) }.getOrElse { error ->
                _state.update { it.copy(error = "切换会话失败：${error.message ?: "未知错误"}") }
                return@launch
            }
            if (session != null) {
                _state.update {
                    it.copy(
                        sessionId = session.id,
                        title = session.displayTitle,
                        mode = ChatMode.normalize(session.mode),
                        messages = session.messages,
                        inputText = "",
                        error = null,
                        activeToolTrace = null,
                        pendingAttachments = emptyList(),
                        attachmentNote = null,
                        imageConsent = null,
                    )
                }
            } else {
                _state.update { it.copy(error = "会话不存在或已被删除") }
            }
            refreshSessions()
        }
    }

    /**
     * Deletes a session. Deleting the currently active session clears its
     * active marker so the next send restores the most recently updated
     * remaining session (or starts a fresh empty one). A non-active deletion
     * leaves the active session untouched. Deleting the current session
     * switches the UI to the next available session (or an empty new one).
     */
    fun deleteSession(sessionId: String) {
        if (_state.value.isSending) {
            _state.update { it.copy(error = "请先停止当前回复，再删除会话。") }
            return
        }
        viewModelScope.launch {
            val wasActive = sessionId == _state.value.sessionId
            val deleted = runCatching { repository.deleteSession(sessionId) }
            if (deleted.isFailure) {
                _state.update {
                    it.copy(error = "删除会话失败：${deleted.exceptionOrNull()?.message ?: "未知错误"}")
                }
                return@launch
            }
            if (wasActive) {
                // Select the most recently updated remaining session, or clear the
                // view to an empty state when none remain (a new one is created on
                // the next send).
                val session = runCatching {
                    repository.session(repository.activeSessionId().orEmpty())
                }.getOrNull()
                if (session != null) {
                    _state.update {
                        it.copy(
                            sessionId = session.id,
                            title = session.displayTitle,
                            mode = ChatMode.normalize(session.mode),
                            messages = session.messages,
                            error = null,
                            activeToolTrace = null,
                            pendingAttachments = emptyList(),
                            attachmentNote = null,
                            imageConsent = null,
                        )
                    }
                    refreshSessions()
                } else {
                    _state.update {
                        it.copy(
                            sessionId = "",
                            title = "",
                            messages = emptyList(),
                            error = null,
                            activeToolTrace = null,
                            pendingAttachments = emptyList(),
                            attachmentNote = null,
                            imageConsent = null,
                        )
                    }
                    refreshSessions()
                }
            } else {
                refreshSessions()
            }
        }
    }

    private suspend fun refreshSessions() {
        val sessions = runCatching { repository.sessions() }.getOrDefault(_state.value.sessions)
        _state.update { it.copy(sessions = sessions) }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text, imageConsent = null) }
    }

    // ------------------------------------------------------------------
    // Phase 45 — chat image attachment selection (in-memory/UI projection).
    // ------------------------------------------------------------------

    /**
     * Adds normalized image attachments selected by the user (content URIs held
     * as opaque references only). User-tap triggered by the UI's attach entry;
     * nothing is uploaded here. Empty / normalised-to-empty results are no-ops.
     */
    fun addPendingAttachments(uris: List<String>, source: AttachmentSource) {
        val existing = _state.value.pendingAttachments
        val additions = toPendingAttachments(
            ids = uris.indices.map { "pending-${System.nanoTime()}-$it" },
            uris = uris,
            source = source,
        ).filter { newA ->
            existing.none { (it.reference.toString() == newA.reference.toString()) }
        }
        if (additions.isEmpty()) return
        _state.update {
            it.copy(
                pendingAttachments = (it.pendingAttachments + additions)
                    .take(com.clawmate.app.chat.model.CHAT_IMAGE_ATTACHMENT_MAX_COUNT),
                attachmentNote = null,
            )
        }
    }

    /** Removes one pending attachment by id (user dismisses a selected image). */
    fun removePendingAttachment(id: String) {
        _state.update {
            it.copy(
                pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == id },
                attachmentNote = null,
            )
        }
    }

    /** Clears all pending attachments (e.g. when starting/selecting a session). */
    fun clearPendingAttachments() {
        _state.update { it.copy(pendingAttachments = emptyList(), attachmentNote = null, imageConsent = null) }
    }

    /** Dismisses the attachment send-status note. */
    fun clearAttachmentNote() {
        _state.update { it.copy(attachmentNote = null) }
    }

    /** Surfaces an honest note (e.g. photo-picker unavailable) without a send. */
    fun showAttachmentNote(message: String) {
        _state.update { it.copy(attachmentNote = message) }
    }

    /**
     * Sends the current input through [ChatRepository.sendMessage], streaming
     * the assistant reply into [ChatUiState.messages] as it arrives.
     *
     * Images use explicit consent and ordinary multimodal streaming chat when
     * the adapter is configured. Unavailable adapters keep selections pending;
     * no selected image may silently fall through to text-only or task execution.
     */
    fun send() {
        if (_state.value.isSending || _state.value.imageConsent != null) return
        val text = _state.value.inputText.trim()
        val attachments = _state.value.pendingAttachments
        if (attachments.isNotEmpty() && prepareImage != null) {
            if (_state.value.mode == ChatMode.LOCAL_INFERENCE) {
                _state.update { it.copy(attachmentNote = "本地推理尚不支持图片输入，请切换到云端聊天。") }
                return
            }
            if (attachments.size > com.clawmate.app.chat.cloud.ChatImageBounds.MAX_IMAGES) {
                _state.update { it.copy(attachmentNote = "单次最多发送4张图片，请先移除多余图片。") }
                return
            }
            val destination = repository.imageUploadInfo()
            if (destination == null) {
                _state.update { it.copy(attachmentNote = "请先在设置中保存支持图片输入的云端聊天地址和模型，图片仍保留在待发送列表。") }
                return
            }
            _state.update { it.copy(imageConsent = ChatImageSendConsent(text, attachments, it.sessionId, destination), attachmentNote = null) }
            return
        }
        val plan = chatAttachmentSendPlan(
            text = text,
            attachments = attachments,
            capability = AttachmentUploadCapability.NONE,
        )

        // Plain text with no images -> existing behavior unchanged.
        if (plan is com.clawmate.app.chat.model.AttachmentSendPlan.TextOnly) {
            if (text.isEmpty() || _state.value.isSending) return
            sendText(text)
            return
        }

        // Image send with no upload API -> honest local-only/unsupported note.
        if (plan is com.clawmate.app.chat.model.AttachmentSendPlan.LocalOnlyUnsupported) {
            if (_state.value.isSending) return
            _state.update {
                it.copy(
                    pendingAttachments = plan.attachments,
                    attachmentNote = "图片附件上传尚未接入云端多模态接口，本次发送保持本地待发送状态。若想继续对话，请先移除图片。",
                )
            }
            return
        }

        // No adapter may silently discard selected images and send only text.
    }

    fun dismissImageConsent() { _state.update { it.copy(imageConsent = null) } }

    fun confirmImageSend() {
        val consent = _state.value.imageConsent ?: return
        dismissImageConsent()
        if (_state.value.isSending || _state.value.mode != ChatMode.CLOUD_AGENT ||
            _state.value.sessionId != consent.sessionId || _state.value.inputText.trim() != consent.text ||
            _state.value.pendingAttachments != consent.attachments || repository.imageUploadInfo() != consent.destination) {
            _state.update { it.copy(attachmentNote = "发送内容、会话或服务设置已改变，请重新点击发送并确认。") }
            return
        }
        sendText(consent.text.ifBlank { com.clawmate.app.chat.model.CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT }, consent.attachments, consent.destination)
    }

    private fun sendText(text: String, images: List<PendingChatAttachment> = emptyList(), destination: com.clawmate.app.chat.data.ChatImageUploadInfo? = null) {
        sendingJob?.cancel()
        _state.update { it.copy(isSending = true, error = null, agentRunning = images.isEmpty()) }
        sendingJob = viewModelScope.launch {
            // Phase 26 — publish the coarse "running" state to the live overlay
            // status registry before any agent output streams. This is a pure
            // observation side-channel; chat behavior/logging are unchanged.
            com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                com.clawmate.app.overlay.OverlayAgentState.RUNNING,
                "正在回复…",
            )
            var sessionId = ""
            try {
                sessionId = repository.ensureActiveSession()
                com.clawmate.app.log.AppLogger.cloud(
                    com.clawmate.app.log.domain.LogLevel.INFO,
                    "Chat",
                    "用户消息已发送 session=$sessionId",
                )
                val send = if (images.isEmpty()) repository.sendAgentMessage(text) else {
                    val prepare = checkNotNull(prepareImage)
                    val prepared = images.map { image ->
                        val reference = image.reference as? com.clawmate.app.chat.model.AttachmentReference.ContentUri
                            ?: throw IllegalArgumentException("请重新选择图片")
                        prepare(reference.uri)
                    }
                    currentCoroutineContext().ensureActive()
                    check(repository.imageUploadInfo() == destination) { "服务配置已改变，请重新确认图片发送" }
                    repository.sendImageMessage(text, prepared)
                }
                send.collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is ChatSendEvent.UserMessageAccepted -> {
                            _state.update {
                                it.copy(
                                    sessionId = sessionId,
                                    messages = it.messages + event.message,
                                    inputText = "",
                                    isConfigured = true,
                                    pendingAttachments = it.pendingAttachments.filterNot { pending -> images.any { image -> image.id == pending.id } },
                                    attachmentNote = null,
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
                            // Phase 26 — publish the coarse "tool running" state
                            // with the bounded tool name to the live overlay
                            // status registry (never the trace line's full args).
                            com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                                com.clawmate.app.overlay.OverlayAgentState.TOOL,
                                "执行工具 ${event.toolName}",
                            )
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
                            // Phase 26 — publish the "completed" state to the
                            // live overlay status registry.
                            com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                                com.clawmate.app.overlay.OverlayAgentState.COMPLETED,
                                "回复已完成",
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                currentCoroutineContext().ensureActive()
                // Roll back the tentative assistant bubble (if any) and show the error.
                com.clawmate.app.log.AppLogger.cloud(
                    com.clawmate.app.log.domain.LogLevel.ERROR,
                    "Chat",
                    "对话失败: ${e.userMessage}",
                )
                // Phase 26 — publish the honest "error" state to the overlay.
                com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                    com.clawmate.app.overlay.OverlayAgentState.ERROR,
                    "对话失败",
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
                currentCoroutineContext().ensureActive()
                // Phase 26 — publish the honest "error" state to the overlay.
                com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                    com.clawmate.app.overlay.OverlayAgentState.ERROR,
                    "智能体执行失败",
                )
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
                // Phase 26 — publish the honest "error" state to the overlay.
                currentCoroutineContext().ensureActive()
                com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
                    com.clawmate.app.overlay.OverlayAgentState.ERROR,
                    "发送失败",
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        agentRunning = false,
                        activeToolTrace = null,
                        error = if (images.isEmpty()) "发送失败：${e.message ?: "未知错误"}" else
                            "图片准备或发送失败，请检查图片权限、网络和模型设置后重试。未接受的附件仍保留在待发送列表。",
                        messages = it.messages.filterNot { message -> message.isStreaming && message.role == ChatMessageRole.ASSISTANT },
                    )
                }
            }
        }
    }

    /** Cancels an in-flight send. */
    fun cancelSend() {
        sendingJob?.cancel()
        // Phase 26 — a cancelled turn returns the live overlay to idle.
        com.clawmate.app.overlay.OverlayStateRegistry.updateAgentStatus(
            com.clawmate.app.overlay.OverlayAgentState.IDLE,
            "",
        )
        _state.update { it.copy(isSending = false, agentRunning = false, activeToolTrace = null, error = null,
            messages = it.messages.filterNot { message -> message.isStreaming && message.role == ChatMessageRole.ASSISTANT }) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}
