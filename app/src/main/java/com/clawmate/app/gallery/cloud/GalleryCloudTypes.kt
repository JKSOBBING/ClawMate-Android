package com.clawmate.app.gallery.cloud

import com.clawmate.app.settings.domain.EmbeddingMode

/**
 * Typed, config-driven contract for the optional cloud portions of Gallery
 * monthly insights (migration rows D1 remote embedding + D2 AI month summary).
 *
 * Ported from the HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/utils/gallery/GalleryEmbeddingService.ets
 *   entry/src/main/ets/utils/CloudModelClient.ets (createEmbedding,
 *     summarizeGalleryMonth, normalizeEmbeddingUrl)
 *
 * Nothing here performs a network call. It only *describes* a cloud
 * configuration and whether one is actually present. The callers
 * ([GalleryEmbeddingClient], [GalleryMonthlySummaryClient]) are gated behind
 * these config objects and never fire when a feature is not explicitly enabled
 * and fully configured.
 *
 * Credential policy (no new credentials):
 *  - Remote embedding reuses the existing gallery embedding settings
 *    ([EmbeddingMode.Remote] base URL / API key / model that the Settings
 *    center already persists).
 *  - The AI month summary reuses the existing OpenAI-compatible Planner chat
 *    backend ([com.clawmate.app.chat.cloud.ChatServerConfig]) — the same URL /
 *    key / model the chat screen already uses. No plan-wide key, no
 *    subscription-gathered credential is ever inferred.
 */

/** Bounding constants for untrusted model input/output. */
object GalleryCloudBounds {
    /** Cap on characters of text sent to a remote embedding (port of the
     * Harmony `embedTextOnDevice` clamp of 512 and common embedding limits). */
    const val MAX_EMBEDDING_TEXT_LENGTH = 512

    /** Cap on an embedding vector's dimension count admitted from a provider. */
    const val MAX_EMBEDDING_DIMENSIONS = 4096

    /** Cap on an embedding response body bytes before parsing is rejected. */
    const val MAX_EMBEDDING_RESPONSE_BYTES = 512 * 1024

    /** Cap on an AI month-summary response body bytes. */
    const val MAX_SUMMARY_RESPONSE_BYTES = 256 * 1024

    /** Cap on a single chat-completion content string admitted for a summary. */
    const val MAX_SUMMARY_CHARACTERS = 16 * 1024
}

/**
 * A validated, ready-to-send remote embedding configuration.
 *
 * [baseUrl] is the normalized base URL; [completionsUrl] is the resolved
 * `/v1/embeddings` endpoint. Endpoint / model / key presence is validated up
 * front in [validate], so a client is never constructed for a feature that is
 * not configured.
 */
data class GalleryEmbeddingConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    val embeddingsUrl: String get() = normalizeEmbeddingUrl(baseUrl)

    companion object {
        /**
         * True only when a remote embedding is both *explicitly selected*
         * ([mode] == [EmbeddingMode.Remote]) and fully configured (URL, model,
         * key all present). Otherwise the feature is NOT_CONFIGURED and no call
         * is ever attempted.
         */
        fun fromSettings(
            mode: EmbeddingMode,
            baseUrl: String,
            apiKey: String,
            modelName: String,
        ): GalleryEmbeddingConfig? {
            if (mode != EmbeddingMode.Remote) return null
            val url = normalizeEmbeddingUrl(baseUrl)
            val key = apiKey.trim()
            val model = modelName.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            return GalleryEmbeddingConfig(baseUrl = url, apiKey = key, modelName = model)
        }
    }
}

/**
 * A validated, ready-to-send AI month-summary configuration.
 A validated, ready-to-send AI month-summary configuration.
 *
 * Intentionally reuses the existing OpenAI-compatible Planner chat backend —
 * the endpoint/model/key are the same [baseUrl]/[modelName]/[apiKey] the chat
 * screen already uses. [enabled] is the explicit user opt-in so no summary
 * call ever fires unless the user turned this feature on.
 */
data class GalleryMonthlySummaryConfig(
    val enabled: Boolean,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    val chatReady: Boolean
        get() = enabled &&
            baseUrl.isNotBlank() &&
            apiKey.isNotBlank() &&
            modelName.isNotBlank()

    companion object {
        fun fromSettings(
            enabled: Boolean,
            baseUrl: String,
            apiKey: String,
            modelName: String,
        ): GalleryMonthlySummaryConfig? {
            if (!enabled) return null
            val url = baseUrl.trim()
            val key = apiKey.trim()
            val model = modelName.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            return GalleryMonthlySummaryConfig(
                enabled = true,
                baseUrl = url,
                apiKey = key,
                modelName = model,
            )
        }
    }
}

/**
 * Typed error surfaced by the gallery cloud clients. Secrets are redacted
 * before they can reach [message] via [GalleryCloudErrorRedactor].
 */
class GalleryCloudException(
    val reason: GalleryCloudErrorReason,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val safeMessage: String get() = message
}

/** Machine-readable error reason for the honest ERROR cloud state. */
enum class GalleryCloudErrorReason {
    /** HTTP non-2xx response from the provider. */
    HTTP,
    /** Transport / IO failure (timeout, socket, DNS). */
    IO,
    /** Response could not be parsed / was malformed. */
    MALFORMED,
    /** Response exceeded size / dimension / character bounds. */
    OVER_BOUNDS,
    /** Provider reply had no usable payload (e.g. empty vector list, empty summary). */
    EMPTY,
    /** Request input was empty / blank. */
    EMPTY_INPUT,
}

/** Pure, deterministic redaction of secrets from error text and -path text. */
internal object GalleryCloudErrorRedactor {
    /**
     * Scrub bearer keys, `sk-…` OpenAI keys and generic `key=…`/`key: …`
     * secrets from arbitrary provider text (error bodies / raw replies). Reuses
     * the app-wide [com.clawmate.app.log.domain.SensitiveLogRedactor] so the
     * redaction rules stay single-sourced. Blank input stays blank.
     */
    fun redact(text: String): String =
        com.clawmate.app.log.domain.SensitiveLogRedactor.redact(text)
}

/**
 * Port of `CloudModelClient.normalizeEmbeddingUrl`: resolves a bare host /
 * `/v1` / already-full `/embeddings` base into a `/v1/embeddings` URL.
 * A blank base stays blank.
 */
fun normalizeEmbeddingUrl(baseUrl: String): String {
    var url = baseUrl.trim()
    while (url.endsWith("/")) url = url.dropLast(1)
    if (url.isEmpty()) return ""
    if (url.endsWith("/embeddings")) return url
    if (url.endsWith("/v1")) return url + "/embeddings"
    return url + "/v1/embeddings"
}

/** A successfully produced remote embedding (vector + provenance). */
data class GalleryEmbeddingResult(
    val vector: List<Double>,
    val mode: String,
    val model: String,
) {
    companion object {
        fun remote(vector: List<Double>, model: String): GalleryEmbeddingResult =
            GalleryEmbeddingResult(vector = vector, mode = "remote", model = model)
    }
}

/** One remote AI month summary, ported from Harmony `GalleryMonthSummaryResult`. */
data class GalleryMonthSummaryResult(
    val title: String,
    val summary: String,
    val suggestion: String,
    val rawText: String,
)
