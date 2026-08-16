package com.clawmate.app.chat.agent

import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.chat.cloud.ChatStreamChunkParser
import com.clawmate.app.chat.cloud.StreamToolCallAccumulator
import com.clawmate.app.chat.cloud.ToolDefinitionWire
import com.clawmate.app.chat.cloud.ToolFunctionDefinitionWire
import com.clawmate.app.chat.cloud.ToolCallWire
import com.clawmate.app.chat.cloud.WireJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Phase 3 tool-call wire mapping:
 * request serialization, streaming tool-call delta accumulation, and the SSE
 * chunk parser's `tool_calls` handling. No network. Mirrors the HarmonyOS
 * `applyStreamToolCallDeltas` contract.
 */
class ToolCallMappingTest {

    @Test
    fun `request serializes tool definitions and messages with tool calls`() {
        val tools = listOf(
            ToolDefinitionWire(
                function = ToolFunctionDefinitionWire(
                    name = "get_current_datetime",
                    description = "Current time",
                    parameters = buildJsonObject { put("type", "object") },
                ),
            ),
        )
        val request = ChatCompletionsRequest(
            model = "m",
            messages = listOf(
                ChatMessageWire(role = "assistant", content = null, toolCalls = listOf(
                    ToolCallWire(id = "call_1", function = com.clawmate.app.chat.cloud.ToolCallFunctionWire("f", "{}")),
                )),
                ChatMessageWire(role = "tool", content = "ok", toolCallId = "call_1", name = "f"),
            ),
            stream = true,
            tools = tools,
            toolChoice = "auto",
        )
        val json = WireJson.json.encodeToString(ChatCompletionsRequest.serializer(), request)
        assertTrue(json.contains("\"tools\""))
        assertTrue(json.contains("\"tool_choice\":\"auto\""))
        assertTrue(json.contains("\"tool_calls\""))
        assertTrue(json.contains("\"tool_call_id\":\"call_1\""))
    }

    @Test
    fun `right-canonical round trip of tool call wire`() {
        val call = ToolCallWire(id = "c1", function = com.clawmate.app.chat.cloud.ToolCallFunctionWire("search", "{\"q\":\"x\"}"))
        val json = WireJson.json.encodeToString(ToolCallWire.serializer(), call)
        val back = WireJson.json.decodeFromString(ToolCallWire.serializer(), json)
        assertEquals("c1", back.id)
        assertEquals("search", back.function.name)
        assertEquals("{\"q\":\"x\"}", back.function.arguments)
    }

    @Test
    fun `stream tool call deltas accumulate into a complete call`() {
        val calls = mutableListOf<ToolCallWire>()
        StreamToolCallAccumulator.apply(calls, listOf(
            com.clawmate.app.chat.cloud.StreamToolCallDelta(index = 0, id = "call_9", type = "function"),
        ))
        StreamToolCallAccumulator.apply(calls, listOf(
            com.clawmate.app.chat.cloud.StreamToolCallDelta(index = 0, name = "get_current_datetime"),
        ))
        StreamToolCallAccumulator.apply(calls, listOf(
            com.clawmate.app.chat.cloud.StreamToolCallDelta(index = 0, arguments = "{\"tz\":"),
        ))
        StreamToolCallAccumulator.apply(calls, listOf(
            com.clawmate.app.chat.cloud.StreamToolCallDelta(index = 0, arguments = "\"UTC\"}"),
        ))

        assertEquals(1, calls.size)
        val call = calls[0]
        assertEquals("call_9", call.id)
        assertEquals("get_current_datetime", call.function.name)
        assertEquals("{\"tz\":\"UTC\"}", call.function.arguments)
    }

    @Test
    fun `compact drops empty calls and assigns synthetic ids`() {
        val calls = mutableListOf(
            ToolCallWire(function = com.clawmate.app.chat.cloud.ToolCallFunctionWire()),
            ToolCallWire(function = com.clawmate.app.chat.cloud.ToolCallFunctionWire("f", "{}")),
        )
        val compacted = StreamToolCallAccumulator.compact(calls)
        assertEquals(1, compacted.size)
        assertEquals("stream-tool-call-2", compacted[0].id)
        assertEquals("f", compacted[0].function.name)
    }

    @Test
    fun `sse chunk parser extracts streaming tool call deltas`() {
        val chunk = ChatStreamChunkParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":"{\"q\":\"x\"}"}}]},"finish_reason":null}]}""",
        )
        assertEquals(1, chunk.toolCallDeltas.size)
        val delta = chunk.toolCallDeltas[0]
        assertEquals(0, delta.index)
        assertEquals("call_1", delta.id)
        assertEquals("search", delta.name)
        assertEquals("{\"q\":\"x\"}", delta.arguments)
    }

    @Test
    fun `sse chunk parser with tool calls and no content maps only deltas`() {
        val chunk = ChatStreamChunkParser.parse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}""",
        )
        assertEquals("", chunk.contentDelta)
        assertEquals(1, chunk.toolCallDeltas.size)
        assertEquals("{}", chunk.toolCallDeltas[0].arguments)
    }
}
