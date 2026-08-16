package com.clawmate.app.gallery.cloud

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.gallery.insights.GalleryMonthlyInsightCloudState
import com.clawmate.app.settings.domain.EmbeddingMode

/**
 * The config-driven gate + executor for the optional cloud portions of Gallery
 * monthly insights (migration rows D1 remote embedding, D2 AI month summary).
 *
 * This is the single place a caller asks "is this configured, and can I get a
 * real cloud result?" — it never fabricates a summary or a vector, never calls
 * the network unless a feature is explicitly enabled and fully configured, and
 * never infers credentials from a subscription.
 *
 * [summaryState] / [embeddingState] are synchronous, deterministic gate states:
 *   - NOT_CONFIGURED — the feature is off and/or endpoint/model/key missing.
 *   - UNSUPPORTED — the selected embedding mode has no Android backend
 *     (huawei_local / remote is not really usable without config).
 *   - READY — fully configured and an executor would fire.
 *
 * [generateMonthlySummary] / [embedText] are the only code paths that can touch
 * the network, and both short-circuit with NOT_CONFIGURED / UNSUPPORTED before
 * any I/O when the feature is not configured. Callers that want to avoid any
 * background upload must **not** auto-invoke these without an explicit action.
 */
class GalleryCloudAdapter(
    private val summaryConfig: GalleryMonthlySummaryConfig?,
    private val summaryClientFactory: (GalleryMonthlySummaryConfig) -> GalleryMonthlySummaryClient,
    private val embeddingConfig: GalleryEmbeddingConfig?,
    private val embeddingClientFactory: (GalleryEmbeddingConfig) -> GalleryEmbeddingClient,
    private val embeddingModeUnsupported: Boolean,
) {
    // ------------------------------------------------------------------ gates

    val summaryState: GalleryMonthlyInsightCloudState =
        if (summaryConfig?.chatReady == true) {
            GalleryMonthlyInsightCloudState.Ready("云端月度总结已配置")
        } else {
            GalleryMonthlyInsightCloudState.NotConfigured
        }

    val embeddingState: GalleryMonthlyInsightCloudState =
        when {
            embeddingConfig != null -> GalleryMonthlyInsightCloudState.Ready("远程向量已配置")
            embeddingModeUnsupported -> GalleryMonthlyInsightCloudState.Unsupported
            else -> GalleryMonthlyInsightCloudState.NotConfigured
        }

    // --------------------------------------------------------------- executors
    // These are the only network-reachable paths. They short-circuit to a
    // truthful error when not configured so no unexpected call can fire.

    /** Produces a real AI month summary, or throws [GalleryCloudException]. */
    suspend fun generateMonthlySummary(monthAggregateText: String): GalleryMonthSummaryResult {
        val config = summaryConfig ?: throw GalleryCloudException(
            reason = GalleryCloudErrorReason.EMPTY_INPUT,
            message = "Monthly summary is not configured",
        )
        if (!config.chatReady) {
            throw GalleryCloudException(
                reason = GalleryCloudErrorReason.EMPTY_INPUT,
                message = "Monthly summary is not configured",
            )
        }
        return summaryClientFactory(config).summarize(monthAggregateText)
    }

    /** Produces a real remote embedding, or throws [GalleryCloudException]. */
    suspend fun embedText(text: String): GalleryEmbeddingResult {
        val config = embeddingConfig ?: throw GalleryCloudException(
            reason = GalleryCloudErrorReason.EMPTY_INPUT,
            message = "Embedding is not configured",
        )
        return embeddingClientFactory(config).embed(text)
    }

    companion object {
        /**
         * Build the adapter from the persisted [AppSettings] and the runtime
         * OpenAI-compatible planner endpoint (the existing chat backend). No new
         * credentials are introduced: the AI summary reuses the planner URL/key/
         * model and the embedding reuses the existing gallery embedding settings.
         *
         * Client construction is injected so unit tests run device-free; the
         * production wiring supplies a real [okhttp3.OkHttpClient] and the app's
         * existing [com.clawmate.app.chat.cloud.ChatCompletionsClient] as a
         * [ChatClient] for the AI month summary.
         */
        fun fromSettings(
            settings: com.clawmate.app.settings.domain.AppSettings,
            plannerBaseUrl: String,
            plannerApiKey: String,
            plannerModel: String,
            httpClient: okhttp3.OkHttpClient,
            chatClient: com.clawmate.app.chat.cloud.ChatClient,
        ): GalleryCloudAdapter {
            val summaryConfig = GalleryMonthlySummaryConfig.fromSettings(
                enabled = settings.galleryAiSummaryEnabled,
                baseUrl = plannerBaseUrl,
                apiKey = plannerApiKey,
                modelName = plannerModel,
            )
            val embeddingConfig = GalleryEmbeddingConfig.fromSettings(
                mode = settings.galleryEmbeddingMode,
                baseUrl = settings.galleryEmbeddingBaseUrl,
                apiKey = settings.galleryEmbeddingApiKey,
                modelName = settings.galleryEmbeddingModel,
            )
            val modeUnsupported = settings.galleryEmbeddingMode == EmbeddingMode.HuaweiLocal
            return GalleryCloudAdapter(
                summaryConfig = summaryConfig,
                summaryClientFactory = { config ->
                    GalleryMonthlySummaryClient(chatClient, config)
                },
                embeddingConfig = embeddingConfig,
                embeddingClientFactory = { config ->
                    GalleryEmbeddingClient(httpClient, config)
                },
                embeddingModeUnsupported = modeUnsupported,
            )
        }
    }
}
