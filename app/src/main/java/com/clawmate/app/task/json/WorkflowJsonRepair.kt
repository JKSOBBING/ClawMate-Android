package com.clawmate.app.task.json

/**
 * Deterministic text-level JSON repair, ported from HarmonyOS
 * `utils/WorkflowJsonRepair.ets` (reference only). A faithful, bounded subset
 * of the source heuristics is provided to make workflow config loading robust
 * against the malformed JSON a model/LLM or a hand-edited file can produce.
 *
 * All functions are pure / deterministic and unit-tested.
 */
object WorkflowJsonRepair {

    /**
     * Best-effort normalisation of a raw workflow JSON string before it is
     * handed to a real JSON parser: strips fenced blocks, extracts the first
     * top-level JSON object, closes unclosed brackets/strings, normalises CJK
     * punctuation and repair JSON literal casing. Returns the original text if
     * nothing can be done.
     */
    fun normalizeJsonText(raw: String): String {
        var text = raw.trim()
        text = stripFencing(text)
        text = repairJsonLiteralCasing(text)
        text = normalizeCjkJsonPunctuation(text)
        text = extractFirstJsonObjectText(text)
        text = closeTruncatedJson(text)
        return text.trim()
    }

    /** Removes a wrapping ```json ... ``` fence if present. */
    fun stripFencing(text: String): String {
        val trimmed = text.trim()
        val regex = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)
        return (match?.groupValues?.getOrNull(1) ?: trimmed).trim()
    }

    /**
     * Extracts the substring of the first top-level JSON object (balanced
     * braces, string-aware), mirroring extractFirstJsonObjectText. If no
     * balanced object exists, returns everything from the first '{' to the
     * last '}' (or to the end).
     */
    fun extractFirstJsonObjectText(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) return text
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        val end = text.lastIndexOf('}')
        return if (end > start) text.substring(start, end + 1) else text.substring(start)
    }

    /** Normalises full-width/CJK punctuation used in casual JSON. */
    fun normalizeCjkJsonPunctuation(text: String): String =
        text.replace('“', '"').replace('”', '"')
            .replace("，", ",")
            .replace("：", ":")

    /**
     * Repairs incorrect JSON literal casing outside of strings
     * (`TRUE`/`True` -> `true`, `NONE` -> `null`), ported from
     * repairJsonLiteralCasing.
     */
    fun repairJsonLiteralCasing(text: String): String {
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                sb.append(ch)
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                i++
                continue
            }
            if (ch == '"') {
                inString = true
                sb.append(ch)
                i++
                continue
            }
            if (ch.isLetter()) {
                val start = i
                while (i < text.length && text[i].isLetter()) i++
                val word = text.substring(start, i)
                sb.append(
                    when (word.lowercase()) {
                        "true", "false", "null" -> word.lowercase()
                        "none" -> "null"
                        else -> word
                    }
                )
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /**
     * Closes truncated JSON by balancing brackets and closing an unclosed
     * string, ported from closeTruncatedJson. Returns the input unchanged if
     * it does not start with an object/array or if there is an unmatched
     * closer.
     */
    fun closeTruncatedJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return trimmed
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isEmpty() || stack.last() != ch) return trimmed
                    stack.removeLast()
                }
            }
        }
        if (trimmed.endsWith(":")) return trimmed
        var repaired = trimmed
        if (repaired.endsWith(",")) repaired = repaired.dropLast(1).trim()
        if (inString) {
            if (escaped) repaired += "\\"
            repaired += "\""
        }
        while (stack.isNotEmpty()) {
            repaired = repaired.replace(Regex(",\\s*$"), "")
            repaired += stack.removeLast()
        }
        return repaired
    }
}
