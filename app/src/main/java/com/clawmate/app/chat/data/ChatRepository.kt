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
    /** All sessions, most recently updated first. */
    suspend fun sessions(): List<ChatSession>

    /** A full session including its ordered messages. */
    suspend fun session(sessionId: String): ChatSession?

    /** Returns (creating if needed) the persisted active session id. */
    suspend fun ensureActiveSession(): String

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

    /** Deletes a session and all of its messages. */
    suspend fun deleteSession(sessionId: String)
}
