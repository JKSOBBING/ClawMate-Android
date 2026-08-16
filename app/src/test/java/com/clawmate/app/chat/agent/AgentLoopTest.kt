package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatCompletionResult
import com.clawmate.app.chat.cloud.CloudStreamChunk
import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.ToolCallFunctionWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic tests for [AgentLoop] — the ReAct state machine:
 *   - normal content streaming regression (no tool calls -> final text),
 *   - tool-call execution then final answer,
 *   - max-step guard (reached-limit result, no infinite loop),
 *   - overall timeout -> typed [AgentException](TIMEOUT),
 *   - cancellation propagates.
 *
 * The fake [ChatClient] is deterministic and stateful across turns.
 */
class AgentLoopTest {

    /** Final [AgentResult] from a normal (non-erroring) run. */
    private fun resultOf(events: List<AgentEvent>): AgentResult {
        val completed = events.filterIsInstance<AgentEvent.Completed>()
        assertTrue("no Completed event", completed.isNotEmpty())
        return completed.last().result
    }

    @Test
    fun `no tool calls returns streamed content unchanged (regression)`() = runBlocking {
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                emit(CloudStreamChunk(contentDelta = "Hello"))
                emit(CloudStreamChunk(contentDelta = " world"))
                emit(CloudStreamChunk(done = true))
            }
        }
        val loop = AgentLoop(client, ToolRegistry())
        val events = loop.run(emptyList(), "hi", AgentLoop.Options(model = "m")).toList()
        val result = resultOf(events)
        assertEquals("Hello world", result.content)
        assertEquals(0, result.toolCallCount)
        assertTrue(!result.reachedLimit)
        // Delta events reflect the stream exactly (no fake output).
        val deltas = events.filterIsInstance<AgentEvent.ContentDelta>().map { it.delta }
        assertEquals(listOf("Hello", " world"), deltas)
    }

    @Test
    fun `executes a tool call then returns final answer`() = runBlocking {
        // Turn 1 emits a tool call; turn 2 (tool result present) emits text.
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                val hasToolResult = request.messages.any { it.role == "tool" }
                if (hasToolResult) {
                    emit(CloudStreamChunk(contentDelta = "It is 12:00."))
                    emit(CloudStreamChunk(done = true))
                } else {
                    emit(CloudStreamChunk(toolCallDeltas = listOf(
                        com.clawmate.app.chat.cloud.StreamToolCallDelta(0, id = "call_1", type = "function", name = "get_current_datetime", arguments = "{}"),
                    )))
                    emit(CloudStreamChunk(done = true))
                }
            }
        }
        val loop = AgentLoop(client, createDefaultToolRegistry())
        val events = loop.run(emptyList(), "what time", AgentLoop.Options(model = "m")).toList()
        val result = resultOf(events)
        assertEquals("It is 12:00.", result.content)
        assertEquals(1, result.toolCallCount)
        assertTrue(!result.reachedLimit)
        // A ToolExecuted-equivalent event was emitted for the loop caller.
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, toolResults.size)
        assertEquals("get_current_datetime", toolResults[0].result.toolName)
    }

    @Test
    fun `max-step guard stops and flags reached limit`() = runBlocking {
        // Client always requests a tool call, so the loop never converges.
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                emit(CloudStreamChunk(toolCallDeltas = listOf(
                    com.clawmate.app.chat.cloud.StreamToolCallDelta(0, id = "c", type = "function", name = "echo", arguments = "{}"),
                )))
                emit(CloudStreamChunk(done = true))
            }
        }
        val registry = ToolRegistry().apply {
            register(AgentTool("echo", "echo") { args, _ -> "echo" })
        }
        val loop = AgentLoop(client, registry)
        val events = loop.run(emptyList(), "loop forever", AgentLoop.Options(model = "m", maxIterations = 4)).toList()
        val result = resultOf(events)
        assertTrue(result.reachedLimit)
        assertEquals(4, result.iterations)
        assertTrue(result.content.contains("上限"))
    }

    @Test
    fun `overall timeout raises a typed AgentException`() = runBlocking {
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> =
                flow { awaitCancellation() } // never emits -> hangs
        }
        val loop = AgentLoop(client, createDefaultToolRegistry())
        try {
            loop.run(emptyList(), "hi", AgentLoop.Options(model = "m", timeoutMs = 120)).toList()
            fail("expected AgentException(TIMEOUT)")
        } catch (e: AgentException) {
            assertEquals(AgentErrorKind.TIMEOUT, e.kind)
        }
    }

    @Test
    fun `cancelling the collector cancels a hanging stream`() = runBlocking {
        val client = object : ChatClient {
            override suspend fun completion(request: ChatCompletionsRequest): ChatCompletionResult =
                throw UnsupportedOperationException()

            override fun completionStream(request: ChatCompletionsRequest): Flow<CloudStreamChunk> = flow {
                delay(Long.MAX_VALUE) // blocks until cancelled
            }
        }
        val loop = AgentLoop(client, createDefaultToolRegistry())
        val job = launch {
            try {
                loop.run(emptyList(), "hi", AgentLoop.Options(model = "m", maxIterations = 1)).collect { }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // swallowed
            }
        }
        delay(60)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
