package com.clawmate.app.chat.model

/**
 * Chat domain models for the phase-2 vertical slice.
 *
 * These models mirror the HarmonyOS source contract in
 * `entry/src/main/ets/pages/chat/ChatSessionTypes.ets` and
 * `entry/src/main/ets/pages/chat/ChatPageTypes.ets` (mode constants), keeping
 * only the fields needed for a cloud-model chat with persistence. Image/voice
 * fields from the source are intentionally deferred to later phases.
 *
 * Persistence strategy: the source stores chat sessions as JSON files under
 * `filesDir/chat-sessions/`. This migration uses Room (see
 * MIGRATION_STATUS.md -> Chat & Agent Loop), and these models are the ORM
 * mapping layer. `ChatMessageKind`, roles and session title/preview rules are
 * ported so stored data keeps the same shape/meaning.
 */

/** Message author role, matching `ChatMessageRole` in ChatSessionTypes.ets. */
enum class ChatMessageRole(val wire: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TASK("task"),
    ;

    companion object {
        fun fromWire(wire: String?): ChatMessageRole =
            entries.firstOrNull { it.wire == wire } ?: ASSISTANT
    }
}

/** Message kind, matching `ChatMessageKind` in ChatSessionTypes.ets. */
enum class ChatMessageKind(val wire: String) {
    CHAT("chat"),
    TASK_RESULT("task_result"),
    TASK_TRACE("task_trace"),
    CONTEXT_NOTICE("context_notice"),
    ;

    companion object {
        fun fromWire(wire: String?): ChatMessageKind =
            entries.firstOrNull { it.wire == wire } ?: CHAT
    }
}

/** Chat mode, matching `CHAT_MODE_CLOUD_AGENT` / `CHAT_MODE_LOCAL_INFERENCE`. */
object ChatMode {
    const val CLOUD_AGENT: String = "cloud_agent"
    const val LOCAL_INFERENCE: String = "local_inference"

    fun normalize(mode: String?): String =
        if (mode == LOCAL_INFERENCE) LOCAL_INFERENCE else CLOUD_AGENT

    fun label(mode: String): String = if (mode == LOCAL_INFERENCE) "本地推理" else "云端智能体"
}

/**
 * A single chat message as stored and shown in the UI.
 * Mirrors `ChatMessage` in ChatSessionTypes.ets (scope trimmed to phase 2).
 */
data class ChatMessage(
    val id: String,
    val role: ChatMessageRole,
    val kind: ChatMessageKind,
    val content: String,
    val trace: List<String>,
    val collapsed: Boolean,
    val createdAt: Long,
    val streamingVersion: Int = 0,
    /** Bare names of prepared pictures in the app-private chat image store. */
    val imagePaths: List<String> = emptyList(),
) {
    val isStreaming: Boolean get() = streamingVersion > 0
}

/** A chat session (a persistent thread of messages). */
data class ChatSession(
    val id: String,
    val title: String,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val contextSummary: String = "",
    val customTitle: String = "",
) {
    val messageCount: Int get() = messages.size

    val preview: String
        get() = chatSessionPreview(messages)

    val displayTitle: String
        get() = customTitle.trim().ifEmpty { title }
}

/** Default session title, mirroring `defaultChatSessionTitle(mode)`. */
fun defaultChatSessionTitle(mode: String): String = ChatMode.label(mode) + "新会话"

/** A freshly-created empty session. Mirrors `createEmptyChatSession`. */
fun createEmptyChatSession(id: String, mode: String?, now: Long = System.currentTimeMillis()): ChatSession {
    val normalized = ChatMode.normalize(mode)
    return ChatSession(
        id = id,
        title = defaultChatSessionTitle(normalized),
        mode = normalized,
        createdAt = now,
        updatedAt = now,
    )
}

/** Builds the on-wire context list for the cloud model, port of `buildCloudContextMessages`. */
fun buildCloudContextMessages(messages: List<ChatMessage>): List<Pair<String, String>> =
    buildList {
        for (message in messages) {
            val content = message.content.trim()
            if (content.isEmpty() || message.kind == ChatMessageKind.TASK_TRACE ||
                message.kind == ChatMessageKind.CONTEXT_NOTICE
            ) {
                continue
            }
            when (message.role) {
                ChatMessageRole.USER, ChatMessageRole.ASSISTANT, ChatMessageRole.SYSTEM ->
                    add(message.role.wire to content)
                else -> {
                    if (message.kind == ChatMessageKind.TASK_RESULT) {
                        add(ChatMessageRole.ASSISTANT.wire to content)
                    }
                }
            }
        }
    }

/**
 * Compact single-line preview used for the session list / last-message preview.
 * Port of `compactChatPreviewText` (scan bounded for safety).
 */
fun compactChatPreviewText(content: String, maxLength: Int): String {
    if (maxLength <= 0 || content.isEmpty()) return ""
    val scanLimit = minOf(content.length, 4096)
    val sb = StringBuilder(minOf(maxLength + 4, content.length))
    var pendingSpace = false
    var i = 0
    while (i < scanLimit && sb.length <= maxLength) {
        val ch = content[i]
        i++
        if (ch.isWhitespace()) {
            if (sb.isNotEmpty()) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            if (sb.isNotEmpty()) {
                sb.append(' ')
                if (sb.length > maxLength) break
            }
        }
        sb.append(ch)
        pendingSpace = false
        if (sb.length > maxLength) break
    }
    return if (sb.length > maxLength) sb.toString().take(maxLength) + "..." else sb.toString()
}

/** Last visible preview of a session's messages. Port of `chatSessionPreview`. */
fun chatSessionPreview(messages: List<ChatMessage>): String {
    for (i in messages.indices.reversed()) {
        val message = messages[i]
        if (message.kind == ChatMessageKind.TASK_TRACE) continue
        val text = compactChatPreviewText(message.content, 36)
        if (text.isNotEmpty()) return text
    }
    return "暂无消息"
}

/** Title derived from the first user message. Port of `chatSessionTitleFromMessages`. */
fun chatSessionTitleFromMessages(mode: String?, messages: List<ChatMessage>): String {
    for (message in messages) {
        if (message.role == ChatMessageRole.USER) {
            val text = compactChatPreviewText(message.content, 18)
            if (text.isNotEmpty()) return text
        }
    }
    return defaultChatSessionTitle(ChatMode.normalize(mode))
}
