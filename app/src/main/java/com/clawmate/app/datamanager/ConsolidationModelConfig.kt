package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.chat.cloud.normalizeChatUrl
import com.clawmate.app.config.EndpointConfig

/**
 * Typed, config-driven model configuration for the Phase-34 **model-backed**
 * consolidation/dedup path. Reuses the same shared OpenAI-compatible Planner
 * chat backend (host/key/model) that the chat screen, Phase-28 Home planner and
 * Phase-32 profile claims read from [EndpointConfig].
 *
 * Constructed only via [fromEndpointConfig], which returns null whenever any of
 * base URL / API key / model is missing or the URL is not usable, so **no model
 * call is ever attempted for an unconfigured feature** (mirrors
 * [ProfileModelConfig] / `HomeRecommendationConfig`). A model call is only
 * invited by an explicit, fully-configured, user-triggered consolidation action
 * — never on launch, Home load, recomposition, or a background/WorkManager job.
 */
data class ConsolidationModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {

    /** The fully-normalised `/v1/chat/completions` endpoint used by the client. */
    val completionsUrl: String get() = normalizeChatUrl(baseUrl)
    companion object {
        /** Returns a config only when the shared Planner backend is fully configured. */
        fun fromEndpointConfig(): ConsolidationModelConfig? {
            val url = EndpointConfig.cloudPlannerBaseUrl.trim()
            val key = EndpointConfig.cloudPlannerApiKey.trim()
            val model = EndpointConfig.cloudPlannerModel.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            if (!ChatServerConfig.isConfigured(url)) return null
            return ConsolidationModelConfig(baseUrl = url, apiKey = key, modelName = model)
        }

        fun fromValues(baseUrl: String, apiKey: String, modelName: String): ConsolidationModelConfig? {
            if (baseUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) return null
            if (!ChatServerConfig.isConfigured(baseUrl)) return null
            return ConsolidationModelConfig(baseUrl = baseUrl, apiKey = apiKey, modelName = modelName)
        }
    }
}

/**
 * Typed model gate for the consolidation action. It classifies whether the model
 * path may run so callers can short-circuit to a typed local result before any
 * network/token cost, and so the docs can honestly report the disposition.
 */
sealed interface ConsolidationModelGate {
    /** Fully configured and invited by an explicit action — model path may run. */
    data class Allow(val config: ConsolidationModelConfig) : ConsolidationModelGate

    /** No entries / not an explicit action → local-only (zero network). */
    object LocalOnly : ConsolidationModelGate

    /** Missing full config → the model cannot run; local path only. */
    object NotConfigured : ConsolidationModelGate
}
