package com.clawmate.app.home.tasks

import com.clawmate.app.chat.cloud.*
import com.clawmate.app.datamanager.ConsolidationModelConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class HomeTaskSuggestionModelClient(private val chat: ChatClient, private val model: String) {
    suspend fun generate(context: HomeTaskContext): List<HomeSuggestedTask> {
        require(context.evidence.isNotEmpty()) { "没有可用的真实任务依据" }
        val reply = chat.completion(ChatCompletionsRequest(model, listOf(
            ChatMessageWire("system", HomeTaskSuggestions.systemPrompt), ChatMessageWire("user", context.payload.toString())),
            stream = false, maxTokens = 4096, temperature = 0.5))
        currentCoroutineContext().ensureActive()
        require(reply.finishReason == "stop" && reply.toolCalls.isEmpty()) { "任务建议未完整结束" }
        return HomeTaskSuggestions.parse(reply.content, UUID.randomUUID().toString(), context)
    }

    companion object {
        private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build() }
        fun fromConfig(config: ConsolidationModelConfig, httpClient: OkHttpClient = http): HomeTaskSuggestionModelClient {
            require(ConsolidationModelConfig.fromValues(config.baseUrl, config.apiKey, config.modelName) != null)
            return HomeTaskSuggestionModelClient(ChatCompletionsClient(httpClient,
                ChatServerConfig(config.baseUrl, config.apiKey, config.modelName)), config.modelName)
        }
    }
}
