package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig

/**
 * Production composition helper for the Phase-36 **explicit generic batch event
 * enrichment** seam. It wires the shared OpenAI-compatible [ChatClient] (the same
 * backend the chat screen, Home planner, Phase-34 consolidation and Phase-35
 * entity enrichment use) behind a [BatchEventEnrichModelClient], so an explicit
 * user enrich action can call:
 *
 * ```kotlin
 * val enricher = BatchEventEnricher(filesDir)
 * val result = enricher.enrich(
 *     mode = BatchEventEnricher.EnrichMode.ModelRequested(
 *         explicitAction = true,
 *         config = ProfileModelConfig.fromEndpointConfig(),
 *     ),
 *     clientProvider = { cfg -> BatchEventEnrichClientFactory.client(cfg) },
 * )
 * ```
 *
 * The [BatchEventEnricher] already enforces the explicit gate (zero network
 * unless configured + an explicit action + real events), so wiring this into a
 * launch / Home load / background job still short-circuits to local-only. The
 * [client] factory only ever builds a model client for a fully-validated config.
 */
object BatchEventEnrichClientFactory {

    /** Build the real model client for a fully-configured consolidation config. */
    fun client(config: ConsolidationModelConfig): BatchEventEnrichModelClient {
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
    ): BatchEventEnrichModelClient {
        val chat: ChatClient = ChatCompletionsClient(
            httpClient,
            ChatServerConfig(baseUrl = config.baseUrl, apiKey = config.apiKey, modelName = config.modelName),
        )
        return BatchEventEnrichModelClient(chat, config)
    }
}
