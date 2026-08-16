package com.clawmate.app.chat.cloud

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server-Sent Events (SSE) parsing for OpenAI-compatible chat streams.
 *
 * The HarmonyOS source (`CloudModelClient.ets`) accumulates raw bytes, splits on
 * `\n`, and treats only lines starting with `data:` as payload. This class is
 * the Android equivalent: a pure, deterministic, streaming parser that
 * tolerates chunk boundaries mid-line (a network stream is not guaranteed to
 * deliver whole lines in one frame).
 *
 * The parser only *recognises* SSE framing; turning a `data:` payload into a
 * [CloudStreamChunk] is delegated to [ChatStreamChunkParser.parse], which is
 * tested separately.
 *
 * Malformed event handling: JSON that cannot be parsed is surfaced as a typed
 * [SseParserException] rather than silently dropped, so callers can distinguish
 * a truncated/malformed stream from the terminal `[DONE]`.
 */

/** Thrown when an SSE `data:` payload is not valid JSON. */
class SseParserException(message: String) : Exception(message)

/** One SSE event: the trimmed payload of a `data:` line. */
data class SseEvent(val data: String)

/** A parse pass keeps any unmatched trailing partial line for the next push(). */
data class SseParseResult(val events: List<SseEvent>, val remaining: String)

/**
 * Incremental SSE decoder that buffers partial lines across [push] calls and
 * emits completed `data:` events. Standard SSE bodies are `\n`-terminated;
 * `\r\n` CRLF is normalised away for interoperability, mirroring the source.
 */
class SseEventParser {

    private val buffer = StringBuilder()
    private val events = mutableListOf<SseEvent>()

    /**
     * Feed a decoded chunk of the SSE stream. Completed `data:` events are
     * returned; partial content is buffered and held until the next [push]
     * or [flush].
     */
    fun push(text: String): List<SseEvent> {
        buffer.append(text)
        events.clear()
        drain()
        return events.toList()
    }

    /** Emit the final buffered trailing event, if any leftover line exists. */
    fun flush(): List<SseEvent> {
        events.clear()
        if (buffer.isEmpty()) return emptyList()
        val line = buffer.toString().trim().trimEnd('\r')
        buffer.setLength(0)
        if (line.isNotEmpty()) {
            parseDataLine(line)?.let { events.add(it) }
        }
        return events.toList()
    }

    private fun drain() {
        while (true) {
            val nl = buffer.indexOf('\n')
            if (nl < 0) break
            val line = buffer.substring(0, nl).trimEnd('\r')
            buffer.delete(0, nl + 1)
            if (line.isEmpty()) continue
            parseDataLine(line)?.let { events.add(it) }
        }
    }

    private fun parseDataLine(line: String): SseEvent? {
        // Only `data:` frames matter for OpenAI streams; ignore event:/id:/
        // comment lines just like chatCompletionStream's dataReceive handler.
        if (!line.startsWith("data:")) return null
        var payload = line.substring("data:".length)
        if (payload.startsWith(" ")) payload = payload.substring(1)
        return SseEvent(payload.trim())
    }
}

/**
 * Converts an SSE `data:` payload into a [CloudStreamChunk].
 * Pure function — ported from `CloudModelClient.parseChatCompletionStreamDataLine`.
 *
 * - `[DONE]`        -> a done chunk.
 * - `data:{...}`    -> a parsed delta chunk (tolerates missing/empty fields).
 * - anything else   -> throws [SseParserException] (malformed event).
 */
object ChatStreamChunkParser {

    private val json = WireJson.json
    private const val DONE = "[DONE]"

    fun parse(payload: String): CloudStreamChunk {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return CloudStreamChunk()
        if (trimmed == DONE) return CloudStreamChunk(done = true)

        val root = try {
            json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            throw SseParserException("Cloud stream JSON parse failed: ${e.message}")
        }

        val obj = try {
            root.jsonObject
        } catch (e: Exception) {
            throw SseParserException("Cloud stream payload is not an object")
        }

        val choices = obj["choices"]?.jsonArray ?: return CloudStreamChunk()
        if (choices.isEmpty()) return CloudStreamChunk()

        val choiceObj = try {
            choices.firstOrNull()!!.jsonObject
        } catch (e: Exception) {
            throw SseParserException("Cloud stream choice is not an object")
        }

        var contentDelta = ""
        val toolCallDeltas = mutableListOf<StreamToolCallDelta>()
        val finishReason: String =
            choiceObj["finish_reason"]?.jsonPrimitive?.contentOrNull?.ifBlank { "" } ?: ""

        val deltaObj = choiceObj["delta"] as? JsonObject
        if (deltaObj == null) return CloudStreamChunk(finishReason = finishReason)

        val content = deltaObj["content"]?.jsonPrimitive?.contentOrNull
        if (!content.isNullOrEmpty()) contentDelta = content

        deltaObj["tool_calls"]?.jsonArray?.forEach { rawDelta ->
            toolCallDeltas.add(parseToolCallDelta(rawDelta))
        }

        return CloudStreamChunk(
            contentDelta = contentDelta,
            toolCallDeltas = toolCallDeltas,
            finishReason = finishReason,
            done = false,
        )
    }

    /**
     * Parses one streaming `tool_calls[]` delta entry into a
     * [StreamToolCallDelta]. Ported from the HarmonyOS `CloudStreamToolCallDelta`
     * shape; tolerates missing/null fields from OpenAI-compatible servers.
     */
    private fun parseToolCallDelta(rawDelta: kotlinx.serialization.json.JsonElement): StreamToolCallDelta {
        val obj = try {
            (rawDelta as? JsonObject) ?: return StreamToolCallDelta()
        } catch (e: Exception) {
            return StreamToolCallDelta()
        }
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isBlank()) null else it }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.let { if (it.isBlank()) null else it }
        val fn = obj["function"] as? JsonObject
        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (it.isBlank()) null else it }
        val arguments = fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { if (it.isBlank()) null else it }
        return StreamToolCallDelta(
            index = index,
            id = id,
            type = type,
            name = name,
            arguments = arguments,
        )
    }
}
