package com.clawmate.platform.mika.memory

import com.wangzan101.mika.core.agent.Turn
import kotlinx.serialization.json.*
import java.io.File

/** Read only this host's saved user/assistant pairs; no model-supplied quote or proposal. */
object MikaConversationHistory {
    fun read(filesDir: File, conversation: String): List<Turn> {
        if (!Regex("chat-[0-9]+").matches(conversation)) return emptyList()
        val file = File(filesDir, "files/chat-sessions/sessions/$conversation.json")
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    fun decode(raw: String): List<Turn> {
        val messages = Json.parseToJsonElement(raw).jsonObject["messages"]?.jsonArray.orEmpty()
        val turns = mutableListOf<Turn>()
        var user: String? = null
        for (element in messages) {
            val m = element.jsonObject
            fun str(key: String) = m[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            val role = str("role")
            val plain = str("kind") in setOf("", "chat") && str("imagePath").isEmpty() &&
                m["imagePaths"]?.jsonArray.orEmpty().isEmpty()
            if (role == "user") {
                if (user != null || !plain) turns.clear()
                user = str("content").takeIf { plain && it.length <= 32000 }
            }
            else if (role == "assistant") {
                val content = str("content")
                val trace = m["trace"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonPrimitive.content }
                if (user != null && plain && content.isNotBlank() && content.length <= 32000 &&
                    m["streamingActive"]?.jsonPrimitive?.booleanOrNull != true) {
                    val failed = trace.contains("请求未完成") || trace.contains("已取消") || trace.contains("任务已停止")
                    turns += Turn(user, content, if (failed) "failed" else "unverified")
                } else turns.clear()
                user = null
            } else { user = null; turns.clear() }
        }
        return turns.takeLast(8)
    }
}
