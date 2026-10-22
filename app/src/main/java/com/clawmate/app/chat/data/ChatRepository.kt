package com.clawmate.app.chat.data

import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatSession

/** Result of a single `sendMessage` request, consumed by the ViewModel. */
sealed interface ChatSendEvent {
    /** The persisted user message is now part of the history. */
    data class UserMessageAccepted(val message: ChatMessage) : ChatSendEvent

    /** A new piece of assistant reply text has streamed in. */
    data class AssistantDelta(val delta: String) : ChatSendEvent

    /**
     * A tool call was executed during the agent loop; [traceLine] is the
     * one-line human-readable summary (name/args/result) shown in the chat.
     */
    data class ToolExecuted(val toolName: String, val traceLine: String) : ChatSendEvent

    /** The assistant reply finished (delta accumulation is complete). */
    data class Completed(val fullContent: String) : ChatSendEvent
}

/** Failure surfaced by [ChatRepository.observeSendError], separate from events. */

interface ChatRepository {
    /** Safe destination label; null means this adapter cannot send images. No image I/O. */
    fun imageUploadInfo(): ChatImageUploadInfo? = null

    /** An explicit ordinary-chat image turn, with no tool/task execution. */
    suspend fun sendImageMessage(userText: String, images: List<com.clawmate.app.image.PreparedChatImage>): kotlinx.coroutines.flow.Flow<ChatSendEvent> =
        kotlinx.coroutines.flow.flow { throw com.clawmate.app.chat.cloud.ChatException(
            com.clawmate.app.chat.cloud.ChatErrorKind.CONFIG, "当前聊天适配器未接入图片发送") }

    /** All sessions, most recently updated first. */
    suspend fun sessions(): List<ChatSession>

    /** A full session including its ordered messages. */
    suspend fun session(sessionId: String): ChatSession?

    /** Returns (creating if needed) the persisted active session id. */
    suspend fun ensureActiveSession(): String

    /** The currently tracked active session id, or null when none is set yet. */
    fun activeSessionId(): String?

    /**
     * Creates a new, empty session and makes it the active one. The new session
     * is persisted through the [ChatRepository]'s store (no separate database
     * is created). Returns the created session.
     */
    suspend fun createSession(mode: String? = null): ChatSession

    /**
     * Switches the active session to [sessionId] and bumps its `updatedAt` so a
     * later restart recovers this session (most recently selected wins).
     * Returns the selected session (with its messages), or null if it no
     * longer exists.
     */
    suspend fun selectSession(sessionId: String): ChatSession?

    /**
     * Sends a user message and streams the assistant reply. The returned flow
     * emits [ChatSendEvent.UserMessageAccepted], then a series of
     * [ChatSendEvent.AssistantDelta], then [ChatSendEvent.Completed]; or
     * terminates with an error. The flow surfaces a [com.clawmate.app.chat.cloud.ChatException]
     * when the streamed request fails.
     */
    suspend fun sendMessage(userText: String): kotlinx.coroutines.flow.Flow<ChatSendEvent>

    /**
     * Sends a user message through the **agent ReAct loop** (Phase 3): persists
     * the user message, runs [com.clawmate.app.chat.agent.AgentLoop] over the
     * stored history, and streams assistant text plus tool traces as the model
     * progresses. Emits [ChatSendEvent.UserMessageAccepted], a series of
     * [ChatSendEvent.AssistantDelta] / [ChatSendEvent.ToolExecuted], then
     * [ChatSendEvent.Completed]. Never fakes model output: when no tools are
     * registered or the loop is unavailable it surfaces a typed
     * `com.clawmate.app.chat.agent.AgentException`.
     */
    suspend fun sendAgentMessage(userText: String): kotlinx.coroutines.flow.Flow<ChatSendEvent>

    /**
     * Deletes a session and all of its messages. When [sessionId] is the active
     * session, the in-memory active marker is cleared so the next send/restore
     * re-selects the most recently updated remaining session (or creates a new
     * empty one if none remain). Deleting a non-active session does not change
     * the active session.
     */
    suspend fun deleteSession(sessionId: String)
}

data class ChatImageUploadInfo(val service: String, val model: String, internal val configurationId: String = "") {
    override fun toString() = "ChatImageUploadInfo(service=$service, model=$model)"
}
