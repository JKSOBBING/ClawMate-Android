package com.clawmate.app.datamanager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed, bounded data models for the Phase-32 **profile index / claims**
 * foundation. This is a faithful Kotlin port of the Harmony
 * `DataManagerTypes.ets` profile surfaces (`ProfileState` / `ProfileDomainState`
 * / `ProfileClaim` / `RawProfileClaim` / `ProfileModelPayload`) and the
 * `IndexService.buildProfileDocument` data contract, reduced to Android reality:
 *
 *  - **Four domains only** (`eat`/`wear`/`live`/`travel`) matching the Home
 *    pillar grid — `work`/`other` are explicitly excluded so a claim can never
 *    leak into a domain the four-pillar Home surface does not render.
 *  - Claims are **never fabricated**: every model claim must reference real
 *    indexed `event_id`s and pass strict bounds/whitelist validation before it
 *    is admitted. With no model config, domains are a **local-only** projection
 *    whose summaries come only from the real consolidated-record text and whose
 *    `claims` stay empty.
 *  - Every shape is `@Serializable` with tolerant decode defaults, so a corrupt,
 *    missing or oversized on-disk artifact is honestly recovered to a defensive
 *    empty state rather than thrown and hence can never falsify a run.
 *
 * `generation` mirrors Harmony's `ProfileDomainState.generation` union
 * (`empty` / `model` / `model_empty` / `model_unavailable` / `model_error`);
 * Android adds `local_only` for the no-model local projection. The typed
 * `ProfileBuildStatus` carries an honest, countable overall outcome.
 */
object ProfileTypes {

    /** Profile-claim schema / version markers (mirror Harmony, Android-scoped). */
    object Schemas {
        const val PROFILE_STATE_V1: String = "datamanager-profile-state-v1"
        const val PROFILE_INDEX_V1: String = "datamanager-profile-index-v1"
    }

    /**
     * Ordered four-pillar profile domains. Port of the Harmony `DOMAIN_KEYS`
     * filtered to the four the Home grid renders (Harmony's `work`/`other` are
     * deliberately not surfaced on Android's home profile).
     */
    val PROFILE_DOMAIN_KEYS: List<String> = listOf("eat", "wear", "live", "travel")

    /** Human display names, ported from Harmony `DOMAIN_DISPLAY_NAMES`. */
    val PROFILE_DOMAIN_DISPLAY_NAMES: Map<String, String> = mapOf(
        "eat" to "吃",
        "wear" to "穿",
        "live" to "住",
        "travel" to "行",
    )

    /** Bounding constants for claims (mirror Harmony's normalization caps). */
    object Bounds {
        /** Max admitted claims per domain (Harmony caps at 8; we stay at 5 for the Home surface). */
        const val MAX_CLAIMS_PER_DOMAIN: Int = 5

        /** Max characters of a normalized claim statement. */
        const val MAX_CLAIM_STATEMENT_CHARACTERS: Int = 120

        /** Max characters of a normalized per-domain summary. */
        const val MAX_DOMAIN_SUMMARY_CHARACTERS: Int = 300

        /** Max event ids referenced by a single claim. */
        const val MAX_EVENT_IDS_PER_CLAIM: Int = 8

        /** Confidence tokens the model may emit. */
        val CONFIDENCE_TOKENS: Set<String> = setOf("high", "medium", "low")

        /** Claim `kind` tokens the model may emit (Harmony `normalizeProfileKind`). */
        val CLAIM_KINDS: Set<String> = setOf(
            "preference", "habit", "topic", "service", "relation", "place", "observation",
        )

        /** Minimum independent evidence events a claim needs (Harmony worthiness). */
        const val MIN_EVIDENCE_EVENTS_PER_CLAIM: Int = 2
    }

    /**
     * One claim in the persisted/delivered profile (mirrors Harmony `ProfileClaim`).
     * `event_ids` must be a subset of the real indexed events for its domain.
     */
    @Serializable
    data class ProfileClaim(
        @SerialName("statement") val statement: String = "",
        @SerialName("kind") val kind: String = "observation",
        @SerialName("confidence") val confidence: String = "low",
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
        @SerialName("evidence_count") val evidenceCount: Int = 0,
        @SerialName("start_date") val startDate: String = "",
        @SerialName("end_date") val endDate: String = "",
        @SerialName("example") val example: String = "",
    )

    /**
     * One domain's persisted profile (mirrors Harmony `ProfileDomainState`).
     * `event_ids` are the real indexed domain events; `claims` are strictly
     * validated. `generation` is one of Harmony's tokens plus `local_only`.
     */
    @Serializable
    data class ProfileDomainState(
        @SerialName("domain") val domain: String = "",
        @SerialName("display_name") val displayName: String = "",
        @SerialName("input_hash") val inputHash: String = "",
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
        @SerialName("event_count") val eventCount: Int = 0,
        @SerialName("start_date") val startDate: String = "",
        @SerialName("end_date") val endDate: String = "",
        @SerialName("short_summary") val shortSummary: String = "",
        @SerialName("summary") val summary: String = "",
        @SerialName("claims") val claims: List<ProfileClaim> = emptyList(),
        @SerialName("generation") val generation: String = "empty",
    )

    /**
     * The persisted profile state (mirrors Harmony `ProfileState`) plus a typed
     * `status` so readers/reporters know whether the profile is local-only,
     * model-backed, partial (some domains model, some not) or the honest empty.
     */
    @Serializable
    data class ProfileState(
        @SerialName("schema") val schema: String = Schemas.PROFILE_STATE_V1,
        @SerialName("generated_at") val generatedAt: String = "",
        @SerialName("event_count") val eventCount: Int = 0,
        @SerialName("start_date") val startDate: String = "",
        @SerialName("end_date") val endDate: String = "",
        @SerialName("claim_count") val claimCount: Int = 0,
        @SerialName("status") val status: String = ProfileBuildStatus.EMPTY.token,
        @SerialName("domains") val domains: Map<String, ProfileDomainState> = emptyMap(),
    )

    /** Bounded, summarizable outcome of one profile build run. */
    data class ProfileBuildResult(
        val eventCount: Int,
        val domainCounts: Map<String, Int> = emptyMap(),
        val claimCount: Int = 0,
        val generatedAt: String = "",
        val status: ProfileBuildStatus = ProfileBuildStatus.EMPTY,
        /** Per-domain generation tokens (honest, countable). */
        val domainGeneration: Map<String, String> = emptyMap(),
    )

    /**
     * Honest overall profile outcome. Always typed; never conflates a local-only
     * projection with model claims.
     */
    enum class ProfileBuildStatus(val token: String) {
        /** No profile was produced (no indexed events at all). */
        EMPTY("empty"),

        /** A real local-only projection (no model configured or used). */
        LOCAL_ONLY("local_only"),

        /** Model-backed profile with at least one model claim admitted. */
        MODEL("model"),

        /** Model was attempted, but every domain stayed local/empty (typed partial). */
        PARTIAL("partial"),

        /** The model path failed; the deterministic local result was preserved. */
        ERROR("error");

        companion object {
            fun fromToken(value: String): ProfileBuildStatus =
                entries.firstOrNull { it.token == value } ?: EMPTY
        }
    }

    /**
     * A domain profile projection before model filling. Built purely from real
     * indexed events: the summary is derived deterministically from the event
     * text, `claims` are empty, and `generation` is `local_only`. This is the
     * honest local fallback that a model failure must never mask.
     */
    data class LocalDomainProjection(
        val state: ProfileDomainState,
        val eventText: String,
    )

    /** Raw model claim as parsed from the JSON reply (mirrors Harmony `RawProfileClaim`). */
    @Serializable
    data class RawProfileClaim(
        @SerialName("statement") val statement: String = "",
        @SerialName("kind") val kind: String = "",
        @SerialName("confidence") val confidence: String = "",
        @SerialName("event_ids") val eventIds: List<String> = emptyList(),
        @SerialName("example") val example: String = "",
    )

    /** Parsed model payload for one domain (mirrors Harmony `ProfileModelPayload`). */
    @Serializable
    data class ProfileModelPayload(
        @SerialName("short_summary") val shortSummary: String = "",
        @SerialName("summary") val summary: String = "",
        @SerialName("claims") val claims: List<RawProfileClaim> = emptyList(),
        @SerialName("generation") val generation: String = "model",
    )
}
