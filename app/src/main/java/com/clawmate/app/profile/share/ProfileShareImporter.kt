package com.clawmate.app.profile.share

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import kotlinx.serialization.json.Json

/**
 * Pure, deterministic import/merge pipeline for a shared `.mobi-profile`
 * (application/vnd.mobiinfra.profile+json) payload.
 *
 * It is deliberately device-free and side-effect-free so it can be unit tested
 * with no JVM-device, no network and no Android runtime:
 *  - [parseAndValidate] turns raw text into a [ProfileShareParseResult],
 *    rejecting malformed, oversized, unsupported-version and missing-field
 *    payloads with a stable [ProfileShareErrorCode].
 *  - [mergeIntoLocal] applies only *allowed local profile fields* (the four
 *    persona-domain summaries) into a [LocalAvatarProfile] with deterministic
 *    precedence: the local device value is authoritative and an incoming value
 *    fills a local field only when that local field is blank. Nothing is ever
 *    overwritten, no secrets are imported, and no cloud call is ever made.
 *
 * HarmonyOS source of truth (reference): `ProfileKnockShareService.ets`
 * (envelope validation, size cap) and `buildProfileShareSnapshot` in
 * `Index.ets` (the `profile.domains` shape). The Harmony cloud-affinity
 * analysis is intentionally not ported.
 */
object ProfileShareImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        // Harden against deep nesting from a hostile / malformed payload.
        isLenient = false
    }

    /**
     * Parse + validate a raw `.mobi-profile` payload. Returns [ProfileShareParseResult.Success]
     * with the decoded envelope, or a typed [ProfileShareParseResult.Error].
     *
     * Checks, in priority order: byte-size cap → parseable JSON object → schema
     * version support → envelope identity / presence of the profile body.
     */
    fun parseAndValidate(
        raw: String,
        contentLengthBytes: Int = rawByteLength(raw),
        maxBytes: Int = MAX_RECEIVED_PROFILE_BYTES,
    ): ProfileShareParseResult {
        if (contentLengthBytes <= 0 || contentLengthBytes > maxBytes) {
            return ProfileShareParseResult.Error(
                ProfileShareErrorCode.OVERSIZED,
                "画像文件大小($contentLengthBytes B)超出允许上限($maxBytes B)",
            )
        }

        val envelope = try {
            json.decodeFromString<ProfileShareEnvelopeDto>(raw)
        } catch (e: Exception) {
            return ProfileShareParseResult.Error(
                ProfileShareErrorCode.MALFORMED,
                "无法解析画像文件：${firstLine(e.message)}",
            )
        }

        envelope.schemaVersion?.let { version ->
            if (version != PROFILE_SHARE_SCHEMA_VERSION) {
                return ProfileShareParseResult.Error(
                    ProfileShareErrorCode.UNSUPPORTED_VERSION,
                    "不支持的画像文件版本(schemaVersion=$version，支持 $PROFILE_SHARE_SCHEMA_VERSION)",
                )
            }
        }

        if (!isIdentityValid(envelope)) {
            return ProfileShareParseResult.Error(
                ProfileShareErrorCode.MISSING_FIELD,
                if (envelope.profileText.isNullOrBlank()) {
                    "画像文件内容为空或缺少 profileText 字段，无法导入"
                } else {
                    "无法识别为 ClawMate 画像文件：缺少有效的 typeId/appId 标识"
                },
            )
        }

        return ProfileShareParseResult.Success(envelope)
    }

    /**
     * A payload is a valid ClawMate profile envelope when its body is present
     * (`profileText` non-blank) and its identity matches either the profile-share
     * UTD `typeId` or the `appId` + supported schema version — a direct port of
     * `ProfileKnockShareService.isProfileShareEnvelope`.
     */
    private fun isIdentityValid(envelope: ProfileShareEnvelopeDto): Boolean {
        if (envelope.profileText.isNullOrBlank()) {
            return false
        }
        if (envelope.typeId == PROFILE_SHARE_UTD) {
            return true
        }
        return envelope.appId == PROFILE_SHARE_APP_ID &&
            envelope.schemaVersion == PROFILE_SHARE_SCHEMA_VERSION
    }

    /**
     * Merge a validated envelope into the local profile. Precedence is
     * deterministic and local-first: an imported domain summary is applied only
     * to a local persona domain whose `shortSummary` is currently blank.
     *
     * @return a [ProfileImportOutcome] with the resulting profile and the list of
     *   domain keys that were actually filled from the shared payload.
     */
    fun mergeIntoLocal(
        local: LocalAvatarProfile,
        envelope: ProfileShareEnvelopeDto,
    ): ProfileImportOutcome {
        val incoming = orderedIncomingDomains(envelope)
        val imported = mutableListOf<String>()

        val mergedDomains = defaultProfileDomains().map { def ->
            // Only ever merge into the four known persona domains.
            val current = local.domains.firstOrNull { it.domain == def.domain }
                ?.takeIf { it.domain == def.domain }
                ?: AvatarDomainSnapshot(
                    domain = def.domain,
                    displayName = def.displayName,
                    shortSummary = "",
                    summary = "",
                )
            if (current.shortSummary.isNotBlank()) {
                return@map current
            }
            val incomingValue = incoming[def.domain]
            val candidate = incomingValue?.trim().orEmpty()
            if (candidate.isEmpty()) {
                return@map current
            }
            imported += def.domain
            current.copy(
                shortSummary = candidate.take(PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH),
            )
        }

        return ProfileImportOutcome(
            profile = local.copy(domains = mergedDomains),
            importedDomains = imported,
        )
    }

    /**
     * Build a stable map of candidate summary text per recognised persona domain.
     * The source snapshot exposes both `desc` (short) and `fullDesc` (longer);
     * the local `shortSummary` maps most naturally to `fullDesc` when present,
     * falling back to `desc`. The four keyed persona domains are `eat` / `wear`
     * / `live` / `travel`; any other domain keys are ignored.
     */
    private fun orderedIncomingDomains(
        envelope: ProfileShareEnvelopeDto,
    ): Map<String, String> {
        val snapshot = envelope.profile ?: return emptyMap()
        val known = defaultProfileDomains().map { it.domain }.toSet()
        val result = LinkedHashMap<String, String>()
        snapshot.domains.forEach { dto ->
            val domain = dto.domain?.trim().orEmpty()
            if (domain.isEmpty() || domain !in known) return@forEach
            val summary = (dto.fullDesc?.takeIf { it.isNotBlank() } ?: dto.desc).orEmpty()
            if (summary.isBlank()) return@forEach
            // First occurrence wins; later duplicates do not override.
            result.putIfAbsent(domain, summary)
        }
        return result
    }

    /** Best-effort UTF-8 byte length; falls back to String.length for safety. */
    private fun rawByteLength(text: String): Int =
        try {
            text.toByteArray(Charsets.UTF_8).size
        } catch (e: Exception) {
            text.length
        }

    private fun firstLine(message: String?): String =
        message?.lineSequence()?.firstOrNull()?.take(200) ?: "未知错误"
}
