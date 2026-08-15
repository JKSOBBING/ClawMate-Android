package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatErrorKind
import com.clawmate.app.chat.cloud.ChatMessageWire
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
) : ChatRepository {

    @Volatile
    private var activeSessionId: String? = null

    private var activeSessionIdSetter: (String) -> Unit = { activeSessionId = it }

    fun setActiveSessionIdProvider(provider: (String) -> Unit) {
        activeSessionIdSetter = { id -> activeSessionId = id; provider(id) }
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

        // 2. Build the model request from the stored history.
        val history = dao.messagesFor(sessionId).map(ChatMappers::fromEntity)
        val request = ChatCompletionsRequest(
            model = config.modelName,
            messages = buildWireMessages(history, text),
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

    override suspend fun deleteSession(sessionId: String) {
        dao.deleteMessagesFor(sessionId)
        dao.deleteSession(sessionId)
    }

    /** Returns (creating if needed) a persisted session to serve as the active one. */
    override suspend fun ensureActiveSession(): String {
        activeSessionId?.let { return it }
        val existing = dao.observeSessions().maxByOrNull { it.updatedAt }?.id
        if (existing != null) {
            activeSessionId = existing
            return existing
        }
        val id = "sess-" + UUID.randomUUID()
        val now = now()
        dao.upsertSession(
            ChatMappers.toEntity(createEmptySession(id, now)),
        )
        activeSessionId = id
        activeSessionIdSetter(id)
        return id
    }

    private fun createEmptySession(id: String, now: Long) =
        com.clawmate.app.chat.model.createEmptyChatSession(id, com.clawmate.app.chat.model.ChatMode.CLOUD_AGENT, now)

    private fun now() = System.currentTimeMillis()

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

    private fun buildWireMessages(history: List<ChatMessage>, pendingUserText: String): List<ChatMessageWire> {
        val context = mutableListOf<ChatMessageWire>()
        context += ChatMessageWire(ChatMessageRole.SYSTEM.wire, "You are ClawMate, a helpful assistant.")
        for (message in history) {
            val content = message.content.trim()
            if (content.isEmpty()) continue
            when {
                message.role == ChatMessageRole.USER ||
                    message.role == ChatMessageRole.ASSISTANT ||
                    message.role == ChatMessageRole.SYSTEM -> {
                    if (message.kind != ChatMessageKind.TASK_TRACE &&
                        message.kind != ChatMessageKind.CONTEXT_NOTICE
                    ) {
                        context += ChatMessageWire(message.role.wire, content)
                    }
                }
                message.kind == ChatMessageKind.TASK_RESULT ->
                    context += ChatMessageWire(ChatMessageRole.ASSISTANT.wire, content)
                else -> Unit
            }
        }
        context += ChatMessageWire(ChatMessageRole.USER.wire, pendingUserText)
        return context
    }
}
