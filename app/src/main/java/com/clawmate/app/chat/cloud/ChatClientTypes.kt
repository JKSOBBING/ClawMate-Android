package com.clawmate.app.chat.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Result / error types for the OpenAI-compatible chat client.
 */

/** A completed (non-streaming) chat completion outcome. */
data class ChatCompletionResult(
    val content: String,
    val finishReason: String,
    val raw: String?,
)

/** Finished state of a streamed chat completion, including total text. */
data class ChatStreamResult(
    val content: String,
    val finishReason: String,
)

/** The final mapped error reason surfaced to the UI. */
enum class ChatErrorKind {
    // User-facing states
    HTTP,
    TIMEOUT,
    CANCELLED,
    IO,
    MALFORMED_SSE,
    CONFIG,
    UNKNOWN,
}

class ChatException(
    val kind: ChatErrorKind,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val userMessage: String
        get() = when (kind) {
            ChatErrorKind.CONFIG -> "未配置云端模型，请先在设置中填写端点。"
            ChatErrorKind.TIMEOUT -> "请求超时，请稍后重试。"
            ChatErrorKind.CANCELLED -> "请求已取消。"
            else -> message
        }
}

/**
 * Maps a raw HTTP non-2xx response body to a human-readable error, extracting
 * common OpenAI-style `error`/`message`/`detail` fields. Ported from
 * `CloudModelClient.errorMessageFromNonSsePayload`.
 */
internal object ChatErrorBodyParser {

    fun message(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val json = WireJson.json
        val obj = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return trimmed.take(512)
        }
        val parts = mutableListOf<String>()

        stringOf(obj["message"])?.takeIf { it.isNotBlank() }?.let { parts += it }
        stringOf(obj["detail"])?.takeIf { it.isNotBlank() }?.let { parts += it }

        val error = obj["error"]
        if (error != null) {
            when (error) {
                is JsonPrimitive -> stringOf(error)?.takeIf { it.isNotBlank() }?.let { parts += it }
                is JsonObject -> {
                    stringOf(error["message"])?.takeIf { it.isNotBlank() }?.let { parts += it }
                    stringOf(error["type"])?.takeIf { it.isNotBlank() }?.let { parts += "type=${it}" }
                    stringOf(error["code"])?.takeIf { it.isNotBlank() }?.let { parts += "code=${it}" }
                }
                else -> Unit
            }
        }
        return parts.distinct().joinToString(", ")
    }

    private fun stringOf(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull
}
