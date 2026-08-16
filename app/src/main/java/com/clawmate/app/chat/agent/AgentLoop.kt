package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatException
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.StreamToolCallAccumulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cancellable ReAct agent loop — the deterministic state machine that powers
 * the Phase 3 agent slice, ported from the HarmonyOS `ChatReactAgent.ets`
 * (with the max-step guard from `AgentLoopRunner.ets`).
 *
 * Loop shape (per iteration `i` in 1..maxIterations):
 *   1. POST `/v1/chat/completions` with the accumulated messages + tool
 *      declarations, streaming deltas (content + tool-call fragments).
 *   2. If the turn produced no tool calls -> the accumulated text is the final
 *      answer; finish.
 *   3. Otherwise execute each tool call via [ToolRegistry.execute] and append
 *      the assistant message + `tool` result messages, then loop.
 *
 * Guards:
 *   - **Max-step guard** ([maxIterations]) — emits [AgentEvent.LimitReached]
 *     and finishes with a reached-limit result instead of looping forever.
 *   - **Overall timeout** ([idle/live timeoutMs]) — wraps the whole run in
 *     `withTimeout`, emitting a typed [AgentErrorKind.TIMEOUT] rather than a
 *     silent hang.
 *   - **Cancellation** — the returned [Flow] is cold and cancellable; cancelling
 *     the collector propagates into the client's stream, which closes the HTTP
 *     connection.
 *
 * There is **no fake model output**: content only ever arrives from streaming
 * chunks; tool results only come from registered safe adapters.
 */
class AgentLoop(
    private val client: ChatClient,
    private val registry: ToolRegistry,
) {

    /** Options controlling one run of the loop. */
    data class Options(
        val model: String,
        val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        /** Apply an overall wall-clock budget to the whole loop. */
        val timeoutMs: Long = 0L, // 0 = no explicit overall timeout (cancellation still applies)
        val emptyContentMessage: String = "模型未返回有效内容。",
        val limitMessage: String = "工具调用轮数已达到上限，未生成最终答案。",
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        val enableTools: Boolean = true,
    )

    companion object {
        const val DEFAULT_MAX_ITERATIONS: Int = 30
        const val DEFAULT_SYSTEM_PROMPT =
            "You are ClawMate, a helpful assistant. Prefer plain text answers unless a tool is genuinely useful."
    }

    /**
     * Runs the ReAct loop for [history] plus [userText], emitting a `Flow` of
     * [AgentEvent]s and finishing with [AgentEvent.Completed] carrying an
     * [AgentResult].
     *
     * @throws [AgentException] for loop-level failures (timeout, model errors).
     */
    fun run(history: List<ChatMessageWire>, userText: String, options: Options): Flow<AgentEvent> = flow {
        val loopRunner = withTimeoutOrNoTimeout(options.timeoutMs) {
            var messages = history.toMutableList()
            var toolCallCount = 0
            val tools = if (options.enableTools && registry.hasTools()) registry.openAiTools() else emptyList()

            for (iteration in 1..options.normalizeMaxIterations()) {
                emit(AgentEvent.ModelTurnStarted(iteration))

                val request = ChatCompletionsRequest(
                    model = options.model,
                    messages = messages + listOf(ChatMessageWire(role = "user", content = userText)),
                    stream = true,
                    tools = tools.ifEmpty { null },
                    toolChoice = tools.takeIf { it.isNotEmpty() }?.let { "auto" },
                )

                var content = StringBuilder()
                val toolCalls = mutableListOf<ToolCallWire>()
                emit(AgentEvent.ContentStreamOpen(iteration))
                client.completionStream(request).collect { chunk ->
                    if (chunk.contentDelta.isNotEmpty()) {
                        content.append(chunk.contentDelta)
                        emit(AgentEvent.ContentDelta(iteration, chunk.contentDelta))
                    }
                    if (chunk.toolCallDeltas.isNotEmpty()) {
                        StreamToolCallAccumulator.apply(toolCalls, chunk.toolCallDeltas)
                    }
                }
                emit(AgentEvent.ContentStreamClosed(iteration))

                val finalCalls = StreamToolCallAccumulator.compact(toolCalls)

                // Model produced content with no tool calls -> final answer.
                if (finalCalls.isEmpty()) {
                    val text = content.toString().trim()
                    return@withTimeoutOrNoTimeout AgentResult(
                        content = text.ifEmpty { options.emptyContentMessage },
                        iterations = iteration,
                        toolCallCount = toolCallCount,
                        reachedLimit = false,
                        finishReason = "stop",
                    )
                }

                // Append the assistant turn (content + tool_calls).
                messages = messages.apply {
                    add(
                        ChatMessageWire(
                            role = "assistant",
                            content = content.toString().ifEmpty { null },
                            toolCalls = finalCalls,
                        ),
                    )
                }

                // Execute each requested tool call.
                for (call in finalCalls) {
                    toolCallCount++
                    emit(AgentEvent.ToolSelected(iteration, call))
                    val result = registry.execute(call, ToolExecutionContext())
                    emit(AgentEvent.ToolResult(iteration, result))

                    if (result.kind == ToolResultKind.TERMINAL) {
                        return@withTimeoutOrNoTimeout AgentResult(
                            content = result.terminalMessage,
                            iterations = iteration,
                            toolCallCount = toolCallCount,
                            reachedLimit = false,
                            finishReason = "tool_terminal",
                        )
                    }

                    messages = messages.apply {
                        add(
                            ChatMessageWire(
                                role = "tool",
                                content = result.truncatedContent.ifEmpty { result.content },
                                toolCallId = call.id,
                                name = call.function.name,
                            ),
                        )
                    }
                }
            }

            // Max-step guard reached.
            AgentResult(
                content = options.limitMessage,
                iterations = options.normalizeMaxIterations(),
                toolCallCount = toolCallCount,
                reachedLimit = true,
                finishReason = "",
            )
        }
        emit(AgentEvent.Completed(loopRunner))
    }

    private fun Options.normalizeMaxIterations(): Int =
        if (maxIterations <= 0) DEFAULT_MAX_ITERATIONS else maxIterations
}

/** Suspends [block] under [timeoutMs] when > 0, otherwise runs it directly. */
internal suspend fun <T> withTimeoutOrNoTimeout(timeoutMs: Long, block: suspend () -> T): T {
    if (timeoutMs <= 0) return block()
    return try {
        kotlinx.coroutines.withTimeout(timeoutMs) { block() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AgentException(AgentErrorKind.TIMEOUT, "Agent loop exceeded $timeoutMs ms budget")
    }
}

/**
 * A completed (or limit-reached) agent run. Mirrors the source
 * `ChatReactAgentResult`.
 */
data class AgentResult(
    val content: String,
    val iterations: Int,
    val toolCallCount: Int,
    val reachedLimit: Boolean,
    val finishReason: String,
)

/** Discrete events emitted by [AgentLoop.run] for the UI / ViewModel. */
sealed interface AgentEvent {
    /** A new model turn started. */
    data class ModelTurnStarted(val iteration: Int) : AgentEvent
    /** Streaming content region opened for this turn. */
    data class ContentStreamOpen(val iteration: Int) : AgentEvent
    /** A text delta streamed from the model. */
    data class ContentDelta(val iteration: Int, val delta: String) : AgentEvent
    /** Streaming content region for this turn closed. */
    data class ContentStreamClosed(val iteration: Int) : AgentEvent
    /** The model requested execution of [call]. */
    data class ToolSelected(val iteration: Int, val call: ToolCallWire) : AgentEvent
    /** [ToolSelected] produced [ToolExecutionResult]. */
    data class ToolResult(val iteration: Int, val result: ToolExecutionResult) : AgentEvent
    /** The loop finished normally. */
    data class Completed(val result: AgentResult) : AgentEvent
}

/** Typed failure reasons for the whole loop. */
enum class AgentErrorKind {
    /** Overall [Options.timeoutMs] budget exceeded. */
    TIMEOUT,
    /** Underlying model/network error (wraps [ChatException]). */
    MODEL,
    /** Internal loop invariant broken. */
    INTERNAL,
    /** Cancelled by the collector (maps to [java.util.concurrent.CancellationException]). */
    CANCELLED,
}

/** Typed exception thrown by [AgentLoop.run] for loop-level failures. */
class AgentException(
    val kind: AgentErrorKind,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /** Wraps a client-level [ChatException] into an agent MODEL error. */
        fun fromChat(e: ChatException): AgentException = AgentException(AgentErrorKind.MODEL, e.message, e)
    }
}

// ---------------------------------------------------------------------------
// Wire helpers (kept small, separately unit-tested)
// ---------------------------------------------------------------------------

/** Wraps a tool result content string back into a JSON object for display. */
internal fun toolResultToJson(content: String): JsonObject = runCatching {
    Json.parseToJsonElement(content) as? JsonObject
}.getOrNull() ?: buildJsonObject {
    // content may be plain text, not JSON; wrap it so the UI can always render it.
    put("_text", JsonPrimitive(content.take(4000)))
}
