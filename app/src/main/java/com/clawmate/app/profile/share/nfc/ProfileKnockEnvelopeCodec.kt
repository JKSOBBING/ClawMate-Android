package com.clawmate.app.profile.share.nfc

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import com.clawmate.app.profile.share.MAX_RECEIVED_PROFILE_BYTES
import com.clawmate.app.profile.share.PROFILE_SHARE_APP_ID
import com.clawmate.app.profile.share.PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH
import com.clawmate.app.profile.share.PROFILE_SHARE_MIME_TYPE
import com.clawmate.app.profile.share.PROFILE_SHARE_SCHEMA_VERSION
import com.clawmate.app.profile.share.PROFILE_SHARE_UTD
import com.clawmate.app.profile.share.ProfileShareDomainDto
import com.clawmate.app.profile.share.ProfileShareEnvelopeDto
import com.clawmate.app.profile.share.ProfileShareImporter
import com.clawmate.app.profile.share.ProfileShareParseResult
import com.clawmate.app.profile.share.ProfileShareSnapshotDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/**
 * Pure, device-free codec for the NDEF **send** payload and for validating
 * incoming NDEF bytes — a faithful Android port of the Harmony share format in
 * `ProfileKnockShareService.ets`.
 *
 * Send contract (mirrors Harmony `buildProfileShareEnvelope`):
 *  - schemaVersion = 1, typeId = `com.mobiinfra.profile-json`,
 *    appId = `com.clawmate.app`, appName = `ClawMate`,
 *    generatedAt = ISO-8601 timestamp, profileText = human portrait,
 *    profile = [ProfileShareSnapshotDto] carrying only the four persona domains.
 *  - The serialized JSON is capped at [MAX_RECEIVED_PROFILE_BYTES]; [buildOutgoingEnvelope]
 *    refuses to emit anything above that cap so Send can never encode an oversized payload.
 *
 * Receive contract (same envelope identity/version rules as [ProfileShareImporter]):
 *  - NDEF must be a MIME/TNF payload matching [PROFILE_SHARE_MIME_TYPE] (read-side
 *    [parseAndValidateNfc] validates the declared MIME/type independently of the body).
 *  - Payload must be ≤ [MAX_RECEIVED_PROFILE_BYTES], valid UTF-8, parseable JSON
 *    object, supported schema version, and match the ClawMate identity / body rules.
 *
 * No NDEF object is referenced here so the whole codec is unit-testable offline.
 */
object ProfileKnockEnvelopeCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    /** ISO-8601 timestamp used for generatedAt (deterministic, injectable). */
    fun isoNow(instant: Instant = Instant.now()): String = instant.toString()

    /**
     * Build the portable ClawMate envelope from a [LocalAvatarProfile]. Sending
     * is exclusively on-device: only the already-curated local profile domains
     * are copied; nothing fabricated, no secrets, no remote data.
     */
    fun buildOutgoingEnvelope(
        profile: LocalAvatarProfile,
        generatedAt: String = isoNow(),
        appName: String = APP_NAME,
    ): ProfileShareEnvelopeDto {
        // The four persona domains, mirroring the Harmony snapshot slice.
        val domains = defaultDomains().mapNotNull { key ->
            val src = profile.domains.firstOrNull { it.domain == key }
                ?: return@mapNotNull null
            if (src.shortSummary.isBlank()) return@mapNotNull null
            ProfileShareDomainDto(
                domain = src.domain,
                title = src.domain,
                desc = src.shortSummary.take(PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH),
                fullDesc = src.shortSummary.take(PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH),
                claims = emptyList(),
                eventCount = 0,
            )
        }
        val snapshot = ProfileShareSnapshotDto(
            updatedLabel = if (profile.updatedAt.isBlank()) "" else profile.updatedAt,
            completeness = localProfileCompleteness(profile),
            totalEvents = 0,
            empty = domains.isEmpty(),
            domains = domains,
            recommendations = emptyList(),
        )
        return ProfileShareEnvelopeDto(
            schemaVersion = PROFILE_SHARE_SCHEMA_VERSION,
            typeId = PROFILE_SHARE_UTD,
            appId = PROFILE_SHARE_APP_ID,
            appName = appName,
            generatedAt = generatedAt,
            profileText = buildProfileShareText(snapshot),
            profile = snapshot,
        )
    }

    /**
     * Serialize a [ProfileShareEnvelopeDto] to the canonical UTF-8 byte payload
     * that is written to an NDEF message. Returns null when the envelope is
     * null / the serialized form exceeds the hard size cap — oversize is never sent.
     */
    fun encodeOutgoingPayload(envelope: ProfileShareEnvelopeDto, maxBytes: Int = MAX_RECEIVED_PROFILE_BYTES): ByteArray? {
        val text = try {
            json.encodeToString(envelope)
        } catch (e: Exception) {
            return null
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (bytes.isEmpty() || bytes.size > maxBytes) null else bytes
    }

    /**
     * Validate an incoming NDEF payload.
     *
     * @param declaredMime the NDEF MIME/type read from the tag (lower-cased or
     *   empty when the tag carries none). A non-matching MIME is rejected before
     *   any decode even if the body happens to parse — the peer must have shared
     *   a profile, not arbitrary data.
     * @param rawBytes the raw NDEF payload bytes (already size-capped by the read
     *   side, but re-checked here).
     */
    fun parseAndValidateNfc(
        declaredMime: String?,
        rawBytes: ByteArray,
        maxBytes: Int = MAX_RECEIVED_PROFILE_BYTES,
    ): ProfileShareParseResult {
        if (declaredMime == null || declaredMime.uppercase() != PROFILE_SHARE_MIME_TYPE.uppercase()) {
            return ProfileShareParseResult.Error(
                com.clawmate.app.profile.share.ProfileShareErrorCode.UNRECOGNIZED,
                "NDEF 类型不匹配(期望 $PROFILE_SHARE_MIME_TYPE，实际 ${declaredMime ?: "无"})",
            )
        }
        if (rawBytes.isEmpty() || rawBytes.size > maxBytes) {
            return ProfileShareParseResult.Error(
                com.clawmate.app.profile.share.ProfileShareErrorCode.OVERSIZED,
                "一碰分享画像大小(${rawBytes.size} B)超出允许上限($maxBytes B)",
            )
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBytes)).toString()
        } catch (e: Exception) {
            return ProfileShareParseResult.Error(
                com.clawmate.app.profile.share.ProfileShareErrorCode.MALFORMED,
                "画像内容不是有效 UTF-8 文本",
            )
        }
        // Delegate all structural/version/identity validation to the existing importer
        // so the NFC path can never bypass the shared profile-merge rules.
        return ProfileShareImporter.parseAndValidate(text, contentLengthBytes = rawBytes.size, maxBytes = maxBytes)
    }

    /**
     * Deterministic, stable signature for an incoming message so the same
     * `.mobi-profile` bytes received twice are recognised and not re-imported.
     * Mirrors the Harmony `profileEventSignature` hash (raw-length + weighted hash).
     */
    fun messageSignature(bytes: ByteArray): String {
        var hash = 0
        bytes.forEach { b ->
            hash = (hash * 131 + (b.toInt() and 0xff)) % Int.MAX_VALUE
        }
        return "${bytes.size}#$hash"
    }

    private fun defaultDomains(): List<String> =
        listOf("eat", "wear", "live", "travel")

    private fun buildProfileShareText(snapshot: ProfileShareSnapshotDto): String {
        val lines = mutableListOf<String>()
        val completeness = snapshot.completeness ?: 0
        lines.add("应用：ClawMate")
        lines.add("画像完整度：${completeness}%")
        lines.add("画像领域：")
        if (completeness == 0) {
            lines.add("- 暂无领域摘要")
        } else {
            snapshot.domains.forEach { d ->
                val value = d.fullDesc?.takeIf { it.isNotBlank() } ?: d.desc ?: ""
                lines.add("- ${d.title ?: d.domain}(${d.domain})：${value}")
            }
        }
        return lines.joinToString("\n")
    }

    private const val APP_NAME: String = "ClawMate"
}
