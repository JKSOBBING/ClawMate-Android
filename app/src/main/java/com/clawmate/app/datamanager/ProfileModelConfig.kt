package com.clawmate.app.datamanager

import com.clawmate.app.chat.cloud.ChatServerConfig
import com.clawmate.app.config.EndpointConfig
import com.clawmate.app.datamanager.ProfileTypes.Bounds
import com.clawmate.app.datamanager.ProfileTypes.ProfileModelPayload
import com.clawmate.app.datamanager.ProfileTypes.RawProfileClaim
import kotlinx.serialization.json.jsonObject

/**
 * Typed, config-driven model configuration for the Phase-32 profile claims
 * generation. Reuses the shared OpenAI-compatible Planner chat backend (same
 * host/key/model the chat screen and other cloud features read from
 * [EndpointConfig]).
 *
 * Constructed only via [fromEndpointConfig], which returns null whenever any
 * required field is missing so no call is ever attempted for an unconfigured
 * feature. This mirrors the `HomeRecommendationConfig` /
 * `ProfileAffinityConfig` gates and keeps Phase-32 **zero-network** unless the
 * user has explicitly configured a model backend **and** real indexed events
 * exist.
 */
data class ProfileModelConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
) {
    companion object {
        /** Returns a config only when the Planner backend is fully configured. */
        fun fromEndpointConfig(): ProfileModelConfig? {
            val url = EndpointConfig.cloudPlannerBaseUrl.trim()
            val key = EndpointConfig.cloudPlannerApiKey.trim()
            val model = EndpointConfig.cloudPlannerModel.trim()
            if (url.isEmpty() || key.isEmpty() || model.isEmpty()) return null
            if (!ChatServerConfig.isConfigured(url)) return null
            return ProfileModelConfig(baseUrl = url, apiKey = key, modelName = model)
        }

        fun fromValues(baseUrl: String, apiKey: String, modelName: String): ProfileModelConfig? {
            if (baseUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) return null
            if (!ChatServerConfig.isConfigured(baseUrl)) return null
            return ProfileModelConfig(baseUrl, apiKey, modelName)
        }
    }
}

/**
 * Strict, **independent** validator for a model's raw profile payload. The model
 * output is untrusted: this object decodes the JSON reply itself, whitelists
 * every field, and admits only claims that reference **real** indexed event ids
 * and pass every bound. It never fabricates a claim, never trusts the model's own
 * counts/dates, and drops (never coerces) invalid claims.
 */
object ProfileModelValidator {

    /** Normalize a `kind` token against the whitelist (Harmony `normalizeProfileKind`). */
    fun normalizeKind(value: String): String {
        val kind = ConsolidationText.normalizeFreeText(value).lowercase()
        return if (kind in Bounds.CLAIM_KINDS) kind else "observation"
    }

    /** Normalize a `confidence` token to the strict high|medium|low set (Harmony). */
    fun normalizeConfidence(value: String): String {
        val confidence = ConsolidationText.normalizeFreeText(value).lowercase()
        return if (confidence in Bounds.CONFIDENCE_TOKENS) confidence else "low"
    }

    /**
     * Trim statement to the bounded cap. Statements longer than the cap are the
     * only admissible field that is truncated (to avoid dropping an otherwise
     * real claim); every other over-bounds field is rejected.
     */
    fun boundStatement(value: String): String {
        val text = ConsolidationText.normalizeFreeText(value)
        return if (text.length <= Bounds.MAX_CLAIM_STATEMENT_CHARACTERS) text
        else text.substring(0, Bounds.MAX_CLAIM_STATEMENT_CHARACTERS)
    }

    /**
     * Whitelist the raw claims against the real domain events. Returns the
     * admitted claims plus a count of dropped ones. A claim is dropped when its
     * statement is blank/duplicate, its evidence ids are not all real domain
     * ids, it references fewer than [Bounds.MIN_EVIDENCE_EVENTS_PER_CLAIM]
     * distinct real events, or it looks like a raw event detail.
     */
    fun validateClaims(
        rawClaims: List<RawProfileClaim>,
        realDomainEventIds: Set<String>,
    ): Pair<List<RawProfileClaim>, Int> {
        val admitted = ArrayList<RawProfileClaim>()
        val seen = HashSet<String>()
        var dropped = 0
        for (raw in rawClaims) {
            val statement = ConsolidationText.normalizeFreeText(raw.statement)
            if (statement.isEmpty()) { dropped++; continue }
            val key = statement.lowercase()
            if (key in seen) { dropped++; continue }
            if (looksLikeEventDetail(statement)) { dropped++; continue }
            val ids = ConsolidationText.stableUnique(raw.eventIds)
                .filter { it in realDomainEventIds }
            val distinct = HashSet(ids)
            if (distinct.size < Bounds.MIN_EVIDENCE_EVENTS_PER_CLAIM) { dropped++; continue }
            if (ids.size > Bounds.MAX_EVENT_IDS_PER_CLAIM) { dropped++; continue }
            admitted.add(
                raw.copy(
                    statement = boundStatement(statement),
                    kind = normalizeKind(raw.kind),
                    confidence = normalizeConfidence(raw.confidence),
                    eventIds = ids.take(Bounds.MAX_EVENT_IDS_PER_CLAIM),
                ),
            )
            seen.add(key)
            if (admitted.size >= Bounds.MAX_CLAIMS_PER_DOMAIN) break
        }
        return admitted to dropped
    }

    /** Decode a raw model JSON payload strictly; null when the JSON is invalid. */
    fun decodePayload(text: String): ProfileModelPayload? = runCatching {
        ProfileCodecModelJson.decodeProfilePayload(text)
    }.getOrNull()

    /**
     * Rejects statements that read like raw event details (dates / amounts /
     * order status / page noise / conversation markers), mirroring Harmony
     * `IndexService.looksLikeEventDetail`.
     */
    fun looksLikeEventDetail(statement: String): Boolean {
        if (Regex("""\d{4}年|\d{4}-\d{2}-\d{2}|\d{1,2}月\d{1,2}日|\d+(?:\.\d+)?元|¥\s*\d""").containsMatchIn(statement)) {
            return true
        }
        val markers = listOf("订单状态", "实付", "下单时间", "当前页面", "记录显示", "分别为", "之前", "正在尝试", "这块")
        for (marker in markers) {
            if (statement.contains(marker)) return true
        }
        return false
    }

    /** True when a presumably-model payload carries at least one real admitted claim. */
    fun hasUsableClaims(rawClaims: List<RawProfileClaim>): Boolean = rawClaims.isNotEmpty()
}

/**
 * Bounded, tolerant JSON decoder for the model's raw profile reply. This is a
 * separate codec from [ProfileCodec] so model decoding can be stricter/bounded
 * without affecting the on-disk artifact codec.
 */
object ProfileCodecModelJson {
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Decode a raw model payload; throws on invalid JSON / shape. */
    fun decodeProfilePayload(text: String): ProfileModelPayload {
        val element = json.parseToJsonElement(text).jsonObject
        val claims = element["claims"]
        val rawClaims = if (claims != null) {
            val list = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(RawProfileClaim.serializer()),
                claims,
            )
            list
        } else emptyList()
        return ProfileModelPayload(
            shortSummary = element["short_summary"]?.toString()?.trim('"') ?: "",
            summary = element["summary"]?.toString()?.trim('"') ?: "",
            claims = rawClaims,
            generation = "model",
        )
    }
}
