package com.clawmate.app.profile.avatar

import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.AvatarGenerationGate
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.AvatarProviderSettings
import com.clawmate.app.profile.avatar.domain.AvatarStatusToken
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.avatarErrorMessage
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for the Phase-12 Profile / Avatar local UI + state:
 * the typed avatar domain, the honest generation gate (never fabricates a
 * generated avatar) and the local-profile completeness reducer. All pure, no
 * device/network.
 */
class AvatarDomainTest {

    @Test
    fun `empty profile completeness is zero`() {
        assertEquals(0, localProfileCompleteness(LocalAvatarProfile.empty))
    }

    @Test
    fun `default profile has exactly the four persona domains`() {
        val domains = defaultProfileDomains()
        assertEquals(4, domains.size)
        assertEquals(listOf("eat", "wear", "live", "travel"), domains.map { it.domain })
        assertTrue(domains.all { it.shortSummary.isBlank() })
    }

    @Test
    fun `filling a single domain short summary moves completeness by one fifth`() {
        val profile = LocalAvatarProfile.empty.copy(
            domains = defaultProfileDomains().mapIndexed { i, d ->
                if (i == 0) d.copy(shortSummary = "爱探店") else d
            },
        )
        // 4 domains + display name = 5 signals; 1 filled → 20%.
        assertEquals(20, localProfileCompleteness(profile))
    }

    @Test
    fun `filling display name alone is twenty percent`() {
        val profile = LocalAvatarProfile.empty.copy(displayName = "小林")
        assertEquals(20, localProfileCompleteness(profile))
    }

    @Test
    fun `filling every signal reaches one hundred percent`() {
        val profile = LocalAvatarProfile(
            displayName = "小林",
            gender = "男",
            age = "28",
            shortSummary = "喜欢摄影",
            domains = defaultProfileDomains().map { it.copy(shortSummary = "有内容") },
        )
        assertEquals(100, localProfileCompleteness(profile))
    }

    @Test
    fun `blank whitespace does not count as filled`() {
        val profile = LocalAvatarProfile.empty.copy(displayName = "   ")
        assertEquals(0, localProfileCompleteness(profile))
    }

    @Test
    fun `generation gate reports ready only when every required input is present`() {
        // With a real provider wired (migration row A2), configured base+key+model+size
        // is honestly READY — not a disabled boundary.
        assertEquals(
            AvatarGenerationState.READY,
            AvatarGenerationGate.state(AvatarProviderSettings(
                imageBaseUrl = "https://img.example.com", apiKey = "sk-xyz",
                textToImageModel = "seedream-3", imageSize = "2K",
            )),
        )
        assertEquals(
            AvatarGenerationState.PARTIALLY_CONFIGURED,
            AvatarGenerationGate.state(AvatarProviderSettings(
                imageBaseUrl = "https://img.example.com", apiKey = "sk-xyz",
                textToImageModel = "seedream-3", imageSize = "",
            )),
        )
        assertEquals(
            AvatarGenerationState.PARTIALLY_CONFIGURED,
            AvatarGenerationGate.state(AvatarProviderSettings(
                imageBaseUrl = "https://img.example.com", apiKey = "sk-xyz",
                textToImageModel = "", imageSize = "2K",
            )),
        )
        // Whitespace-only credentials are still NOT_CONFIGURED — never a fake.
        assertEquals(
            AvatarGenerationState.NOT_CONFIGURED,
            AvatarGenerationGate.state(AvatarProviderSettings(imageBaseUrl = "  ", apiKey = "  ")),
        )
        // Invalid (non-http) URL is not READY even with key+model+size.
        assertEquals(
            AvatarGenerationState.NOT_CONFIGURED,
            AvatarGenerationGate.state(AvatarProviderSettings(
                imageBaseUrl = "", apiKey = "sk", textToImageModel = "m", imageSize = "2K",
            )),
        )
        // canGenerate only ever permits READY (the pre-call gate).
        assertTrue(AvatarGenerationGate.canGenerate(AvatarGenerationState.READY))
        assertFalse(AvatarGenerationGate.canGenerate(AvatarGenerationState.NOT_CONFIGURED))
        assertFalse(AvatarGenerationGate.canGenerate(AvatarGenerationState.PARTIALLY_CONFIGURED))
    }

    @Test
    fun `gate guidance never leaks the api key and never implies a success`() {
        val secret = "sk-super-secret-value"
        val ready =
            AvatarGenerationGate.guidance(AvatarGenerationGate.state(AvatarProviderSettings(
                imageBaseUrl = "https://img.example.com", apiKey = secret,
                textToImageModel = "m", imageSize = "2K",
            )), hasCurrentProfile = true)
        val notConfigured = AvatarGenerationGate.guidance(AvatarGenerationState.NOT_CONFIGURED, hasCurrentProfile = false)
        // The literal key must never appear in user-facing guidance.
        assertTrue("API key leaked into guidance", secret !in ready)
        assertTrue(ready.isNotEmpty())
        assertFalse(ready.contains("sk-"))
        assertTrue(notConfigured.isNotEmpty())
    }

    @Test
    fun `avatar status token parsing is stable`() {
        assertEquals(AvatarStatusToken.CURRENT, AvatarStatusToken.fromToken("current"))
        assertEquals(AvatarStatusToken.EMPTY, AvatarStatusToken.fromToken("unknown"))
        assertEquals(AvatarStatusToken.EMPTY, AvatarStatusToken.fromToken(""))
    }

    @Test
    fun `avatarErrorMessage collapses blank to fallback and normalizes whitespace`() {
        assertEquals("fallback", avatarErrorMessage(null, "fallback"))
        assertEquals("fallback", avatarErrorMessage("", "fallback"))
        assertEquals("fallback", avatarErrorMessage("   ", "fallback"))
        val result = avatarErrorMessage("line1  \n   line2", "fallback")
        assertTrue(result.contains("line1") && result.contains("line2"))
    }

    @Test
    fun `domain snapshots keep identity and event count`() {
        val d = AvatarDomainSnapshot(domain = "eat", displayName = "吃", shortSummary = "爱探店", summary = "探店", eventCount = 5)
        assertEquals("eat", d.domain)
        assertEquals(5, d.eventCount)
    }
}
