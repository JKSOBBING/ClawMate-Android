package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.ToolDefinitionWire
import com.clawmate.app.chat.cloud.ToolFunctionDefinitionWire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Domain models for the Phase 3 Agent/ReAct vertical slice.
 *
 * These mirror the HarmonyOS `ChatToolRegistry.ets` / `CloudModelClient`
 * tool-call contract (roles `assistant`/`tool`, `tool_call_id`, `name`) so that
 * stored-wire history keeps the same shape as the source. The registry executes
 * only *safe, explicit* Android adapters — never arbitrary commands.
 */

/** Execution context handed to a tool when it runs. */
data class ToolExecutionContext(
    val sessionId: String = "",
    val userMessage: String = "",
)

/**
 * A registered tool: name/description/JSON-schema plus a suspend [execute]
 * returning the human-readable result sent back to the model.
 */
data class AgentTool(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
    val execute: suspend (arguments: String, context: ToolExecutionContext) -> String,
)

/** The classified outcome category of a single tool execution. */
enum class ToolResultKind {
    /** The tool completed and produced usable output. */
    OK,
    /** The tool produced an error; result [ToolExecutionResult.error]. */
    ERROR,
    /**
     * The capability exists in the product but cannot run on Android today
     * (e.g. Harmony GUI/HDC/overlay actions). A controlled-disabled result
     * explains the required PC bridge / permission instead of a fake success
     * (AGENTS.md — do not silently drop; do not pretend a mock is real).
     */
    DISABLED,
    /**
     * The tool result represents a user-terminal decision (e.g. the user
     * refused a device-side action): the ReAct loop must stop instead of
     * asking the model again (source `ChatToolExecutionResult.terminal`).
     */
    TERMINAL,
}

/** Result of executing one tool call. */
data class ToolExecutionResult(
    val toolName: String,
    val toolCallId: String,
    val argumentsText: String = "",
    /** Raw result text from the tool (pre-truncation). */
    val content: String = "",
    /** Truncated content actually handed back to the model. */
    val truncatedContent: String = "",
    /** One-line trace shown in the UI. */
    val traceLine: String = "",
    val success: Boolean,
    val kind: ToolResultKind = if (success) ToolResultKind.OK else ToolResultKind.ERROR,
    /** Non-null and non-empty only when [kind] is [ToolResultKind.TERMINAL]. */
    val terminalMessage: String = "",
    /** Non-empty when [kind] is [ToolResultKind.DISABLED]; explains the bridge. */
    val disabledReason: String = "",
    /** Structured error payload (JSON) returned to the model on failure. */
    val errorPayload: String = "",
)

/** Default empty execution result used for tool calls that were not executed. */
internal fun emptyToolError(toolName: String, toolCallId: String, message: String): ToolExecutionResult =
    ToolExecutionResult(
        toolName = toolName,
        toolCallId = toolCallId,
        content = errorJson(message),
        truncatedContent = truncateResult(errorJson(message)),
        traceLine = "工具：$toolName\n结果：${errorJson(message)}",
        success = false,
        kind = ToolResultKind.ERROR,
        errorPayload = errorJson(message),
    )

/** Error JSON payload mirroring the source `ChatToolErrorPayload`. */
fun errorJson(message: String): String =
    """{"ok":false,"error":${jsonEscape(message)}}"""

/** Builds a controlled-disabled JSON result explaining the PC bridge/permission. */
fun disabledJson(message: String): String =
    """{"ok":false,"disabled":true,"requires":${jsonEscape(message)}}"""

internal fun jsonEscape(message: String): String =
    kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), JsonPrimitive(message))

internal fun truncateResult(text: String, maxLength: Int = 4000): String =
    if (text.length <= maxLength) text else text.substring(0, maxLength) + "..."

/** Builds the OpenAI `tools` declaration list from registered [AgentTool]s. */
fun toOpenAiTools(tools: List<AgentTool>): List<ToolDefinitionWire> =
    tools.map { tool ->
        ToolDefinitionWire(
            type = "function",
            function = ToolFunctionDefinitionWire(
                name = tool.name,
                description = tool.description,
                parameters = tool.parameters,
            ),
        )
    }

/**
 * Renders a complete [ToolCallWire] back into the tool-facing wire message.
 * Mirrors the source assistant turn `{role:'assistant', tool_calls:[...]}`.
 */
fun toolCallArgumentsText(call: ToolCallWire): String {
    val raw = call.function.arguments.trim()
    return if (raw.isEmpty()) "{}" else raw
}

/** Whether the arguments text parses to a non-null value (validation check). */
internal fun isValidJsonObject(arguments: String): Boolean {
    val text = arguments.trim().ifEmpty { "{}" }
    return try {
        kotlinx.serialization.json.Json.parseToJsonElement(text) is JsonObject
    } catch (e: Exception) {
        false
    }
}
