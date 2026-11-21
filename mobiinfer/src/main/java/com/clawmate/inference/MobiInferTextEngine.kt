package com.clawmate.inference

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/** Synchronous, CPU-backed GGUF inference. The caller owns scheduling and message storage. */
object MobiInferTextEngine {
    @Synchronized
    fun generate(
        model: File,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        threads: Int,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ) {
        require(model.isFile && model.canRead()) { "无法读取 GGUF 模型，请重新选择模型。" }
        require(messages.lastOrNull()?.first == "user") { "最后一条消息必须来自用户。" }
        require(messages.all { (role, text) ->
            role in setOf("system", "user", "assistant") && '\u0000' !in text
        }) { "本地引擎收到不支持的消息格式。" }
        require(threads > 0 && maxTokens > 0)
        fun checkActive() {
            if (cancelled()) throw CancellationException("本地生成已取消")
        }
        checkActive()
        val handle = NativeGguf.open(model.absolutePath.toByteArray(), threads, ::checkActive)
        try {
            val history = messages.toMutableList()
            var available: Int
            while (true) {
                checkActive()
                available = NativeGguf.prompt(handle, history.map { it.first.toByteArray() }.toTypedArray(),
                    history.map { it.second.toByteArray() }.toTypedArray())
                if (available >= maxTokens) break
                // Retain the system prompt and current turn. Remove an entire old user turn,
                // including its assistant continuations, before asking the tokenizer again.
                val first = history.indexOfFirst { it.first == "user" }
                if (first < 0 || first == history.lastIndex) break
                val next = (first + 1..history.lastIndex).first { history[it].first == "user" }
                history.subList(first, next).clear()
            }
            require(available > 0) { "当前问题和系统提示超过模型上下文，请缩短后再试。" }
            do {
                checkActive()
            } while (NativeGguf.prefillBatch(handle))

            // A GGUF token can end in the middle of a UTF-8 scalar. Retain incomplete
            // bytes until the next piece and send the host only decoded text deltas.
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            var pending = ByteArray(0)
            var nonBlank = false
            fun emit(bytes: ByteArray, end: Boolean) {
                val input = ByteBuffer.wrap(pending + bytes)
                val chars = CharBuffer.allocate(input.remaining() + 1)
                decoder.decode(input, chars, end)
                pending = ByteArray(input.remaining()).also { input.get(it) }
                if (end) decoder.flush(chars)
                chars.flip()
                val delta = chars.toString()
                if (delta.isNotEmpty()) {
                    nonBlank = nonBlank || delta.isNotBlank()
                    onText(delta)
                }
            }
            for (i in 0 until minOf(maxTokens, available)) {
                checkActive()
                val piece = NativeGguf.next(handle) ?: break
                emit(piece, false)
            }
            checkActive()
            emit(ByteArray(0), true)
            check(nonBlank) { "模型未生成可显示的文字。" }
        } finally {
            NativeGguf.close(handle)
        }
    }
}

internal object NativeGguf {
    init { System.loadLibrary("clawmate_llama") }
    external fun open(model: ByteArray, threads: Int, checkActive: Runnable): Long
    external fun prompt(handle: Long, roles: Array<ByteArray>, texts: Array<ByteArray>): Int
    external fun prefillBatch(handle: Long): Boolean
    external fun next(handle: Long): ByteArray?
    external fun close(handle: Long)
}
