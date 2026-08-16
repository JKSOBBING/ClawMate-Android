package com.clawmate.app.profile.avatar.data.generation

import java.util.Base64

/**
 * Pure, defensive parser for the Seedream generation JSON response, ported from
 * `seedreamFirstDataString` / `normalizeSeedreamImageUrl` /
 * `normalizeSeedreamImageBase64` in `SeedreamAvatarProvider.ets`.
 *
 * The response shape is `{ "id"?, "data": [ { "url"?, "b64_json"? } ] }`. Only
 * the **first** data item's `url` / `b64_json` strings are honoured and they are
 * returned `trim()`-ed exactly like the Harmony source. A real image URL is
 * returned as-is by [seedreamImageUrl]; a base64 payload is returned as-is by
 * [seedreamImageBase64] and separately validated/decoded via [decodeBoundedBase64].
 *
 * Never performs network I/O; deterministic and unit-testable.
 */
object SeedreamResponseParser {

    /**
     * Parse the raw JSON into a result. [imageUrl] is the trimmed first `url`,
     * [imageBase64] the trimmed first `b64_json` (not yet decoded — bounded
     * decode happens in [decodeBoundedBase64]), and [providerRequestId] the
     * top-level `id` when present.
     */
    fun parse(raw: String): ParseResult {
        val root = JsonValue.tryParse(raw) ?: return ParseResult()
        val data = root["data"]?.asArray() ?: return ParseResult(providerRequestId = firstString(root["id"]))
        val first = data.firstOrNull() ?: return ParseResult(providerRequestId = firstString(root["id"]))
        return ParseResult(
            imageUrl = firstString(first["url"]),
            imageBase64 = firstString(first["b64_json"]),
            providerRequestId = firstString(root["id"]),
        )
    }

    /** The trimmed `data[0].url` string (empty when absent / not a string). */
    fun seedreamImageUrl(raw: String): String = parse(raw).imageUrl

    /** The trimmed `data[0].b64_json` string (empty when absent / not a string). */
    fun seedreamImageBase64(raw: String): String = parse(raw).imageBase64

    /**
     * Decode a bounded base64 image payload. Returns `null` when the string is
     * blank, exceeds [AvatarProviderLimits.MAX_BASE64_LENGTH], or does not
     * actually decode as base64. The decoded byte array is additionally bounded
     * to [AvatarProviderLimits.MAX_IMAGE_BYTES]. Treats any malformed base64 as
     * untrusted input and rejects it.
     */
    fun decodeBoundedBase64(encoded: String): ByteArray? {
        val s = encoded.trim()
        if (s.isEmpty()) return null
        if (s.length > AvatarProviderLimits.MAX_BASE64_LENGTH) return null
        val bytes = try {
            // Strip a possible `data:image/...;base64,` prefix defensively.
            val payload = s.substringAfter(",", s)
            Base64.getDecoder().decode(payload)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.size <= 0 || bytes.size > AvatarProviderLimits.MAX_IMAGE_BYTES) return null
        return bytes
    }

    /** Parsed result holder; [imageUrl]/[imageBase64] are pre-trimmed strings. */
    data class ParseResult(
        val imageUrl: String = "",
        val imageBase64: String = "",
        val providerRequestId: String = "",
    )

    private fun firstString(node: JsonValue?): String =
        node?.asString()?.trim() ?: ""

    /** Minimal immutable JSON value tree (no bindings / no codec). */
    private sealed class JsonValue {
        abstract fun asString(): String?
        abstract fun asArray(): List<JsonValue>?
        abstract operator fun get(key: String): JsonValue?

        data class Str(val value: String) : JsonValue() {
            override fun asString() = value
            override fun asArray() = null
            override fun get(key: String) = null
        }

        object Null : JsonValue() {
            override fun asString() = null
            override fun asArray() = null
            override fun get(key: String) = null
        }

        data class Obj(val map: Map<String, JsonValue>) : JsonValue() {
            override fun asString() = null
            override fun asArray() = null
            override fun get(key: String) = map[key]
        }

        data class Arr(val list: List<JsonValue>) : JsonValue() {
            override fun asString() = null
            override fun asArray() = list
            override fun get(key: String) = null
        }

        object Unparsed : JsonValue() {
            override fun asString() = null
            override fun asArray() = null
            override fun get(key: String) = null
        }

        companion object {
            fun tryParse(text: String): JsonValue? {
                val trimmed = text.trim()
                if (trimmed.isEmpty() || trimmed.first() != '{') return null
                return parseValue(Cursor(trimmed))?.takeIf {
                    it !is Unparsed
                }
            }

            private fun parseValue(c: Cursor): JsonValue? {
                c.skipWs()
                return when {
                    c.eof -> Unparsed
                    c.peek() == '{' -> {
                        c.advance()
                        val map = LinkedHashMap<String, JsonValue>()
                        c.skipWs()
                        if (c.peek() == '}') {
                            c.advance(); return Obj(map)
                        }
                        while (true) {
                            c.skipWs()
                            val key = parseString(c)
                            if (key == null) {
                                consumeGarbageUntil(c, '}')
                                return Obj(map)
                            }
                            c.skipWs()
                            if (c.eof || c.peek() != ':') {
                                consumeGarbageUntil(c, '}')
                                return Obj(map)
                            }
                            c.advance()
                            c.skipWs()
                            val value = parseValue(c) ?: Null
                            map[key] = value
                            c.skipWs()
                            when {
                                c.eof -> return Obj(map)
                                c.peek() == ',' -> c.advance()
                                c.peek() == '}' -> {
                                    c.advance()
                                    return Obj(map)
                                }
                                else -> consumeGarbageUntil(c, ',')
                            }
                        }
                        return Obj(map)
                    }
                    c.peek() == '[' -> {
                        c.advance()
                        val list = ArrayList<JsonValue>()
                        c.skipWs()
                        if (c.peek() == ']') {
                            c.advance()
                            return Arr(list)
                        }
                        while (true) {
                            c.skipWs()
                            val value = parseValue(c) ?: Null
                            list.add(value)
                            c.skipWs()
                            when {
                                c.eof -> return Arr(list)
                                c.peek() == ',' -> c.advance()
                                c.peek() == ']' -> {
                                    c.advance()
                                    return Arr(list)
                                }
                                else -> consumeGarbageUntil(c, ',')
                            }
                        }
                        return Arr(list)
                    }
                    c.peek() == '"' -> Str(parseString(c) ?: "")
                    c.peek() == 't' -> { c.consumeWord("true"); Str("") }
                    c.peek() == 'f' -> { c.consumeWord("false"); Str("") }
                    c.peek() == 'n' -> { c.consumeWord("null"); Null }
                    else -> parseNumberOrGarbage(c)
                }
            }

            private fun parseString(c: Cursor): String? {
                c.skipWs()
                if (c.eof || c.peek() != '"') return null
                c.advance()
                val sb = StringBuilder()
                while (!c.eof) {
                    val ch = c.peek()
                    when {
                        ch == '"' -> { c.advance(); return sb.toString() }
                        ch == '\\' -> {
                            c.advance()
                            if (c.eof) return sb.toString()
                            val esc = c.peek()
                            c.advance()
                            when (esc) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'u' -> {
                                    val hex = c.take(4)
                                    sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                }
                                else -> sb.append(esc)
                            }
                        }
                        else -> { sb.append(ch); c.advance() }
                    }
                }
                return sb.toString()
            }

            private fun parseNumberOrGarbage(c: Cursor): JsonValue {
                val start = c.index
                while (!c.eof && (c.peek().isDigit() || c.peek() == '-' || c.peek() == '+' ||
                        c.peek() == '.' || c.peek() == 'e' || c.peek() == 'E')) {
                    c.advance()
                }
                if (c.index == start) {
                    consumeGarbageUntil(c, ',')
                    return Null
                }
                return Null
            }

            private fun consumeGarbageUntil(c: Cursor, stop: Char) {
                while (!c.eof && c.peek() != stop) c.advance()
            }
        }
    }

    private class Cursor(private val text: String) {
        var index = 0
        val eof get() = index >= text.length
        fun peek(): Char = text[index]
        fun advance() { index++ }
        fun skipWs() { while (!eof && text[index].isWhitespace()) index++ }
        fun take(n: Int): String {
            val end = (index + n).coerceAtMost(text.length)
            val s = text.substring(index, end)
            index = end
            return s
        }
        fun consumeWord(word: String) {
            if (text.startsWith(word, index)) index += word.length
        }
    }
}
