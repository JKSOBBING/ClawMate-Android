package com.clawmate.app.chat.data

import com.clawmate.app.chat.model.ChatMessage
import com.clawmate.app.chat.model.ChatMessageKind
import com.clawmate.app.chat.model.ChatMessageRole
import com.clawmate.app.chat.model.ChatMode
import com.clawmate.app.chat.model.ChatSession
import com.clawmate.app.chat.model.defaultChatSessionTitle

/** Mappers between domain [ChatSession]/[ChatMessage] and Room entities. */
object ChatMappers {

    fun toEntity(session: ChatSession): ChatSessionEntity =
        ChatSessionEntity(
            id = session.id,
            title = session.title,
            mode = session.mode,
            contextSummary = session.contextSummary,
            customTitle = session.customTitle,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
        )

    fun fromEntity(entity: ChatSessionEntity, messages: List<ChatMessage>): ChatSession =
        ChatSession(
            id = entity.id,
            title = entity.title,
            mode = entity.mode,
            contextSummary = entity.contextSummary,
            customTitle = entity.customTitle,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            messages = messages,
        )

    fun toEntity(sessionId: String, message: ChatMessage): ChatMessageEntity =
        ChatMessageEntity(
            id = message.id,
            sessionId = sessionId,
            role = message.role.wire,
            kind = message.kind.wire,
            content = message.content,
            trace = message.trace,
            collapsed = message.collapsed,
            createdAt = message.createdAt,
            imagePaths = message.imagePaths,
        )

    fun fromEntity(entity: ChatMessageEntity): ChatMessage =
        ChatMessage(
            id = entity.id,
            role = ChatMessageRole.fromWire(entity.role),
            kind = ChatMessageKind.fromWire(entity.kind),
            content = entity.content,
            trace = entity.trace,
            collapsed = entity.collapsed,
            createdAt = entity.createdAt,
            streamingVersion = 0,
            imagePaths = entity.imagePaths,
        )
}

/**
 * Derives the display title for a session that has just gained its first user
 * message, mirroring `chatSessionTitleFromMessages`. Falls back to the
 * per-mode default title.
 */
fun titleForSession(mode: String, messages: List<ChatMessage>): String {
    for (message in messages) {
        if (message.role == ChatMessageRole.USER) {
            val text = com.clawmate.app.chat.model.compactChatPreviewText(message.content, 18)
            if (text.isNotEmpty()) return text
        }
    }
    return defaultChatSessionTitle(ChatMode.normalize(mode))
}
