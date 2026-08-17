package com.clawmate.app.profile.affinity

import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.log.domain.SensitiveLogRedactor
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile

/**
 * Typed, config-driven contract for the profile-affinity "同频报告" cloud
 * feature (ported from the HarmonyOS flow in `pages/Index.ets`:
 * `handleProfileShareReceived` / `runProfileShareAffinityAnalysis` /
 * `buildProfileAffinityMessages`).
 *
 * On Harmony, immediately after a profile share is imported the app analyses the
 * two profile summaries with the Planner cloud backend and produces a Markdown
 * "同频报告". On Android, the report is **never generated automatically**:
 * it is only produced after an explicit user action (button) post-import, and
 * only when the OpenAI-compatible Planner backend is configured. Nothing here
 * performs a network call; the clients are gated behind [ProfileAffinityConfig]
 * and never fire when not configured. No NFC, no fabricated capability.
 *
 * Credential policy (no new credentials): the report reuses the existing
 * OpenAI-compatible Planner chat backend — the same endpoint / model / key the
 * chat screen already uses via
 * [com.clawmate.app.chat.cloud.ChatServerConfig]. No token, URL or model is
 * ever hard-coded. Only bounded profile *text* is sent to the model; raw file
 * URIs, tokens and unrelated local data are never transmitted.
 *
 * Safety properties (enforced and unit-tested):
 *  - [ProfileAffinityConfig.fromEndpointConfig] returns null when the Planner
 *    backend is not configured, so no call can ever fire without credentials.
 *  - The prompt only carries the two bounded profile texts (each capped by
 *    [ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS]); no secrets or URIs.
 *  - The produced report is bounded by [ProfileAffinityBounds.MAX_REPORT_CHARACTERS].
 *  - Error messages are redacted via [SensitiveLogRedactor] so an API key or
 *    complete sensitive content can never leak.
 */

/** Bounding constants for untrusted profile input and model output. */
object ProfileAffinityBounds {
    /**
     * Cap on a single profile text sent to the model (port of the Harmony
     * `limitProfileShareText(localProfile, 12000)` / `(remoteProfile, 12000)`
     * clamp in `buildProfileAffinityMessages`).
     */
    const val MAX_PROFILE_INPUT_CHARACTERS: Int = 12000

    /**
     * Cap on the produced report characters admitted from the model. Mirrors the
     * Harmony `maxTokens: 2048` (a 2048-token reply is far below this bound).
     *
     * This is the effective output safety boundary: the affinity analyzer only
     * ever receives the parsed `ChatCompletionResult.content`, never the raw HTTP
     * body stream (the shared `ChatCompletionsClient` buffers the body internally
     * and its behavior is intentionally left unchanged). So a raw HTTP body byte
     * cap cannot be enforced from this layer and is deliberately not declared
     * here — the content-character bound is what really bounds the report.
     */
    const val MAX_REPORT_CHARACTERS: Int = 16 * 1024
}

/**
 * A validated, ready-to-send report configuration.
 *
 * Reuses the existing OpenAI-compatible Planner chat backend: [baseUrl],
 * [apiKey] and [modelName] are the same host / key / model the chat screen
 * already uses (from `EndpointConfig`). Constructed only via
 * [fromEndpointConfig], which returns null whenever any required field is
 * missing so no call is ever attempted for an unconfigured feature.
 */
data class ProfileAffinityConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    companion object {
        /**
         * True only when the Planner chat backend is fully configured (URL,
         * model and key all present and a normalized URL exists), matching the
         * gate the Harmony source enforces in `isCloudPlannerConfigReady`
         * (planner base URL + API key) plus the model the Android request needs.
         * Otherwise the feature is NOT_CONFIGURED and no call is attempted.
         */
        fun fromEndpointConfig(): ProfileAffinityConfig? {
            val url = com.clawmate.app.config.EndpointConfig.cloudPlannerBaseUrl.trim()
            val key = com.clawmate.app.config.EndpointConfig.cloudPlannerApiKey.trim()
            val model = com.clawmate.app.config.EndpointConfig.cloudPlannerModel.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            if (!ChatServerConfig.isConfigured(url)) return null
            return ProfileAffinityConfig(baseUrl = url, apiKey = key, modelName = model)
        }
    }
}

/** Machine-readable error reason for the honest report state. */
enum class ProfileAffinityErrorReason {
    /** Planner backend not configured (no credentials) — never a call. */
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
    /** Response exceeded [ProfileAffinityBounds.MAX_REPORT_CHARACTERS]. */
    OVER_BOUNDS,
    /** The provider returned no usable report content. */
    EMPTY,
    /** Request input was empty / blank. */
    EMPTY_INPUT,
}

/** Internal `ChatException` kind -> our typed reason. */
private fun mapThreadReason(kind: com.clawmate.app.chat.cloud.ChatErrorKind): ProfileAffinityErrorReason =
    when (kind) {
        com.clawmate.app.chat.cloud.ChatErrorKind.HTTP -> ProfileAffinityErrorReason.HTTP
        com.clawmate.app.chat.cloud.ChatErrorKind.TIMEOUT -> ProfileAffinityErrorReason.TIMEOUT
        com.clawmate.app.chat.cloud.ChatErrorKind.CANCELLED -> ProfileAffinityErrorReason.CANCELLED
        com.clawmate.app.chat.cloud.ChatErrorKind.CONFIG -> ProfileAffinityErrorReason.CONFIG
        com.clawmate.app.chat.cloud.ChatErrorKind.MALFORMED_SSE -> ProfileAffinityErrorReason.MALFORMED
        else -> ProfileAffinityErrorReason.IO
    }

/**
 * Typed error surfaced by [ProfileAffinityAnalyzer]. Secrets are redacted
 * before they can reach [message] via [SensitiveLogRedactor]. [userMessage] is
 * the honest, friendly string shown in the UI.
 */
class ProfileAffinityException(
    val reason: ProfileAffinityErrorReason,
    override val message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val safeMessage: String get() = message

    /**
     * Honest user-facing copy. [message] is redacted but may still contain a
     * trace of provider-provided text, so the UI falls back to this stable copy
     * for the common failure classes.
     */
    val userMessage: String
        get() = when (reason) {
            ProfileAffinityErrorReason.CONFIG ->
                "未配置云端服务，请先在设置中填写 Planner 地址、API Key 和模型后再生成同频报告。"
            ProfileAffinityErrorReason.TIMEOUT -> "同频报告生成超时，请稍后重试。"
            ProfileAffinityErrorReason.CANCELLED -> "同频报告生成已取消。"
            ProfileAffinityErrorReason.OVER_BOUNDS ->
                "模型返回内容超出允许长度，已停止并保持安全边界。"
            ProfileAffinityErrorReason.EMPTY -> "模型未返回可用的同频报告内容。"
            ProfileAffinityErrorReason.EMPTY_INPUT -> "双方画像内容不足，无法生成同频报告。"
            ProfileAffinityErrorReason.HTTP ->
                "同频报告服务返回错误${httpCode?.let { "（HTTP $it）" } ?: ""}，请稍后重试。"
            ProfileAffinityErrorReason.IO -> "无法连接同频报告服务，请检查网络或配置。"
            else -> "同频报告生成失败，请稍后重试。"
        }

    companion object {
        fun fromChat(e: com.clawmate.app.chat.cloud.ChatException): ProfileAffinityException =
            ProfileAffinityException(
                reason = mapThreadReason(e.kind),
                httpCode = e.httpCode,
                message = SensitiveLogRedactor.redact(e.message.orEmpty()),
                cause = e,
            )

        fun of(reason: ProfileAffinityErrorReason, message: String): ProfileAffinityException =
            ProfileAffinityException(reason, SensitiveLogRedactor.redact(message))
    }
}

/** A successfully produced "同频报告" Markdown. [report] is the display text. */
data class ProfileAffinityResult(
    val report: String,
    val rawText: String,
)

/**
 * Build a bounded local profile text representing the device-persisted profile
 * for the report. Mirrors the shape of the Harmony `buildProfileShareText`
 * (domain titles + summaries + evidence claims) but only ever reads the local
 * [LocalAvatarProfile]. The output is truncated to
 * [ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS] so the model never
 * receives an unbounded/raw profile. Deterministic and device-free.
 */
fun buildLocalAffinityProfileText(profile: LocalAvatarProfile): String {
    val lines = mutableListOf<String>()
    lines += "应用：ClawMate"
    if (profile.displayName.isNotBlank()) lines += "昵称：${profile.displayName.trim().take(120)}"
    if (profile.age.isNotBlank()) lines += "年龄：${profile.age.trim().take(40)}"
    if (profile.gender.isNotBlank()) lines += "性别：${profile.gender.trim().take(20)}"
    if (profile.shortSummary.isNotBlank()) lines += "简介：${profile.shortSummary.trim()}"
    lines += "画像领域："
    val domains = profile.domains.take(12)
    if (domains.isEmpty()) {
        lines += "- 暂无领域摘要"
    } else {
        domains.forEach { domain ->
            val body = (domain.summary.ifBlank { domain.shortSummary }).trim()
            val summary = body.ifBlank { "暂无数据" }.take(360)
            val claims = domain.claims.take(6)
                .filter { it.isNotBlank() }
                .map { it.trim().take(90) }
            val claimText = if (claims.isNotEmpty()) "；证据要点：${claims.joinToString("；")}" else ""
            lines += "- ${domain.displayName}：$summary$claimText"
        }
    }
    val joined = lines.joinToString("\n")
    return if (joined.length <= ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS) {
        joined
    } else {
        joined.substring(0, ProfileAffinityBounds.MAX_PROFILE_INPUT_CHARACTERS - 3) + "..."
    }
}
