package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.model.defaultChatSessionTitle
import com.clawmate.app.chat.model.chatSessionTitleFromMessages
import java.util.UUID
import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.chat.cloud.ChatImageBounds
import com.clawmate.app.chat.cloud.validateChatImageDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Default [ChatRepository] backed by Room (via [ChatDao]) and the OkHttp
 * [ChatCompletionsClient].
 *
 * Persistence matches the pattern of the source
 * `pages/chat/ChatSessionController.ets`: a user message is appended and
 * persisted immediately; the assistant message is accumulated from stream
 * deltas and persisted once the stream completes. No fake replies are ever
 * produced — if the cloud model is not configured or the request fails, a
 * typed [ChatException] is thrown and no assistant message is stored.
 *
 * The repository owns the "active session": [activeSessionId] is persisted so
 * that a fresh request targets the same thread across sends.
 */
class DefaultChatRepository(
    private val dao: ChatDao,
    private val client: ChatClient,
    private val config: ChatServerConfig,
    private val onSessionTitleChanged: (String) -> Unit = {},
    /** Optional agent loop for [sendAgentMessage]; created lazily when null. */
    private val agentLoop: com.clawmate.app.chat.agent.AgentLoop? = null,
    /**
     * Optional typed Settings store (Phase 43). When present, the context
     * compression [com.clawmate.app.chat.model.ChatContextBudget] is rebuilt from
     * the user's configured `chatCompressionThresholdPercent` /
     * `chatRecentRounds` before every request; when absent the Settings-safe
     * defaults are used. The budget itself is pure and makes no network calls.
     */
    private val settingsRepository: com.clawmate.app.settings.data.SettingsRepository? = null,
    private val toolRegistry: com.clawmate.app.chat.agent.ToolRegistry = com.clawmate.app.chat.agent.createDefaultToolRegistry(),
    private val imageStore: ChatImageFileStore? = null,
    /** Reads the latest saved runtime connection at each explicit send. */
    private val connectionProvider: (() -> Pair<ChatServerConfig, ChatClient>)? = null,
) : ChatRepository {

    @Volatile
    private var activeSessionId: String? = null

    /** Last timestamp handed out, so adjacent create/select calls never collide. */
    @Volatile
    private var lastTimestamp: Long = 0L

    private var activeSessionIdSetter: (String?) -> Unit = { activeSessionId = it }

    fun setActiveSessionIdProvider(provider: (String) -> Unit) {
        activeSessionIdSetter = { id -> activeSessionId = id; if (id != null) provider(id) }
    }

    override suspend fun sessions(): List<ChatSession> =
        dao.observeSessions().map { entity ->
            ChatMappers.fromEntity(entity, dao.messagesFor(entity.id).map(ChatMappers::fromEntity))
        }

    override suspend fun session(sessionId: String): ChatSession? {
        val entity = dao.session(sessionId) ?: return null
        val messages = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        return ChatMappers.fromEntity(entity, messages)
    }

    override suspend fun sendMessage(userText: String): Flow<ChatSendEvent> = flow {
        val (currentConfig, currentClient) = connection()
        sendOrdinaryMessage(userText, emptyList(), currentConfig, currentClient).collect { emit(it) }
    }

    private fun connection(): Pair<ChatServerConfig, ChatClient> = connectionProvider?.invoke() ?: (config to client)

    override fun imageUploadInfo(): ChatImageUploadInfo? {
        if (imageStore == null) return null
        val current = connection().first
        val url = current.completionsUrl.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || current.modelName.isBlank()) return null
        val identity = java.security.MessageDigest.getInstance("SHA-256")
            .digest((current.completionsUrl + "\u0000" + current.apiKey + "\u0000" + current.modelName).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return ChatImageUploadInfo("${url.host}:${url.port}", current.modelName, identity)
    }

    override suspend fun sendImageMessage(userText: String, images: List<PreparedChatImage>): Flow<ChatSendEvent> = flow {
        check(imageUploadInfo() != null) { "请先配置支持图片输入的聊天服务" }
        require(images.size in 1..ChatImageBounds.MAX_IMAGES) { "单次最多发送4张图片" }
        images.forEach { validateChatImageDataUrl(it.dataUrl) }
        val (currentConfig, currentClient) = connection()
        sendOrdinaryMessage(userText.ifBlank { com.clawmate.app.chat.model.CHAT_IMAGE_ATTACHMENT_ONLY_PROMPT },
            images, currentConfig, currentClient).collect { emit(it) }
    }

    private fun sendOrdinaryMessage(userText: String, images: List<PreparedChatImage>, config: ChatServerConfig, client: ChatClient): Flow<ChatSendEvent> = flow {
        if (!ChatServerConfig.isConfigured(config.baseUrl)) {
            throw ChatException(ChatErrorKind.CONFIG, "Cloud model not configured")
        }
        val text = userText.trim()
        if (text.isEmpty()) return@flow

        val sessionId = ensureActiveSession()
        if (images.isNotEmpty()) check(dao.session(sessionId)?.mode != com.clawmate.app.chat.model.ChatMode.LOCAL_INFERENCE) {
            "本地推理尚不支持图片输入，请切换云端聊天"
        }

        val storedNames = mutableListOf<String>()
        var accepted = false
        try {
            if (images.isNotEmpty()) withContext(Dispatchers.IO) { storedNames += checkNotNull(imageStore).save(images) }

            // 1. Persist the user message immediately.
            val userMessage = ChatMessage(
                id = "msg-" + UUID.randomUUID(),
                role = ChatMessageRole.USER,
                kind = ChatMessageKind.CHAT,
                content = text,
                trace = emptyList(),
                collapsed = false,
                createdAt = now(),
                imagePaths = storedNames.toList(),
            )
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                dao.upsertMessages(listOf(ChatMappers.toEntity(sessionId, userMessage)))
                accepted = true
            }
            updateSessionMeta(sessionId, now())
            emit(ChatSendEvent.UserMessageAccepted(userMessage))

            // 2. Build the model request from the stored history (which already
            // contains the just-persisted user turn). The context budget applies
            // deterministic, local-only compression when the thread exceeds the
            // user-configured threshold.
            val history = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
            val buildResult = if (images.isEmpty()) budget().buildWireContext(systemPrompt(), history) else
                budget().buildWireContext(systemPrompt(), history.filterNot { it.id == userMessage.id }, pendingUserText = text)
            val wires = if (images.isEmpty()) buildResult.messages else buildResult.messages.dropLast(1) +
                buildResult.messages.last().copy(imageUrls = images.map { it.dataUrl })
            val request = ChatCompletionsRequest(
                model = config.modelName,
                messages = wires,
                stream = true,
            )

            // 3. Stream from the client, accumulate and persist on completion.
            val accumulated = StringBuilder()
            var imageFinish = ""
            var receivedImageToolCall = false
            client.completionStream(request).collect { chunk ->
                if (chunk.finishReason.isNotBlank()) imageFinish = chunk.finishReason
                if (chunk.toolCallDeltas.isNotEmpty()) receivedImageToolCall = true
                if (chunk.contentDelta.isNotEmpty()) {
                    accumulated.append(chunk.contentDelta)
                    emit(ChatSendEvent.AssistantDelta(chunk.contentDelta))
                }
            }

            // 4. Persist the completed assistant message.
            val assistantContent = accumulated.toString()
            if (images.isNotEmpty() && (imageFinish != "stop" || receivedImageToolCall || assistantContent.isBlank())) {
                throw ChatException(ChatErrorKind.MALFORMED_SSE, "图片模型未返回完整的普通聊天回复，请检查模型能力并重试")
            }
            val assistantMessage = ChatMessage(
                id = "msg-" + UUID.randomUUID(),
                role = ChatMessageRole.ASSISTANT,
                kind = ChatMessageKind.CHAT,
                content = assistantContent,
                trace = emptyList(),
                collapsed = false,
                createdAt = now(),
            )
            dao.upsertMessages(listOf(ChatMappers.toEntity(sessionId, assistantMessage)))
            updateSessionMeta(sessionId, now())
            emit(ChatSendEvent.Completed(assistantContent))
        } finally {
            // Once accepted, the persisted user bubble owns the prepared local files,
            // even if the network fails or is cancelled. Unaccepted batches are removed.
            if (!accepted && storedNames.isNotEmpty()) withContext(NonCancellable + Dispatchers.IO) { imageStore?.delete(storedNames) }
        }
    }

    override suspend fun sendAgentMessage(userText: String): Flow<ChatSendEvent> = flow {
        val (config, client) = connection()
        if (!ChatServerConfig.isConfigured(config.baseUrl)) {
            throw com.clawmate.app.chat.cloud.ChatException(
                com.clawmate.app.chat.cloud.ChatErrorKind.CONFIG,
                "Cloud model not configured",
            )
        }
        val text = userText.trim()
        if (text.isEmpty()) return@flow

        val sessionId = ensureActiveSession()

        // Build the agent context from the history stored *before* this turn so the
        // closure includes the whole prior thread exactly once (the loop appends the
        // new user turn itself). Context compression (deterministic, local-only) is
        // applied here by the budget when the prior thread exceeds the setting.
        val priorHistory = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        val buildResult = budget().buildWireContext(systemPrompt(), priorHistory)
        val wireHistory = buildResult.messages.toMutableList()
        val loop = agentLoop ?: com.clawmate.app.chat.agent.AgentLoop(
            client = client,
            registry = toolRegistry,
        )

        // 1. Persist the user message immediately.
        val userMessage = ChatMessage(
            id = "msg-" + UUID.randomUUID(),
            role = ChatMessageRole.USER,
            kind = ChatMessageKind.CHAT,
            content = text,
            trace = emptyList(),
            collapsed = false,
            createdAt = now(),
        )
        dao.upsertMessages(listOf(ChatMappers.toEntity(sessionId, userMessage)))
        updateSessionMeta(sessionId, now())
        emit(ChatSendEvent.UserMessageAccepted(userMessage))

        // 2. Run the ReAct loop over the stored history.
        val accumulated = StringBuilder()
        val toolTraces = mutableListOf<String>()

        try {
            loop.run(
                history = wireHistory,
                userText = text,
                options = com.clawmate.app.chat.agent.AgentLoop.Options(
                    model = config.modelName,
                ),
            ).collect { event ->
                when (event) {
                    is com.clawmate.app.chat.agent.AgentEvent.ContentDelta -> {
                        accumulated.append(event.delta)
                        emit(ChatSendEvent.AssistantDelta(event.delta))
                    }
                    is com.clawmate.app.chat.agent.AgentEvent.ToolResult -> {
                        if (event.result.traceLine.isNotEmpty()) {
                            toolTraces += event.result.traceLine
                            emit(ChatSendEvent.ToolExecuted(event.result.toolName, event.result.traceLine))
                        }
                        if (event.result.kind == com.clawmate.app.chat.agent.ToolResultKind.DISABLED) {
                            // Append the actionable bridge/permission note to the reply text
                            // so the user sees why the action was skipped (no fake success).
                            val note = "\n\n[设备操作被禁用] ${event.result.disabledReason}"
                            accumulated.append(note)
                            emit(ChatSendEvent.AssistantDelta(note))
                        }
                    }
                    else -> Unit
                }
            }
        } catch (e: com.clawmate.app.chat.agent.AgentException) {
            throw e
        } catch (e: com.clawmate.app.chat.cloud.ChatException) {
            throw e
        }

        // 3. Persist the completed assistant message (with tool traces).
        val assistantContent = accumulated.toString()
        val assistantMessage = ChatMessage(
            id = "msg-" + UUID.randomUUID(),
            role = ChatMessageRole.ASSISTANT,
            kind = ChatMessageKind.CHAT,
            content = assistantContent,
            trace = toolTraces,
            collapsed = false,
            createdAt = now(),
        )
        dao.upsertMessages(listOf(ChatMappers.toEntity(sessionId, assistantMessage)))
        updateSessionMeta(sessionId, now())
        emit(ChatSendEvent.Completed(assistantContent))
    }

    override suspend fun deleteSession(sessionId: String) {
        val wasActive = activeSessionId == sessionId
        val images = dao.messagesFor(sessionId).flatMap { it.imagePaths }
        dao.deleteMessagesFor(sessionId)
        dao.deleteSession(sessionId)
        withContext(NonCancellable + Dispatchers.IO) { imageStore?.delete(images) }
        if (wasActive) {
            // Clear the in-memory active marker so the next send (or restart
            // restore) re-selects the most recently updated remaining session,
            // or creates a fresh empty one when nothing remains.
            activeSessionIdSetter(null)
        }
    }

    override fun activeSessionId(): String? = activeSessionId

    /** A persisted session becomes active (used by new/select). */
    private suspend fun makeActive(sessionId: String) {
        activeSessionId = sessionId
        activeSessionIdSetter(sessionId)
    }

    override suspend fun createSession(mode: String?): ChatSession {
        val id = "sess-" + UUID.randomUUID()
        val now = nextNow()
        val session = createEmptySession(
            id,
            now,
            com.clawmate.app.chat.model.ChatMode.normalize(mode),
        )
        dao.upsertSession(ChatMappers.toEntity(session))
        makeActive(id)
        return session
    }

    override suspend fun selectSession(sessionId: String): ChatSession? {
        if (sessionId.isEmpty()) return null
        val entity = dao.session(sessionId) ?: return null
        // Bump updatedAt so the most recently selected session is restored on a
        // fresh app launch (restart recovery uses the max-updated session).
        dao.upsertSession(entity.copy(updatedAt = nextNow()))
        makeActive(sessionId)
        val messages = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        return ChatMappers.fromEntity(
            dao.session(sessionId) ?: entity,
            messages,
        )
    }

    /** Returns (creating if needed) a persisted session to serve as the active one. */
    override suspend fun ensureActiveSession(): String {
        activeSessionId?.let { return it }
        val existing = dao.observeSessions().maxByOrNull { it.updatedAt }?.id
        if (existing != null) {
            // Restart recovery: pick the most recently (selected/updated) session.
            activeSessionId = existing
            activeSessionIdSetter(existing)
            return existing
        }
        return createSession().id
    }

    private fun createEmptySession(
        id: String,
        now: Long,
        mode: String = com.clawmate.app.chat.model.ChatMode.CLOUD_AGENT,
    ) = com.clawmate.app.chat.model.createEmptyChatSession(id, mode, now)

    private fun now() = System.currentTimeMillis()

    /** A monotonically increasing timestamp so create/select ordering is stable. */
    private fun nextNow(): Long {
        val ts = maxOf(now(), lastTimestamp + 1L)
        lastTimestamp = ts
        return ts
    }

    /** First user message becomes the session title unless the user renamed it. */
    private suspend fun updateSessionMeta(sessionId: String, ts: Long) {
        val entity = dao.session(sessionId) ?: return
        val messages = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        val currentDefault = defaultChatSessionTitle(entity.mode)
        val title = if (entity.customTitle.isBlank() && entity.title == currentDefault) {
            chatSessionTitleFromMessages(entity.mode, messages)
        } else {
            entity.title
        }
        dao.upsertSession(entity.copy(updatedAt = ts, title = title))
        onSessionTitleChanged(title)
    }

    /**
     * Resolves the context-compression budget for the next request. When a
     * Settings store is injected this reads the user's configured threshold /
     * recent-rounds (clamped to the same bounds the Settings normalizers
     * enforce); otherwise the Settings-safe defaults are used. The budget is a
     * pure value object and never touches the network.
     */
    private suspend fun budget(): com.clawmate.app.chat.model.ChatContextBudget {
        val settings = settingsRepository?.snapshot()
            ?: com.clawmate.app.settings.domain.AppSettings.defaults()
        return com.clawmate.app.chat.model.ChatContextBudget.fromSettings(settings)
    }

    private fun systemPrompt(): String = DEFAULT_SYSTEM_PROMPT

    companion object {
        const val DEFAULT_SYSTEM_PROMPT: String = "You are ClawMate, a helpful assistant."
    }
}
