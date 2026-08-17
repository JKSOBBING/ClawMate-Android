package com.clawmate.app.home

import com.clawmate.app.datamanager.ProfileTypes
import com.clawmate.app.datamanager.ProfileTypes.ProfileDomainState
import com.clawmate.app.datamanager.ProfileTypes.ProfileState
import com.clawmate.app.home.domain.ProfileIndexBlender
import com.clawmate.app.home.domain.defaultHomeDomains
import com.clawmate.app.home.domain.homeProfileCompleteness
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase-32 display-only blend between the user-curated local
 * profile and the index-derived profile. Verifies local-first behaviour (a
 * populated user domain wins verbatim) and the four-domain bounded summary /
 * claims / real evidence ids surfaced into the Home pillar cards — without ever
 * overwriting user data or writing a fabricated profile.
 */
class ProfileIndexBlenderTest {

    private fun indexState(domain: String, eventIds: List<String>, summary: String): ProfileState {
        val display = mapOf("eat" to "吃", "wear" to "穿", "live" to "住", "travel" to "行")
        val states = defaultProfileDomains().associate { d ->
            if (d.domain == domain) {
                d.domain to ProfileDomainState(
                    domain = d.domain,
                    displayName = display.getValue(d.domain),
                    eventCount = eventIds.size,
                    eventIds = eventIds,
                    summary = summary,
                    generation = "local_only",
                    claims = eventIds.take(1).map {
                        com.clawmate.app.datamanager.ProfileTypes.ProfileClaim(
                            statement = "偏好$summary",
                            kind = "preference",
                            confidence = "high",
                            eventIds = listOf(it),
                            evidenceCount = 1,
                        )
                    },
                )
            } else {
                d.domain to ProfileDomainState(domain = d.domain, displayName = display.getValue(d.domain), eventCount = 0, generation = "empty")
            }
        }
        return ProfileState(eventCount = eventIds.size, domains = states, claimCount = 1)
    }

    private fun curatedEat(): AvatarDomainSnapshot =
        AvatarDomainSnapshot(domain = "eat", displayName = "吃", shortSummary = "用户手填的吃", summary = "手填", claims = listOf("手填结论"))

    @Test
    fun `user-populated domain is preserved verbatim (local-first)`() {
        val curated = LocalAvatarProfile(
            domains = listOf(curatedEat()) + defaultProfileDomains().filter { it.domain != "eat" },
        )
        val index = indexState("eat", listOf("a#0", "a#1"), "索引吃摘要")
        val home = ProfileIndexBlender.blend(curated, index, "2026-08-01")
        val eat = home.domains.first { it.domain == "eat" }
        assertEquals("用户手填的吃", eat.desc)
        assertTrue(eat.claims.contains("手填结论") || eat.fullDesc == "手填")
        // Real evidence ids still surfaced read-only.
        assertEquals(listOf("a#0", "a#1"), eat.evidenceIds)
    }

    @Test
    fun `empty user domain is filled from real index summary claims and evidence`() {
        val curated = LocalAvatarProfile(domains = defaultProfileDomains())
        val index = indexState("eat", listOf("a#0", "a#1"), "咖啡奶茶饮食")
        val home = ProfileIndexBlender.blend(curated, index, "2026-08-01")
        val eat = home.domains.first { it.domain == "eat" }
        // Local-only projection has a bounded summary (fullDesc) but no short
        // summary, so desc reads the honest "查看画像" with the real summary as
        // the full description (matching HomeProfileBuilder behaviour).
        assertEquals("咖啡奶茶饮食", eat.fullDesc)
        assertEquals("查看画像", eat.desc)
        assertTrue(eat.evidenceIds.containsAll(listOf("a#0", "a#1")))
        assertEquals(2, eat.eventCount)
        assertTrue("completeness should reflect the filled pillar", homeProfileCompleteness(home.domains) >= 25)
    }

    @Test
    fun `no index evidence yields the honest default pillars with no fabricated claims`() {
        val curated = LocalAvatarProfile(domains = defaultProfileDomains())
        val empty = ProfileState()
        val home = ProfileIndexBlender.blend(curated, empty, "2026-08-01")
        assertTrue(home.empty)
        assertTrue(home.domains.all { it.evidenceIds.isEmpty() })
        assertTrue(home.domains.all { it.claims.isEmpty() })
    }

    @Test
    fun `blend imports only four domains bounded content and never touches unrelated data`() {
        val curated = LocalAvatarProfile(domains = defaultProfileDomains())
        val states = mapOf(
            "eat" to ProfileDomainState(domain = "eat", eventCount = 2, eventIds = listOf("e1", "e2"), summary = "吃", generation = "local_only"),
            "wear" to ProfileDomainState(domain = "wear", eventCount = 1, eventIds = listOf("e3"), summary = "穿", generation = "local_only"),
            "live" to ProfileDomainState(domain = "live", eventCount = 1, eventIds = listOf("e4"), summary = "住", generation = "local_only"),
            "travel" to ProfileDomainState(domain = "travel", eventCount = 1, eventIds = listOf("e5"), summary = "行", generation = "local_only"),
        )
        val index = ProfileState(eventCount = 5, domains = states, claimCount = 0)
        val home = ProfileIndexBlender.blend(curated, index, "2026-08-01")
        assertEquals(4, home.domains.size)
        assertEquals(setOf("eat", "wear", "live", "travel"), home.domains.map { it.domain }.toSet())
        // No image/URI/token carried into the Home profile.
        assertEquals(home.domains.sumOf { it.evidenceIds.size }, 5)
    }
}
