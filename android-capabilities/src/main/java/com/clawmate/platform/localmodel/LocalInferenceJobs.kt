package com.clawmate.platform.localmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** Synchronous streaming port. Each callback supplies the next decoded text fragment. */
fun interface LocalTextGenerator {
    fun generate(model: File, messages: List<Pair<String, String>>, threads: Int,
                 cancelled: () -> Boolean, onText: (String) -> Unit)
}

/** Owns one in-flight generation and at most one transcript, scoped to the host lifecycle. */
class LocalInferenceJobs(
    private val resolveModel: (String) -> File,
    private val engine: LocalTextGenerator,
) : Closeable {
    data class Snapshot(val state: String, val text: String = "", val error: String = "")

    private class Request(val id: String, val modelId: String) {
        val output = StringBuilder()
        @Volatile var cancelled = false
        var state = "running"
        var error = ""
        var finished = false
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var current: Request? = null
    private var closed = false

    @Synchronized
    fun start(requestId: String, modelId: String, messagesJson: String, threads: Int): String {
        check(!closed) { "本地推理已关闭" }
        check(current?.finished != false) { "上一次本地推理仍在运行或停止中，请稍后再试" }
        require(requestId.length in 1..96 && current?.id != requestId) { "本地推理请求标识无效" }
        require(threads in 1..8) { "推理线程数应为 1 至 8" }
        val messages = parseMessages(messagesJson)
        val request = Request(requestId, modelId)
        current = request
        worker.execute {
            var failure = "模型未能完成推理"
            try {
                if (!request.cancelled) {
                    engine.generate(resolveModel(modelId), messages, threads, { request.cancelled }) { delta ->
                        synchronized(this) {
                            if (!request.cancelled) request.output.append(delta)
                        }
                    }
                }
                failure = ""
            } catch (e: Exception) {
                failure = e.message?.take(240)?.takeIf { it.isNotBlank() } ?: "模型未能完成推理"
            } catch (_: LinkageError) {
                failure = "当前设备无法加载本地推理库，请检查应用安装包和 CPU 架构"
            } finally {
                synchronized(this) {
                    if (!request.cancelled) {
                        if (failure.isEmpty() && request.output.isBlank()) failure = "模型没有生成文本"
                        request.state = if (failure.isEmpty()) "done" else "error"
                        request.error = failure
                    }
                    request.finished = true
                }
            }
        }
        return request.id
    }

    @Synchronized
    fun poll(id: String): Snapshot {
        val request = current?.takeIf { it.id == id } ?: return Snapshot("error", error = "本地推理请求已结束")
        return Snapshot(request.state, request.output.toString(), request.error)
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        val request = current?.takeIf { it.id == id && !it.finished } ?: return false
        request.cancelled = true
        request.state = "cancelled"
        return true
    }

    @Synchronized
    fun cancelCurrent() { current?.let { cancel(it.id) } }

    @Synchronized
    fun uses(modelId: String): Boolean = current?.let { !it.finished && it.modelId == modelId } == true

    @Synchronized
    override fun close() {
        closed = true
        cancelCurrent()
        worker.shutdown()
    }

    private fun parseMessages(raw: String): List<Pair<String, String>> {
        require(raw.length <= 192_000) { "本次输入太长，请缩短问题或新建会话" }
        val array = Json.parseToJsonElement(raw) as? JsonArray ?: error("本地聊天消息格式无效")
        require(array.size in 1..17 && array.size % 2 == 1) { "本地聊天历史须由完整问答和本次问题组成" }
        return array.mapIndexed { index, element ->
            val message = element as? JsonObject ?: error("本地聊天消息格式无效")
            val role = if (index % 2 == 0) "user" else "assistant"
            val content = message["content"] as? JsonPrimitive ?: error("本地推理仅支持文字消息")
            require(message["role"] == JsonPrimitive(role) && content.isString && content.content.isNotBlank()) {
                "本地推理仅支持用户和助手的文字消息"
            }
            role to content.content
        }
    }
}
