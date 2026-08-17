package com.clawmate.app.home.recommendation

import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.home.domain.HomeRecommendationState
import com.clawmate.app.log.domain.SensitiveLogRedactor

/**
 * Typed, config-driven contract for generating **home todo recommendations from
 * real Android Gallery data** (Phase 28).
 *
 * This closes the Phase-16 remainder of migration row **A1**: on Harmony, the
 * Home recommendation list is fed by the data-manager pipeline's indexed
 * todo-events (`HomeRecommendationService.ets` +
 * `DataPipelineModelClient.planHomeRecommendations` /
 * `normalizeHomeRecommendations`). On Android there is no indexed-todo source,
 * so Phase 28 derives the recommendation input only from **real, already-stored
 * [com.clawmate.app.gallery.model.GalleryPhotoItem]s** — their text metadata
 * (title / category / summary / details / tags / keywords / date / id) is
 * converted into bounded planner events; raw file URIs, unseen image bytes,
 * tokens and un-analysed/fabricated content are never transmitted.
 *
 * Design properties (matching the Harmony reference and the Phase-28 safety
 * requirements, all enforced and unit-tested):
 *
 *  - **Explicit user action only.** Recommendations are generated only from the
 *    explicit Home "生成推荐" button — never at launch, on recomposition, from a
 *    background job or automatically. The generated list is merged into the
 *    current Home state and only the per-recommendation `completed` flags are
 *    persisted through the existing [com.clawmate.app.home.data.HomeRecRepository].
 *  - **No-call when not configured or no data.** [HomeRecommendationConfig
 *    .fromEndpointConfig] returns null unless the OpenAI-compatible Planner
 *    backend (URL + API key + model) is configured; the pipeline short-circuits
 *    with a typed state and does zero network I/O when there is no gallery data,
 *    all planner inputs are empty, or the feature is unconfigured.
 *  - **Bounded, real input.** Each planner event is capped by
 *    [HomeRecommendationBounds] and built from real [GalleryPhotoItem] text only.
 *  - **Strict output validation.** [HomeRecommendationNormalizer] requires every
 *    returned item to carry a non-blank title **and** source event ids that are
 *    present in the input; ids are deduplicated so each input event appears in at
 *    most one recommendation; counts / field lengths / domain / tag / desc are
 *    bounded; invalid items are dropped (never stuffed with defaults).
 *  - **Redacted errors.** Error paths route through [SensitiveLogRedactor], so a
 *    scrubbed provider message can never leak a key or raw content.
 */

/** Allowed recommendation domains (mirrors Harmony `normalizeDomains` set). */
val HOME_RECOMMENDATION_DOMAINS: Set<String> =
    setOf("eat", "wear", "live", "travel", "work", "other")

/** Bounding constants for untrusted gallery-derived input and model output. */
object HomeRecommendationBounds {
    /**
     * Cap on the total number of planner events sent to the model in one call
     * (each gallery item becomes one bounded event). Keeps the request bounded.
     */
    const val MAX_EVENTS_PER_REQUEST: Int = 60

    /** Cap on a single planner event's `todo_text` field (real text only). */
    const val MAX_EVENT_TODO_TEXT: Int = 120

    /** Cap on a single planner event's `summary` field. */
    const val MAX_EVENT_SUMMARY: Int = 100

    /** Cap on a single planner event's `normalized_fact` field. */
    const val MAX_EVENT_NORMALIZED_FACT: Int = 260

    /** Max number of real keywords/tags copied into a single event. */
    const val MAX_EVENT_KEYWORDS: Int = 8

    /**
     * Cap on the number of recommendations admitted from one model response.
     * Harmony lets the model decide, but on Android we bound the count so a
     * runaway reply can never flood the home list.
     */
    const val MAX_RECOMMENDATIONS: Int = 24

    /** Cap on a recommendation `title` (must be present and non-blank). */
    const val MAX_TITLE_CHARACTERS: Int = 40

    /** Cap on a recommendation `desc`. Empty is allowed (honest). */
    const val MAX_DESC_CHARACTERS: Int = 120

    /** Cap on a recommendation `tag` (length is a validity test as in Harmony). */
    const val MAX_TAG_CHARACTERS: Int = 6

    /** Max source event ids referenced by one recommendation. */
    const val MAX_SOURCE_EVENT_IDS_PER_RECOMMENDATION: Int = 5
}

/**
 * A validated, ready-to-send recommendation configuration. Reuses the existing
 * OpenAI-compatible Planner chat backend (the same host / key / model the chat
 * screen and other cloud features already use, read from
 * [com.clawmate.app.config.EndpointConfig]). Constructed only via
 * [fromEndpointConfig], which returns null whenever any required field is
 * missing so no call is ever attempted for an unconfigured feature.
 */
data class HomeRecommendationConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    companion object {
        /**
         * Returns a config only when the Planner backend is fully configured
         * (URL + API key + model present and the URL normalizes to a usable
         * endpoint). Mirrors the `isCloudPlannerConfigReady` gate the Harmony
         * source enforces. Otherwise the feature is NOT_CONFIGURED and no call is
         * attempted.
         */
        fun fromEndpointConfig(): HomeRecommendationConfig? {
            val url = com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl.trim()
            val key = com.clawmate.app.config.EndpointConfig.cloudPlannerApiKey.trim()
            val model = com.clawmate.app.config.EndpointConfig.cloudPlannerModel.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            if (!ChatServerConfig.isConfigured(url)) return null
            return HomeRecommendationConfig(baseUrl = url, apiKey = key, modelName = model)
        }
    }
}

/**
 * A bounded planner-event representation of one real gallery item, mirroring
 * the Harmony `HomeRecommendationPlannerEvent` shape. Only real text metadata
 * that actually exists on the stored [GalleryPhotoItem] is ever populated; no
 * field is fabricated.
 */
data class HomeRecommendationPlannerEvent(
    val eventId: String,
    val date: String,
    val todoType: String,
    val todoDeadline: String,
    val todoText: String,
    val summary: String,
    val normalizedFact: String,
    val sources: List<String>,
    val domains: List<String>,
    val keywords: List<String>,
    /** Always empty for Android gallery input — raw image URIs/bytes never leave. */
    val imagePaths: List<String>,
)

/**
 * Hand-built result of one explicit recommendation generation attempt. The typed
 * state drives the honest button UI (未配置 / 无数据 / 运行中 / 成功 / 失败).
 */
sealed interface HomeRecommendationGenerationState {
    /** No explicit generate action has completed yet. */
    data object Idle : HomeRecommendationGenerationState

    /** Planner backend is not configured — zero network calls attempted. */
    data object NotConfigured : HomeRecommendationGenerationState

    /** Available to generate, but no real gallery-derived input exists. */
    data object NoData : HomeRecommendationGenerationState

    /** An explicit generation is in progress. */
    data object Running : HomeRecommendationGenerationState

    /** A generation completed with [recommendations] admitted (possibly zero). */
    data class Success(val recommendations: List<HomeRecommendationState>) :
        HomeRecommendationGenerationState

    /** A generation failed; [message] is redacted and bounded. */
    data class Error(val message: String, val userMessage: String) :
        HomeRecommendationGenerationState
}

/** Machine-readable reason for a failed / unusable generation attempt. */
enum class HomeRecommendationErrorReason {
    /** Input had no usable gallery events (no data). */
    NO_DATA,
    /** Planner backend not configured (never a call). */
    CONFIG,
    /** HTTP non-2xx response from the provider. */
    HTTP,
    /** Connectivity / transport / IO failure. */
    IO,
    /** Request timed out. */
    TIMEOUT,
    /** Coroutine cancelled by the user / lifecycle. */
    CANCELLED,
    /** Response could not be parsed / was malformed. */
    MALFORMED,
    /** The provider returned no usable recommendations. */
    EMPTY,
}

/** Internal `ChatException` kind -> our typed reason. */
private fun mapInternetReason(
    kind: com.clawmate.app.chat.cloud.ChatErrorKind,
): HomeRecommendationErrorReason =
    when (kind) {
        com.clawmate.app.chat.cloud.ChatErrorKind.HTTP -> HomeRecommendationErrorReason.HTTP
        com.clawmate.app.chat.cloud.ChatErrorKind.TIMEOUT -> HomeRecommendationErrorReason.TIMEOUT
        com.clawmate.app.chat.cloud.ChatErrorKind.CANCELLED ->
            HomeRecommendationErrorReason.CANCELLED
        com.clawmate.app.chat.cloud.ChatErrorKind.CONFIG -> HomeRecommendationErrorReason.CONFIG
        com.clawmate.app.chat.cloud.ChatErrorKind.MALFORMED_SSE ->
            HomeRecommendationErrorReason.MALFORMED
        else -> HomeRecommendationErrorReason.IO
    }

/**
 * Typed error surfaced by the recommendation pipeline. Secrets are redacted
 * before they reach [message] via [SensitiveLogRedactor]; [userMessage] is the
 * honest, friendly string shown in the UI. This is deliberately a *silent*
 * failure path (the Home list is never fabricated): a model failure yields a
 * redacted error state, never invented recommendations.
 */
class HomeRecommendationException(
    val reason: HomeRecommendationErrorReason,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Redacted copy (safe to surface in logs / safeMessage callers). */
    val safeMessage: String get() = message

    /** Honest user-facing copy for the common failure classes. */
    val userMessage: String
        get() = when (reason) {
            HomeRecommendationErrorReason.NO_DATA ->
                "图库中还没有可生成推荐的真实内容。"
            HomeRecommendationErrorReason.CONFIG ->
                "未配置云端模型，请先在设置中填写 Planner 地址、API Key 和模型后再生成推荐。"
            HomeRecommendationErrorReason.TIMEOUT -> "智能推荐生成超时，请稍后重试。"
            HomeRecommendationErrorReason.CANCELLED -> "智能推荐生成已取消。"
            HomeRecommendationErrorReason.MALFORMED -> "模型返回内容无法解析，推荐未生成。"
            HomeRecommendationErrorReason.EMPTY -> "模型未返回可用的推荐内容。"
            HomeRecommendationErrorReason.HTTP ->
                "推荐服务返回错误${httpCode?.let { "（HTTP $it）" } ?: ""}，请稍后重试。"
            HomeRecommendationErrorReason.IO -> "无法连接推荐服务，请检查网络或配置。"
        }

    companion object {
        fun fromChat(e: com.clawmate.app.chat.cloud.ChatException): HomeRecommendationException =
            HomeRecommendationException(
                reason = mapInternetReason(e.kind),
                httpCode = e.httpCode,
                message = SensitiveLogRedactor.redact(e.message.orEmpty()),
                cause = e,
            )

        fun of(reason: HomeRecommendationErrorReason, message: String): HomeRecommendationException =
            HomeRecommendationException(reason, SensitiveLogRedactor.redact(message))
    }
}

/** A successfully produced recommendation request result. */
internal data class HomeRecommendationPlanningResult(
    /** The bounded planner events that were actually sent. */
    val events: List<HomeRecommendationPlannerEvent>,
    /** The strictly-normalized, admitted recommendations. */
    val recommendations: List<com.clawmate.app.home.domain.HomeRecommendationState>,
)
