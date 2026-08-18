package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatClient
import com.clawmate.app.chat.cloud.ChatCompletionsClient
import com.clawmate.app.chat.cloud.ChatServerConfig
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Minimal, testable manual factory for the real [HomeRecommendationRepository].
 *
 * There is no DI framework in this skeleton. This object lazily builds the same
 * OkHttp-backed [ChatCompletionsClient] the other cloud features use (same
 * OpenAI-compatible Planner backend derived from [HomeRecommendationConfig]) and
 * wires it into a [GalleryBackedHomeRecommendationRepository]. It never
 * hard-codes an endpoint, token or model.
 *
 * Tests inject a fake [ChatClient] into [HomeRecommendationPlanner] / a fake
 * [HomeRecommendationRepository] directly, so this factory only needs to compile;
 * its real network path is exercised by the shared chat client and integration
 * via a configured EndpointConfig in the app root.
 */
object HomeRecommendationFactory {

    /**
     * Build a real repository. The planner backend is resolved lazily from
     * [EndpointConfig] on each [HomeRecommendationRepository.generate] call, so a
     * freshly-configured backend is honoured without restart; a
     * not-yet-configured backend yields `NotConfigured` with zero network I/O.
     *
     * With a [baseDir] (the app-private `filesDir`), the daily-log planner input
     * also folds in the Phase-38 persisted batch event-enrichment fields through
     * the existing index read path ([DailyLogEnrichmentResolver]); with no
     * `baseDir`, `realRepository()` behaves exactly as before (no enrichment
     * overlay) for callers/tests that do not pass one.
     */
    fun realRepository(baseDir: java.io.File? = null): HomeRecommendationRepository {
        val currentDateProvider = { java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now()) }
        val dailyLogEnrichment: (List<com.clawmate.app.datamanager.DailyLogEntry>) -> Map<String, com.clawmate.app.datamanager.DailyLogPlannerEnrichment> =
            if (baseDir == null) {
                { emptyMap() }
            } else {
                val resolver = com.clawmate.app.datamanager.DailyLogEnrichmentResolver(baseDir)
                ({ entries -> resolver.resolveBySourceRef(entries) })
            }
        return buildGalleryBackedRepository(
            configLoad = { HomeRecommendationConfig.fromEndpointConfig() },
            plannerFactory = { config -> HomeRecommendationPlanner(buildChatClient(config), config) },
            currentDateProvider = currentDateProvider,
            dailyLogEnrichment = dailyLogEnrichment,
        )
    }

    /**
     * Build a repository bound to a pre-resolved [HomeRecommendationConfig] and a
     * supplied [ChatClient] (tests inject a fake; production uses the real one).
     */
    fun repository(
        config: HomeRecommendationConfig,
        chat: ChatClient? = null,
        currentDateProvider: () -> String = { java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now()) },
    ): HomeRecommendationRepository {
        return buildGalleryBackedRepository(
            configLoad = { config },
            plannerFactory = { HomeRecommendationPlanner(chat ?: buildChatClient(config), config) },
            currentDateProvider = currentDateProvider,
        )
    }

    /** Build a planner bound to a config + chat (test seam). */
    fun planner(config: HomeRecommendationConfig, chat: ChatClient): HomeRecommendationPlanner =
        HomeRecommendationPlanner(chat, config)

    private fun buildChatClient(config: HomeRecommendationConfig): ChatCompletionsClient {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        return ChatCompletionsClient(
            httpClient,
            ChatServerConfig(
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                modelName = config.modelName,
            ),
        )
    }
}
