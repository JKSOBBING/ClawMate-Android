package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig

/**
 * Production composition helper for the Phase-35 **explicit** model entity
 * enrichment seam. It wires the shared OpenAI-compatible [ChatClient] (the same
 * backend the chat screen, Home planner and Phase-34 consolidation use) behind an
 * [EntityEnrichmentModelClient], so an explicit user enrichment action can call:
 *
 * ```kotlin
 * val runner = EntityEnrichmentRunner(filesDir)
 * val result = runner.enrich(
 *     mode = EntityEnrichmentRunner.EnrichMode.ModelRequested(
 *         explicitAction = true,
 *         config = ProfileModelConfig.fromEndpointConfig(),
 *     ),
 *     clientProvider = { cfg -> EntityEnrichmentClientFactory.client(cfg) },
 * )
 * ```
 *
 * The [EntityEnrichmentRunner] already enforces the explicit gate (zero network
 * unless configured + an explicit action + real candidates), so wiring this into a
 * launch / Home load / background job still short-circuits to local-only. The
 * [client] factory only ever builds a model client for a fully-validated config,
 * so it is never invoked for an unconfigured feature.
 */
object EntityEnrichmentClientFactory {

    /** Build the real model client for a fully-configured consolidation config. */
    fun client(config: ConsolidationModelConfig): EntityEnrichmentModelClient {
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
    ): EntityEnrichmentModelClient {
        val chat: ChatClient = ChatCompletionsClient(
            httpClient,
            ChatServerConfig(baseUrl = config.baseUrl, apiKey = config.apiKey, modelName = config.modelName),
        )
        return EntityEnrichmentModelClient(chat, config)
    }
}
