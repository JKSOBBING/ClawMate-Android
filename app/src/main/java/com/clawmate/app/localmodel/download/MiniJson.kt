package com.clawmate.app.localmodel.download

/**
 * 最小 JSON 读写工具：与 Android 平台解耦，纯 JVM 单元测试可直接覆盖下载清单解析。
 */
object MiniJson {
    fun parse(text: String): Any? = runCatching { Reader(text).value() }.getOrNull()

    fun write(value: Any?, pretty: Boolean = false): String =
        StringBuilder().also { writeValue(it, value, pretty, 0) }.toString()

    private fun writeValue(out: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(quote(value))
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString())
            is Float -> writeValue(out, value.toDouble(), pretty, depth)
            is Map<*, *> -> {
                if (value.isEmpty()) { out.append("{}"); return }
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    newline(out, pretty, depth + 1)
                    out.append(quote(key.toString())).append(':')
                    if (pretty) out.append(' ')
                    writeValue(out, item, pretty, depth + 1)
                }
                newline(out, pretty, depth); out.append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) { out.append("[]"); return }
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    newline(out, pretty, depth + 1)
                    writeValue(out, item, pretty, depth + 1)
                }
                newline(out, pretty, depth); out.append(']')
            }
            else -> out.append(quote(value.toString()))
        }
    }

    private fun newline(out: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        out.append('\n').append("  ".repeat(depth))
    }

    private fun quote(text: String): String {
        val out = StringBuilder("\"")
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 32) out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun value(): Any? {
            skip()
            return when (peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun peek(): Char {
            require(index < text.length) { "JSON 提前结束" }
            return text[index]
        }

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(c: Char) {
            skip()
            require(index < text.length && text[index] == c) { "JSON 期望字符 $c" }
            index++
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            expect('{'); skip()
            if (peek() == '}') { index++; return out }
            while (true) {
                skip()
                val key = string()
                expect(':')
                out[key] = value()
                skip()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return out }
                    else -> error("JSON 对象格式错误")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            expect('['); skip()
            if (peek() == ']') { index++; return out }
            while (true) {
                out.add(value())
                skip()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return out }
                    else -> error("JSON 数组格式错误")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(index < text.length) { "JSON 字符串未结束" }
                val c = text[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < text.length) { "JSON 转义未结束" }
                        when (val escape = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "JSON unicode 转义不完整" }
                                out.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> error("JSON 转义无效: $escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, index)) { "JSON 字面量错误" }
            index += word.length
            return value
        }

        private fun number(): Any {
            val start = index
            while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
            val raw = text.substring(start, index)
            require(raw.isNotEmpty()) { "JSON 数值错误" }
            return raw.toLongOrNull() ?: raw.toDouble()
        }
    }
}
