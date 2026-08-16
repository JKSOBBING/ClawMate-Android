package com.clawmate.app.profile.share

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, device-free unit tests for the Phase-17 profile-share
 * import/merge pipeline: parsing, validation (malformed / oversized /
 * unsupported-version / missing-field), local-first merge precedence, and
 * backward compatibility with the schema-v1 ClawMate envelope.
 */
class ProfileShareImporterTest {

    // ---- parsing / validation -------------------------------------------------

    private fun envelopeJson(
        schemaVersion: Int? = 1,
        typeId: String? = PROFILE_SHARE_UTD,
        appId: String? = PROFILE_SHARE_APP_ID,
        profileText: String? = "应用：ClawMate\n生成时间：2024-01-01",
        profile: String? = null,
    ): String {
        val fields = mutableListOf<String>()
        if (schemaVersion != null) fields += "\"schemaVersion\":$schemaVersion"
        if (typeId != null) fields += "\"typeId\":\"$typeId\""
        if (appId != null) fields += "\"appId\":\"$appId\""
        if (profileText != null) fields += "\"profileText\":\"${escape(profileText)}\""
        if (profile != null) fields += "\"profile\":$profile"
        return "{${fields.joinToString(",")}}"
    }

    private fun domainsJson(vararg domains: String) = "[${domains.joinToString(",")}]"

    private fun domainJson(
        domain: String,
        desc: String = "",
        fullDesc: String = "",
        claims: String = "[]",
    ) = "{\"domain\":\"${escape(domain)}\",\"title\":\"${escape(domain)}\",\"desc\":\"${escape(desc)}\",\"fullDesc\":\"${escape(fullDesc)}\",\"claims\":$claims}"

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun fullEnvelope() = envelopeJson(
        profile = """{"completeness":80,"totalEvents":120,"domains":${domainsJson(
            domainJson("eat", fullDesc = "爱探店，喜欢火锅与粤菜"),
            domainJson("travel", fullDesc = "喜欢周末短途旅行"),
        )}}""",
    )

    @Test
    fun `valid ClawMate envelope parses to success`() {
        val result = ProfileShareImporter.parseAndValidate(fullEnvelope())
        assertTrue(result is ProfileShareParseResult.Success)
        val envelope = (result as ProfileShareParseResult.Success).envelope
        assertEquals(1, envelope.schemaVersion)
        assertEquals(2, envelope.profile?.domains?.size)
    }

    @Test
    fun `malformed payload returns malformed error`() {
        val result = ProfileShareImporter.parseAndValidate("not json {{{{")
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.MALFORMED, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `oversized payload returns oversized error`() {
        val big = fullEnvelope() + " ".repeat(MAX_RECEIVED_PROFILE_BYTES + 1024)
        val result = ProfileShareImporter.parseAndValidate(big)
        assertTrue("expected oversized error, got $result", result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.OVERSIZED, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `payload over explicit cap returns oversized`() {
        val text = fullEnvelope()
        val result = ProfileShareImporter.parseAndValidate(text, maxBytes = 1)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.OVERSIZED, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `unsupported schema version returns unsupported-version error`() {
        val text = envelopeJson(schemaVersion = 99)
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.UNSUPPORTED_VERSION, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `missing profileText returns missing-field error`() {
        val text = envelopeJson(profileText = null)
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.MISSING_FIELD, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `blank profileText returns missing-field error`() {
        val text = envelopeJson(profileText = "   ")
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.MISSING_FIELD, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `unrecognized identity returns missing-field error`() {
        // Valid JSON with a body but neither UTD typeId nor ClawMate appId+version.
        val text = envelopeJson(
            typeId = "com.other.app.profile",
            appId = "com.other.app",
            profileText = "something",
        )
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.MISSING_FIELD, (result as ProfileShareParseResult.Error).code)
    }

    @Test
    fun `schema version present without app identity but with utd type passes`() {
        // Harmony accepts a payload whose typeId is the canonical UTD even when
        // the appId/version are not ClawMate's — portable fallback.
        val text = envelopeJson(appId = "com.other.app")
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Success)
    }

    // ---- merge precedence ------------------------------------------------------

    @Test
    fun `incoming summary fills a blank local domain`() {
        val local = LocalAvatarProfile.empty
        val outcome = ProfileShareImporter.mergeIntoLocal(local, parseOk(fullEnvelope()))
        assertEquals("爱探店，喜欢火锅与粤菜", outcome.profile.domains.first { it.domain == "eat" }.shortSummary)
        assertTrue("travel" in outcome.importedDomains)
    }

    @Test
    fun `existing local value is never overwritten`() {
        val local = LocalAvatarProfile.empty.copy(
            domains = com.clawmate.app.profile.avatar.domain.defaultProfileDomains().map {
                if (it.domain == "eat") it.copy(shortSummary = "已有本地内容") else it
            },
        )
        val outcome = ProfileShareImporter.mergeIntoLocal(local, parseOk(fullEnvelope()))
        // Local wins deterministically; incoming is ignored for the filled field.
        assertEquals("已有本地内容", outcome.profile.domains.first { it.domain == "eat" }.shortSummary)
        assertFalse("eat" in outcome.importedDomains)
        assertTrue("travel" in outcome.importedDomains)
    }

    @Test
    fun `unknown domains and blank summaries are ignored`() {
        val envelope = parseOk(
            envelopeJson(profile = """{"domains":${domainsJson(
                domainJson("unknown", fullDesc = "不该导入"),
                domainJson("eat", fullDesc = ""),
                domainJson("", fullDesc = "没有 key"),
            )}}"""),
        )
        val outcome = ProfileShareImporter.mergeIntoLocal(LocalAvatarProfile.empty, envelope)
        assertTrue(outcome.importedDomains.isEmpty())
        assertTrue(outcome.profile.domains.all { it.shortSummary.isBlank() })
    }

    @Test
    fun `fullDesc is preferred over desc as the local short summary`() {
        val envelope = parseOk(
            envelopeJson(profile = """{"domains":${domainsJson(
                domainJson("wear", desc = "短描述", fullDesc = "长描述，优先使用"),
            )}}"""),
        )
        val outcome = ProfileShareImporter.mergeIntoLocal(LocalAvatarProfile.empty, envelope)
        assertEquals("长描述，优先使用", outcome.profile.domains.first { it.domain == "wear" }.shortSummary)
    }

    @Test
    fun `first duplicate domain wins deterministically`() {
        val envelope = parseOk(
            envelopeJson(profile = """{"domains":${domainsJson(
                domainJson("eat", fullDesc = "第一次出现"),
                domainJson("eat", fullDesc = "第二次出现"),
            )}}"""),
        )
        val outcome = ProfileShareImporter.mergeIntoLocal(LocalAvatarProfile.empty, envelope)
        assertEquals("第一次出现", outcome.profile.domains.first { it.domain == "eat" }.shortSummary)
    }

    @Test
    fun `over-long imported summary is truncated to the safe cap`() {
        val long = "x".repeat(PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH + 500)
        val envelope = parseOk(
            envelopeJson(profile = """{"domains":${domainsJson(domainJson("eat", fullDesc = long))}}"""),
        )
        val outcome = ProfileShareImporter.mergeIntoLocal(LocalAvatarProfile.empty, envelope)
        val value = outcome.profile.domains.first { it.domain == "eat" }.shortSummary
        assertEquals(PROFILE_SHARE_MAX_DOMAIN_SUMMARY_LENGTH, value.length)
    }

    @Test
    fun `merge only touches the four known persona domains`() {
        val envelope = parseOk(fullEnvelope())
        val before = LocalAvatarProfile.empty
        val outcome = ProfileShareImporter.mergeIntoLocal(before, envelope)
        assertEquals(
            listOf("eat", "wear", "live", "travel"),
            outcome.profile.domains.map { it.domain },
        )
        assertEquals(before.displayName, outcome.profile.displayName)
        assertEquals(before.gender, outcome.profile.gender)
        assertEquals(before.age, outcome.profile.age)
    }

    @Test
    fun `completeness rises when blank domains are filled`() {
        val before = LocalAvatarProfile.empty
        val outcome = ProfileShareImporter.mergeIntoLocal(before, parseOk(fullEnvelope()))
        assertTrue(localProfileCompleteness(outcome.profile) > localProfileCompleteness(before))
    }

    @Test
    fun `envelope with no profile object merges nothing yet stays successful`() {
        // Backward-compatible: a schema-v1 ClawMate envelope that carries only
        // profileText (no structured profile object) is valid and imports zero.
        val envelope = parseOk(envelopeJson())
        val outcome = ProfileShareImporter.mergeIntoLocal(LocalAvatarProfile.empty, envelope)
        assertTrue(outcome.importedDomains.isEmpty())
        assertEquals(0, localProfileCompleteness(outcome.profile))
    }

    @Test
    fun `malformed profile sub-object is rejected as malformed`() {
        // "profile" must be a JSON object; a scalar or wrong-typed value there is
        // structurally malformed and rejected honestly (never silently empty).
        val text = envelopeJson(profile = "123")
        val result = ProfileShareImporter.parseAndValidate(text)
        assertTrue(result is ProfileShareParseResult.Error)
        assertEquals(ProfileShareErrorCode.MALFORMED, (result as ProfileShareParseResult.Error).code)
    }

    private fun parseOk(raw: String): ProfileShareEnvelopeDto {
        val result = ProfileShareImporter.parseAndValidate(raw)
        assertTrue("expected valid envelope, got $result", result is ProfileShareParseResult.Success)
        return (result as ProfileShareParseResult.Success).envelope
    }
}
