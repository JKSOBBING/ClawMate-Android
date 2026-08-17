package com.clawmate.app.profile.affinity

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Minimal, testable manual factory for the profile-affinity analyzer.
 *
 * There is no DI framework in this skeleton; this object lazily builds an
 * [OkHttpClient] using the **exact same construction** the existing chat feature
 * uses (`ChatComponent.httpClient`), wraps it in the shared
 * [ChatCompletionsClient], and returns a [ProfileAffinityAnalyzer]. It never
 * hard-codes an endpoint, token or model — those come from the validated
 * [ProfileAffinityConfig].
 *
 * The Activity depends on this factory rather than writing any network logic
 * itself. Tests inject a fake [ChatClient] directly into [ProfileAffinityAnalyzer],
 * so the factory only needs to compile; its real network path is exercised by
 * the shared chat client.
 */
object ProfileAffinityFactory {

    /**
     * Build an analyzer for [config]. When [chat] is provided (tests), it is
     * used as the backend; otherwise the real OkHttp-backed
     * [ChatCompletionsClient] is constructed for the configured Planner backend.
     */
    fun create(config: ProfileAffinityConfig, chat: ChatClient? = null): ProfileAffinityAnalyzer {
        val backend = chat ?: buildOkHttpClient(config)
        return ProfileAffinityAnalyzer(backend, config)
    }

    private fun buildOkHttpClient(config: ProfileAffinityConfig): ChatCompletionsClient {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        // Reuse the same OpenAI-compatible chat backend the chat screen uses.
        val chatServerConfig = ChatServerConfig(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.modelName,
        )
        return ChatCompletionsClient(httpClient, chatServerConfig)
    }
}
