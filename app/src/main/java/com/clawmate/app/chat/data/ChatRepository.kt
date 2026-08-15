package com.clawmate.app.chat.data

import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatSession

/** Result of a single `sendMessage` request, consumed by the ViewModel. */
sealed interface ChatSendEvent {
    /** The persisted user message is now part of the history. */
    data class UserMessageAccepted(val message: ChatMessage) : ChatSendEvent

    /** A new piece of assistant reply text has streamed in. */
    data class AssistantDelta(val delta: String) : ChatSendEvent

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

    /** Deletes a session and all of its messages. */
    suspend fun deleteSession(sessionId: String)
}
