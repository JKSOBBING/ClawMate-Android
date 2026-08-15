package com.clawmate.app.chat.cloud

import com.clawmate.app.config.EndpointConfig

/**
 * Server configuration for one OpenAI-compatible chat backend.
 *
 * Endpoints and keys come from the secrets-free [EndpointConfig] (loaded from a
 * git-ignored `raw/local_config.properties`); nothing is hard-coded here. See
 * `MIGRATION_STATUS.md -> Networking & Security`.
 *
 * Planners and deciders may point at different base URLs in the source; the
 * chat screen uses the planner base URL. Both normalize identically.
 */
data class ChatServerConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    /**
     * The fully-normalized `/v1/chat/completions` URL. Accepts a bare host, a
     * `.../v1`, or an already-full `.../chat/completions` — ported from
     * `CloudModelClient.normalizeChatUrl`.
     */
    val completionsUrl: String get() = normalizeChatUrl(baseUrl)

    companion object {
        fun fromEndpointConfig(): ChatServerConfig =
            ChatServerConfig(
                baseUrl = EndpointConfig.cloudPlannerBaseUrl,
                apiKey = EndpointConfig.cloudPlannerApiKey,
                modelName = EndpointConfig.cloudPlannerModel,
            )

        /** Whether a usable URL (and, if keyed, a place to send) is configured. */
        fun isConfigured(baseUrl: String): Boolean = normalizeChatUrl(baseUrl).isNotBlank()
    }
}

/** Port of `CloudModelClient.normalizeChatUrl`. A blank base URL stays blank. */
fun normalizeChatUrl(baseUrl: String): String {
    var url = baseUrl.trim()
    while (url.endsWith("/")) url = url.dropLast(1)
    if (url.isEmpty()) return ""
    if (url.endsWith("/chat/completions")) return url
    if (url.endsWith("/v1")) return url + "/chat/completions"
    return url + "/v1/chat/completions"
}
