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
        if (!ChatServerConfig.isConfigured(config.baseUrl)) {
            throw ChatException(ChatErrorKind.CONFIG, "Cloud model not configured")
        }
        val text = userText.trim()
        if (text.isEmpty()) return@flow

        val sessionId = ensureActiveSession()

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

        // 2. Build the model request from the stored history (which already
        // contains the just-persisted user turn). The context budget applies
        // deterministic, local-only compression when the thread exceeds the
        // user-configured threshold.
        val history = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        val buildResult = budget().buildWireContext(systemPrompt(), history)
        val request = ChatCompletionsRequest(
            model = config.modelName,
            messages = buildResult.messages,
            stream = true,
        )

        // 3. Stream from the client, accumulate and persist on completion.
        val accumulated = StringBuilder()
        client.completionStream(request).collect { chunk ->
            if (chunk.contentDelta.isNotEmpty()) {
                accumulated.append(chunk.contentDelta)
                emit(ChatSendEvent.AssistantDelta(chunk.contentDelta))
            }
        }

        // 4. Persist the completed assistant message.
        val assistantContent = accumulated.toString()
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
    }

    override suspend fun sendAgentMessage(userText: String): Flow<ChatSendEvent> = flow {
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
            registry = com.clawmate.app.chat.agent.createDefaultToolRegistry(),
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
        dao.deleteMessagesFor(sessionId)
        dao.deleteSession(sessionId)
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
