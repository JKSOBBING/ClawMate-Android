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
    // Phase 3: optional tool declarations + tool choice for the ReAct loop.
    val tools: List<ToolDefinitionWire>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

/**
 * An OpenAI-compatible chat message.
 *
 * `content` is nullable because a tool-facing assistant turn may carry only
 * `tool_calls` and no text. This mirrors `CloudChatMessage` in the HarmonyOS
 * source (content may be absent when the model emits tool calls).
 */
@Serializable
data class ChatMessageWire(
    val role: String,
    @SerialName("content") val content: String? = "",
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallWire>? = null,
    val name: String? = null,
)

/** OpenAI `tools[].function` schema for one callable tool. */
@Serializable
data class ToolFunctionDefinitionWire(
    val name: String,
    val description: String = "",
    val parameters: kotlinx.serialization.json.JsonObject? = null,
)

/** OpenAI `tools[]` entry: wrapping a [ToolFunctionDefinitionWire]. */
@Serializable
data class ToolDefinitionWire(
    val type: String = "function",
    @SerialName("function") val function: ToolFunctionDefinitionWire,
)

/** A concrete model-emitted tool invocation (`message.tool_calls[]`). */
@Serializable
data class ToolCallWire(
    val id: String = "",
    val type: String = "function",
    @SerialName("function") val function: ToolCallFunctionWire,
)

/** The function part of a [ToolCallWire]. */
@Serializable
data class ToolCallFunctionWire(
    val name: String = "",
    val arguments: String = "",
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
 * `CloudChatStreamChunk` in the HarmonyOS source. Phase 3 adds streaming
 * tool-call deltas ([toolCallDeltas]), which the ReAct loop accumulates into
 * complete [ToolCallWire]s via [accumulateStreamToolCalls].
 */
data class CloudStreamChunk(
    val contentDelta: String = "",
    /** Streaming `tool_calls` deltas (partial id/name/arguments fragments). */
    val toolCallDeltas: List<StreamToolCallDelta> = emptyList(),
    val finishReason: String = "",
    val done: Boolean = false,
)

/**
 * One streaming tool-call delta fragment, mirroring `CloudStreamToolCallDelta`
 * in the HarmonyOS source. Any field may be `null` until the server fills it.
 */
data class StreamToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

/**
 * Low-level accumulation of streaming tool-call deltas into complete
 * tool calls, ported from `CloudModelClient.applyStreamToolCallDeltas` (it also
 * null-normalises fields that OpenAI-compatible servers leave `null`).
 *
 * `arguments` fragments are concatenated across chunks; `id`/`name` are only
 * overwritten when a non-empty delta arrives. Empty calls (no payload) are
 * dropped by [compactStream].
 */
object StreamToolCallAccumulator {

    /** Applies [deltas] onto [calls], growing the list as needed. */
    fun apply(calls: MutableList<ToolCallWire>, deltas: List<StreamToolCallDelta>) {
        for (delta in deltas) {
            val index = if (delta.index >= 0) delta.index else 0
            while (calls.size <= index) {
                calls.add(ToolCallWire(function = ToolCallFunctionWire()))
            }
            val target = calls[index]
            val newId = asNonNull(delta.id)
            val newType = asNonNull(delta.type)
            val newName = asNonNull(delta.name)
            val argFragment = asNonNull(delta.arguments)
            calls[index] = target.copy(
                id = if (newId.isNotEmpty()) newId else target.id,
                type = if (newType.isNotEmpty()) newType else target.type,
                function = target.function.copy(
                    name = if (newName.isNotEmpty()) newName else target.function.name,
                    arguments = target.function.arguments + argFragment,
                ),
            )
        }
    }

    /** Drops tool calls that carried no id/name/arguments payload. */
    fun compact(calls: List<ToolCallWire>): List<ToolCallWire> =
        calls.mapIndexed { i, call ->
            if (call.id.isNotEmpty() || call.function.name.isNotEmpty() || call.function.arguments.isNotEmpty()) {
                call.copy(
                    id = call.id.ifEmpty { "stream-tool-call-${i + 1}" },
                    type = call.type.ifEmpty { "function" },
                )
            } else null
        }.filterNotNull()

    private fun asNonNull(value: String?): String = value ?: ""
}

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
