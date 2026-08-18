package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig
import java.io.File

/**
 * Production composition helper for the Phase-34 **explicit** model consolidation
 * action. It wires the shared OpenAI-compatible [ChatClient] (the same
 * `ChatCompletionsClient` the chat screen, Home planner and Phase-32 profile
 * claims use) behind a [CloudConsolidationRepository], so a user-facing
 * "consolidate with model" action can be called with:
 *
 * ```kotlin
 * val repo = ConsolidationModelClientFactory.repository(filesDir)
 * val result = repo.consolidate(
 *     explicitAction = true,
 *     modelConfig = ConsolidationModelConfig.fromEndpointConfig(),
 *     modelClientProvider = ConsolidationModelClientFactory::client,
 * )
 * ```
 *
 * The repository itself already enforces the explicit gate (zero network when
 * `explicitAction` is false, config is null, or there are no entries), so wiring
 * this into a launch / Home load / background job would still short-circuit to
 * local-only. The [client] factory only ever builds a model client for a fully
 * validated config, so it is never invoked for an unconfigured feature.
 */
object ConsolidationModelClientFactory {

    /** Build the real model client for a fully-configured consolidation config. */
    fun client(config: ConsolidationModelConfig): ConsolidationModelClient {
        val httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return modelClient(httpClient, config)
    }

    /** Build a model client given an explicit HTTP client (test injectable). */
    fun modelClient(
        httpClient: okhttp3.OkHttpClient,
        config: ConsolidationModelConfig,
    ): ConsolidationModelClient {
        val chat: ChatClient = ChatCompletionsClient(
            httpClient,
            ChatServerConfig(baseUrl = config.baseUrl, apiKey = config.apiKey, modelName = config.modelName),
        )
        return ConsolidationModelClient(chat, config)
    }

    /** A repository over [baseDir] whose production model client derives from config. */
    fun repository(baseDir: File): CloudConsolidationRepository = CloudConsolidationRepository(baseDir)

    /**
     * Phase-38 — the explicit downstream coordinator that consumes the Phase-37
     * [FactSplitRunner] candidate facts into this model-aware repository and
     * persists outputs/index/profile. Production wiring: a Settings action
     * builds this coordinator over `filesDir` and calls [FactSplitConsolidationCoordinator.run]
     * on an explicit click.
     */
    fun factSplitConsolidation(baseDir: File): FactSplitConsolidationCoordinator =
        FactSplitConsolidationCoordinator(
            baseDir = baseDir,
            factSplitRunner = FactSplitRunner(baseDir),
            repository = CloudConsolidationRepository(baseDir),
        )
}
