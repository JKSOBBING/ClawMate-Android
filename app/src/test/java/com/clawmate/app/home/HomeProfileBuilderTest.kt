package com.clawmate.app.home

import com.clawmate.app.home.domain.HomeProfileBuilder
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.profile.avatar.domain.PROFILE_DOMAIN_NO_DATA
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests closing the Phase-12 A1 gap: the pure mapping from the
 * persisted local profile into the Home pillars / completeness / honest empty
 * state. All device-free, no network, no fabricated data.
 */
class HomeProfileBuilderTest {

    private fun domain(domain: String, short: String = "", summary: String = "", claims: List<String> = emptyList()) =
        AvatarDomainSnapshot(
            domain = domain,
            displayName = domain,
            shortSummary = short,
            summary = summary,
            claims = claims,
        )

    @Test
    fun `empty profile yields the backward-compatible empty home profile`() {
        val result = HomeProfileBuilder.build(LocalAvatarProfile.empty, updatedLabel = "暂无画像")
        assertTrue(result.empty)
        assertEquals(0, result.completeness)
        assertEquals(0, result.totalEvents)
        assertEquals(listOf("eat", "wear", "live", "travel"), result.domains.map { it.domain })
        assertTrue(result.domains.all { it.desc == PROFILE_DOMAIN_NO_DATA })
        assertTrue(result.recommendations.isEmpty())
        // Matches the Phase-1..15 honest empty profile (no data invented).
        assertEquals(HomeProfileViewModel.empty.domains, result.domains)
        assertEquals(HomeProfileViewModel.empty.completeness, result.completeness)
    }

    @Test
    fun `filled short summary flows into the matching pillar desc`() {
        val profile = LocalAvatarProfile(
            displayName = "小林",
            domains = defaultProfileDomains().map { if (it.domain == "eat") domain("eat", short = "爱探店") else it },
        )
        val result = HomeProfileBuilder.build(profile, updatedLabel = "今天 09:00")
        assertFalse(result.empty)
        assertEquals("爱探店", result.domains.first { it.domain == "eat" }.desc)
        assertEquals(PROFILE_DOMAIN_NO_DATA, result.domains.first { it.domain == "wear" }.desc)
    }

    @Test
    fun `summary without short summary reads the view-image fallback`() {
        val profile = LocalAvatarProfile(domains = defaultProfileDomains().map {
            if (it.domain == "travel") domain("travel", summary = "常去江浙出差") else it
        })
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        val travel = result.domains.first { it.domain == "travel" }
        assertEquals("查看画像", travel.desc)
        assertEquals("常去江浙出差", travel.fullDesc)
    }

    @Test
    fun `claims are extracted capped at five`() {
        val many = (1..8).map { "陈述$it" }
        val profile = LocalAvatarProfile(domains = defaultProfileDomains().map {
            if (it.domain == "live") domain("live", short = "整租一居", claims = many) else it
        })
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        val live = result.domains.first { it.domain == "live" }
        assertEquals(listOf("陈述1", "陈述2", "陈述3", "陈述4", "陈述5"), live.claims)
    }

    @Test
    fun `blank and whitespace claims are ignored`() {
        val profile = LocalAvatarProfile(domains = defaultProfileDomains().map {
            if (it.domain == "wear") domain("wear", short = "通勤休闲", claims = listOf("  ", "喜欢深色系", "")) else it
        })
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        assertEquals(listOf("喜欢深色系"), result.domains.first { it.domain == "wear" }.claims)
    }

    @Test
    fun `completeness counts only populated pillars`() {
        val profile = LocalAvatarProfile(domains = defaultProfileDomains().map { it }.let { base ->
            base.map { if (it.domain == "eat" || it.domain == "travel") it.copy(shortSummary = "有画像") else it }
        })
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        // 2 of 4 pillars populated → 50%.
        assertEquals(50, result.completeness)
        assertEquals(100, HomeProfileBuilder.build(
            LocalAvatarProfile(domains = defaultProfileDomains().map { it.copy(shortSummary = "x") }),
            updatedLabel = "",
        ).completeness)
    }

    @Test
    fun `total events is the real sum of stored domain event counts`() {
        val profile = LocalAvatarProfile(domains = listOf(
            domain("eat", short = "爱吃", summary = "").copy(eventCount = 3),
            domain("wear", short = "").copy(eventCount = 0),
            domain("live", short = "住").copy(eventCount = 7),
            domain("travel", short = ""),
        ))
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        assertEquals(10, result.totalEvents)
    }

    @Test
    fun `pillars map to their stable fixed domain metadata`() {
        val profile = LocalAvatarProfile(domains = defaultProfileDomains().map { it.copy(shortSummary = "有内容") })
        val result = HomeProfileBuilder.build(profile, updatedLabel = "")
        val eat = result.domains.first { it.domain == "eat" }
        assertEquals("吃", eat.title)
        assertEquals("Eat", eat.icon)
        assertEquals("#39C2A2", eat.color)
        val travel = result.domains.first { it.domain == "travel" }
        assertEquals("行", travel.title)
        assertEquals("Go", travel.icon)
        assertEquals("#4B9BFF", travel.color)
    }
}
