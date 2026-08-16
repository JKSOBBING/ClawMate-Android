package com.clawmate.app.profile.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire model for the `.mobi-profile` /
 * `application/vnd.mobiinfra.profile+json` user-profile summary that is shared
 * and imported via [ProfileShareActivity] (the "profile knock share" flow).
 *
 * HarmonyOS source of truth (read-only reference):
 *   entry/src/main/ets/utils/ProfileKnockShareService.ets
 *   entry/src/main/ets/pages/Index.ets  (buildProfileShareEnvelope /
 *                                        ProfileShareSnapshot & friends)
 *
 * Only the *portable, local, on-device* part of the share is modelled here.
 * The Harmony side additionally performs cloud-affinity analysis of two
 * profiles (the "同频报告" via an LLM). That cloud behaviour is deliberately
 * **not** implemented on Android: this phase only imports and merges allowed
 * local profile fields into the device-persisted local avatar profile. No
 * secrets, no cloud calls, no fabricated capability.
 *
 * All DTO fields are optional so that malformed / partial third-party payloads
 * parse tolerantly and are then validated by [ProfileShareImporter].
 */

/** UTD identifier used by Harmony ShareKit for profile shares. */
const val PROFILE_SHARE_UTD: String = "com.mobiinfra.profile-json"

/** File extension used for shared profile files. */
const val PROFILE_SHARE_FILE_EXTENSION: String = ".mobi-profile"

/** MIME type used for shared profile files. */
const val PROFILE_SHARE_MIME_TYPE: String = "application/vnd.mobiinfra.profile+json"

/** Files beyond this byte size are rejected as oversized. Mirrors the source cap. */
const val MAX_RECEIVED_PROFILE_BYTES: Int = 512 * 1024

/** The only envelope schema version this import pipeline understands. */
const val PROFILE_SHARE_SCHEMA_VERSION: Int = 1

/** App id that produces the canonical ClawMate profile-share envelope. */
const val PROFILE_SHARE_APP_ID: String = "com.clawmate.app"

/** Upper bound applied to any single imported domain summary (characters). */
const val PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH: Int = 300

/** Envelope payload: the YAML-like JSON document inside a `.mobi-profile` file. */
@Serializable
data class ProfileShareEnvelopeDto(
    val schemaVersion: Int? = null,
    val typeId: String? = null,
    @SerialName("appId")
    val appId: String? = null,
    @SerialName("appName")
    val appName: String? = null,
    @SerialName("generatedAt")
    val generatedAt: String? = null,
    @SerialName("profileText")
    val profileText: String? = null,
    @SerialName("profile")
    val profile: ProfileShareSnapshotDto? = null,
)

/** A `profile.domains` entry inside a shared envelope (Port of ProfileShareDomainSnapshot). */
@Serializable
data class ProfileShareDomainDto(
    val domain: String? = null,
    val title: String? = null,
    val desc: String? = null,
    @SerialName("fullDesc")
    val fullDesc: String? = null,
    @SerialName("claims")
    val claims: List<String> = emptyList(),
    @SerialName("eventCount")
    val eventCount: Int? = null,
)

/**
 * The structured `profile` object inside a shared envelope.
 * Port of `ProfileShareSnapshot` (Index.ets). Only [domains] is consumed by the
 * local import pipeline; the other fields are metadata that a future cloud
 * affinity feature could read but which this phase never fabricates.
 */
@Serializable
data class ProfileShareSnapshotDto(
    @SerialName("updatedLabel")
    val updatedLabel: String? = null,
    val completeness: Int? = null,
    @SerialName("totalEvents")
    val totalEvents: Int? = null,
    val empty: Boolean? = null,
    val domains: List<ProfileShareDomainDto> = emptyList(),
    val recommendations: List<ProfileShareRecommendationDto> = emptyList(),
    @SerialName("activeChatTitle")
    val activeChatTitle: String? = null,
    @SerialName("recentChatMessages")
    val recentChatMessages: List<String> = emptyList(),
)

/** A `profile.recommendations` entry (kept for structural parity; not merged). */
@Serializable
data class ProfileShareRecommendationDto(
    val domain: String? = null,
    val tag: String? = null,
    val title: String? = null,
    val desc: String? = null,
    val meta: String? = null,
    val completed: Boolean? = null,
)

/**
 * Stable, deterministic error token for a rejected profile import.
 *
 * @property OVERSIZED payload larger than [MAX_RECEIVED_PROFILE_BYTES].
 * @property MALFORMED payload is not valid JSON (or not a JSON object).
 * @property UNSUPPORTED_VERSION envelope declares a schema version this importer
 *   does not understand (not [PROFILE_SHARE_SCHEMA_VERSION]).
 * @property MISSING_FIELD payload is missing a required structural field (the
 *   shared `profileText` / profile body) so it cannot be treated as a profile.
 * @property UNRECOGNIZED payload parsed but is not recognisable as a ClawMate
 *   `.mobi-profile` envelope (neither UTD typeId nor appId+version identity).
 */
enum class ProfileShareErrorCode { OVERSIZED, MALFORMED, UNSUPPORTED_VERSION, MISSING_FIELD, UNRECOGNIZED }

/** Typed result of parsing + validating a shared `.mobi-profile` payload. */
sealed interface ProfileShareParseResult {
    data class Success(val envelope: ProfileShareEnvelopeDto) : ProfileShareParseResult
    data class Error(val code: ProfileShareErrorCode, val message: String) : ProfileShareParseResult
}

/**
 * Deterministic, honest outcome of merging a valid shared envelope into the
 * local device profile. [profile] is the resulting local profile (the local
 * value is authoritative; incoming summaries only fill blank local fields).
 * [importedDomains] lists the persona-domain keys that were actually filled
 * from the shared payload (empty when nothing local was blank).
 */
data class ProfileImportOutcome(
    val profile: com.clawmate.app.profile.avatar.domain.LocalAvatarProfile,
    val importedDomains: List<String>,
)
