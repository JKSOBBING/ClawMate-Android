package com.clawmate.app.chat.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Pure parsers for the *text-JSON action* channel of the agent, ported from the
 * HarmonyOS `AgentActionParser.ets`. This is the deterministic piece the ReAct
 * loop uses to:
 *   - extract a JSON object from possibly-unwrapped / model-prefixed text,
 *   - read the `action` and `parameters`,
 *   - detect terminal actions (`done`/`stop`/`terminate`/`abort`).
 *
 * This exists alongside OpenAI `tool_calls`: some endpoints still return
 * actions as JSON text rather than structured tool calls, matching the source
 * which parses both. It is pure (no network / platform), so it unit-tests
 * deterministically.
 */
object AgentActionParser {

    /** Thrown when required fields are missing / JSON cannot be parsed. */
    class AgentParseException(message: String) : Exception(message)

    /** A parsed action: the raw input, its JSON data, action name and parameters. */
    data class ParsedAgentAction(
        val raw: String,
        val data: JsonObject,
        val action: String,
        val parameters: JsonObject,
    )

    /** Terminal action names (source `AgentActionParser.isTerminal`). */
    fun isTerminal(action: String): Boolean {
        val normalized = action.trim().lowercase()
        return normalized == "done" || normalized == "stop" || normalized == "terminate" || normalized == "abort"
    }

    /**
     * Parses raw model text into a [ParsedAgentAction]. Mirrors the source
     * `AgentActionParser.parse`: normalizes the text, unwraps nested payloads,
     * and throws [AgentParseException] when no `action` is found.
     */
    fun parse(raw: String): ParsedAgentAction {
        val data = extractJsonMap(raw)
        val action = getMapString(data, "action").lowercase()
        if (action.isEmpty()) throw AgentParseException("Agent JSON parse failed: response missing action")

        // `parameters` is preferred, `coordinates` accepted for legacy callers.
        var params = toMap(data["parameters"] ?: data["coordinates"])
        if (params.isEmpty() && (data.containsKey("coords") || data.containsKey("bbox") ||
                data.containsKey("text") || data.containsKey("direction") ||
                data.containsKey("start_coords") || data.containsKey("end_coords"))
        ) {
            params = data
        }
        return ParsedAgentAction(raw = raw, data = data, action = action, parameters = params)
    }

    /** Reads a string field from a map with a fallback. */
    fun getMapString(map: JsonObject, key: String, fallback: String = ""): String {
        val value = map[key] ?: return fallback
        if (value !is JsonPrimitive) return fallback
        return value.contentOrNull ?: fallback
    }

    // -----------------------------------------------------------------
    // JSON extraction (tolerant port of the source extractJsonMap/unwrap)
    // -----------------------------------------------------------------

    /**
     * Extracts a JSON object from arbitrary model text by locating the first
     * `{` .. last `}` span, parsing it, and unwrapping known wrapper payloads.
     */
    fun extractJsonMap(raw: String): JsonObject {
        val trimmed = raw.trim()
        // Fast path: a valid JSON object on its own.
        val direct = tryParseObject(trimmed)
        if (direct != null) return unwrapKnownPayload(direct, 0)

        val span = extractJsonSpan(trimmed)
        val within = tryParseObject(span) ?: throw AgentParseException("Agent JSON parse failed: no object found")
        return unwrapKnownPayload(within, 0)
    }

    /**
     * Extracts the substring spanning the outermost balanced `{...}` region of
     * [text], tolerating surrounding prose. Returns the original text when no
     * braces are present.
     */
    internal fun extractJsonSpan(text: String): String {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) return text.substring(start, i + 1)
                    }
                }
                else -> Unit
            }
        }
        return text
    }

    /**
     * Attempts to parse [text] as a JSON object; returns `null` when it is not.
     */
    internal fun tryParseObject(text: String): JsonObject? {
        val t = text.trim().ifEmpty { return null }
        return try {
            val el = kotlinx.serialization.json.Json.parseToJsonElement(t)
            el as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private val WRAPPER_KEYS = listOf(
        "response", "content", "output", "text", "message", "result", "data",
        "arguments", "tool_input", "choices", "delta",
    )

    /** Recursively unwraps known wrapper payloads, bounded by depth. */
    private fun unwrapKnownPayload(map: JsonObject, depth: Int): JsonObject {
        if (isKnownPayload(map) || depth >= 4) return map
        for (key in WRAPPER_KEYS) {
            val nested = valueToKnownPayload(map[key], depth + 1)
            if (nested != null) return nested
        }
        // Depth 0: also scan non-wrapper keys.
        if (depth == 0) {
            for ((key, value) in map) {
                if (key in WRAPPER_KEYS) continue
                val nested = valueToKnownPayload(value, depth + 1)
                if (nested != null) return nested
            }
        }
        return map
    }

    private fun isKnownPayload(map: JsonObject): Boolean {
        for (key in listOf("action", "app", "target_app", "app_name", "package_name", "bundle", "bundle_name", "final_task_description")) {
            if (map.containsKey(key)) return true
        }
        return false
    }

    private fun valueToKnownPayload(value: kotlinx.serialization.json.JsonElement?, depth: Int): JsonObject? {
        if (value == null || depth > 4) return null
        return when (value) {
            is JsonPrimitive -> {
                val text = value.contentOrNull ?: return null
                stringToKnownPayload(text, depth)
            }
            is JsonObject -> {
                val unwrapped = unwrapKnownPayload(value, depth + 1)
                unwrapped.takeIf { isKnownPayload(it) }
            }
            else -> {
                // JsonArray
                val arr = (value as? kotlinx.serialization.json.JsonArray) ?: return null
                val limit = minOf(arr.size, 8)
                for (i in 0 until limit) {
                    val nested = valueToKnownPayload(arr[i] as? kotlinx.serialization.json.JsonElement, depth + 1)
                    if (nested != null) return nested
                }
                null
            }
        }
    }

    private fun stringToKnownPayload(text: String, depth: Int): JsonObject? {
        val span = extractJsonSpan(text)
        val obj = tryParseObject(span) ?: return null
        val unwrapped = unwrapKnownPayload(obj, depth + 1)
        return unwrapped.takeIf { isKnownPayload(it) }
    }

    // -----------------------------------------------------------------
    // Parameter helpers
    // -----------------------------------------------------------------

    /** Returns the numeric array under [key] (may be nested inside a single-element list). */
    fun getNumberArray(map: JsonObject, key: String): List<Double> {
        val value = map[key] ?: return emptyList()
        val arr = (value as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
        if (arr.size == 1 && arr[0] is kotlinx.serialization.json.JsonArray) {
            return getNumberArray(arr[0].jsonArray)
        }
        return flattenNumbers(arr)
    }

    private fun getNumberArray(arr: kotlinx.serialization.json.JsonArray): List<Double> =
        flattenNumbers(arr)

    private fun flattenNumbers(arr: kotlinx.serialization.json.JsonArray): List<Double> {
        val result = mutableListOf<Double>()
        for (item in arr) {
            val num = (item as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
            result.add(num)
        }
        return result
    }

    private fun toMap(value: kotlinx.serialization.json.JsonElement?): JsonObject =
        value as? JsonObject ?: JsonObject(emptyMap())
}
