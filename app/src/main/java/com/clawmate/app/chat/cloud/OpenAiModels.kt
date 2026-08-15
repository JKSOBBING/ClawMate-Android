package com.clawmate.app.chat.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI-compatible `/v1/chat/completions` wire models (request and response).
 *
 * Field names use the exact OpenAI wire names so the JSON sent to / read from
 * a compatible model server (the same protocol used by the HarmonyOS source
 * `utils/CloudModelClient.ets`) is correct.
 *
 * Only the subset needed for a text chat is modelled. Tool calls are omitted
 * for phase 2 and can be added when the agent loop lands.
 */

@Serializable
data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessageWire>,
    val stream: Boolean,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
)

@Serializable
data class ChatMessageWire(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionsResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessageWire? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * A single SSE stream chunk (delta) parsed from a `data:` line.
 * Ported from `CloudModelClient.parseChatCompletionStreamDataLine` /
 * `CloudChatStreamChunk` in the HarmonyOS source.
 */
data class CloudStreamChunk(
    val contentDelta: String = "",
    val finishReason: String = "",
    val done: Boolean = false,
)

/**
 * Helper for building tolerant parsers for untrusted model output.
 */
internal object WireJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}
