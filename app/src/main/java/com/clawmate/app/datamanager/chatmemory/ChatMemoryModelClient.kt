package com.clawmate.app.datamanager.chatmemory

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatCompletionsRequest
import com.clawmate.app.chat.cloud.ChatMessageWire
import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.datamanager.ConsolidationModelConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient

/** Only invalid output is retried; transport, access and cancellation errors propagate unchanged. */
class ChatMemoryModelClient(private val chat: ChatClient, private val model: String) : ChatMemoryExtractor {
    init { require(model.isNotBlank()) { "未配置聊天记忆提取模型" } }

    override suspend fun extract(inputs: List<ChatMemoryInput>): List<ChatMemoryResult> {
        if (inputs.isEmpty()) return emptyList()
        require(ChatMemoryExtraction.batches(inputs).size == 1) { "聊天提取请求超出单批限制" }
        val prompt = ChatMemoryExtraction.prompt(inputs)
        var failure: ChatMemoryResponseException? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            val reply = chat.completion(ChatCompletionsRequest(
                model = model,
                messages = listOf(ChatMessageWire("system", prompt.system + if (attempt == 0) "" else
                    "上次输出未满足格式要求；请返回完整 JSON，保留全部 source_ref，不要输出解释。"),
                    ChatMessageWire("user", prompt.user)),
                stream = false, maxTokens = 8192, temperature = 0.1,
            ))
            currentCoroutineContext().ensureActive()
            try {
                if (reply.finishReason != "stop" || reply.toolCalls.isNotEmpty()) {
                    throw ChatMemoryResponseException("聊天提取结果未完整结束或包含不支持的工具调用")
                }
                return ChatMemoryExtraction.parseResults(unfence(reply.content), inputs)
            } catch (e: ChatMemoryResponseException) {
                failure = e
            }
        }
        throw failure ?: ChatMemoryResponseException("聊天信息提取失败，请重试")
    }

    /** Accept a single surrounding code fence without repairing or changing JSON string contents. */
    private fun unfence(text: String): String {
        val lines = text.trim().lines()
        return if (lines.size >= 3 && Regex("```(?:json)?", RegexOption.IGNORE_CASE).matches(lines.first().trim()) &&
            lines.last().trim() == "```") lines.drop(1).dropLast(1).joinToString("\n") else text.trim()
    }
}

object ChatMemoryModelClientFactory {
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build() }

    fun client(config: ConsolidationModelConfig, httpClient: OkHttpClient = http): ChatMemoryExtractor {
        require(ConsolidationModelConfig.fromValues(config.baseUrl, config.apiKey, config.modelName) != null) {
            "请先保存完整的云端模型地址、密钥和模型名称"
        }
        return ChatMemoryModelClient(ChatCompletionsClient(httpClient,
            ChatServerConfig(config.baseUrl, config.apiKey, config.modelName)), config.modelName)
    }
}
