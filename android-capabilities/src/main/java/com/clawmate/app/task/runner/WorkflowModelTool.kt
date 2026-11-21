package com.clawmate.app.task.runner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import com.clawmate.app.task.json.WorkflowJsonRepair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.util.UUID

/** Host owns transport/consent; the reusable runner owns ordering and budgets. */
data class WorkflowModelRequest(val id: String, val mode: String, val question: String, val image: String, val schema: JsonObject?, val captureDay: String = "", val captureOnly: Boolean = false)
fun interface WorkflowModelTool { suspend fun evaluate(request: WorkflowModelRequest): String }

/** Resolve provider field aliases and common response envelopes before conditions read the result. */
internal fun workflowStructuredOutput(response: String, schema: JsonObject): JsonObject {
    fun objectFrom(text: String) = Json.parseToJsonElement(WorkflowJsonRepair.stripFencing(text)) as? JsonObject
    val output = requireNotNull(objectFrom(response)) { "模型未返回要求的JSON对象" }
    if (schema.isEmpty()) return output
    fun key(name: String) = name.filterNot { it.isWhitespace() || it == '-' || it == '_' }.lowercase()
    val fields = schema.keys.associateBy(::key)
    fun matches(value: JsonObject) = value.keys.any { key(it) in fields }
    val source = if (matches(output)) output else sequenceOf("structured_output", "structuredOutput", "output", "result", "data", "response", "content", "message")
        .mapNotNull { name -> when (val nested = output[name]) {
            is JsonObject -> nested
            is JsonPrimitive -> runCatching { objectFrom(nested.content) }.getOrNull()
            else -> null
        } }.firstOrNull(::matches) ?: output
    val result = source.toMutableMap()
    for ((name, declaration) in schema) {
        val actual = source.keys.firstOrNull { it == name } ?: source.keys.firstOrNull { key(it) == key(name) } ?: continue
        val spec = declaration as? JsonObject ?: continue
        val type = (spec["type"] as? JsonPrimitive)?.content
        var value: JsonElement = source.getValue(actual)
        if (type == "string" && value is JsonObject) {
            // Some vision models put the transcription in the schema-shaped description field.
            value = value["value"] ?: value["description"]?.takeUnless { it == spec["description"] }
                ?: value["text"] ?: value["content"]
                ?: throw IllegalArgumentException("视觉模型仅回显字段定义: $name")
            require(value is JsonPrimitive && value.isString) { "视觉字段应为文本: $name" }
        }
        if (value is JsonPrimitive) {
            value = when (type) {
                "boolean" -> when (value.content.trim().lowercase()) {
                    "true", "yes", "y", "1" -> JsonPrimitive(true)
                    "false", "no", "n", "0" -> JsonPrimitive(false)
                    else -> value
                }
                "number", "integer" -> value.content.toLongOrNull()?.let(::JsonPrimitive)
                    ?: value.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: value
                "string" -> JsonPrimitive(value.content)
                else -> value
            }
        }
        if (actual != name) result.remove(actual)
        result[name] = value
    }
    return JsonObject(result)
}

/** One outstanding request per run. Replies cannot cross runs or be replayed. */
class WorkflowModelMailbox : WorkflowModelTool, Closeable {
    private data class Pending(val request: WorkflowModelRequest, val reply: CompletableDeferred<String>)
    private val turn = Mutex()
    private var pending: Pending? = null
    private var closed = false
    @Synchronized fun peek(): WorkflowModelRequest? = pending?.takeUnless { it.reply.isCompleted }?.request
    @Synchronized fun reply(id: String, response: String, error: String): Boolean {
        val current = pending ?: return false
        if (closed || current.request.id != id || current.reply.isCompleted) return false
        require(response.length <= 16000 && error.length <= 500) { "模型结果超出限制" }
        return if (error.isNotEmpty()) current.reply.completeExceptionally(IllegalStateException(error))
            else current.reply.complete(response)
    }
    override suspend fun evaluate(request: WorkflowModelRequest): String = turn.withLock {
        val current = synchronized(this) {
            check(!closed && pending == null) { "模型请求已取消或仍在执行" }
            Pending(request.copy(id = UUID.randomUUID().toString()), CompletableDeferred()).also { pending = it }
        }
        try { current.reply.await() }
        finally { synchronized(this) { if (pending === current) pending = null } }
    }
    @Synchronized override fun close() {
        closed = true
        pending?.reply?.cancel()
        pending = null
    }
}
