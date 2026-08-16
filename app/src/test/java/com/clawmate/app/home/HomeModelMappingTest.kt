package com.clawmate.app.home

import com.clawmate.app.home.domain.AvatarSection
import com.clawmate.app.home.domain.AvatarStatus
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.defaultHomeDomains
import com.clawmate.app.home.domain.homeProfileCompleteness
import com.clawmate.app.home.domain.recommendationColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stable model mapping and honest empty/disabled-state guarantees.
 */
class HomeModelMappingTest {

    @Test
    fun `avatar not-configured is disabled with an honest label`() {
        val avatar = AvatarSection.notConfigured
        assertEquals(AvatarStatus.DISABLED, avatar.status)
        assertFalse(avatar.hasAvatarImage)
        assertTrue(avatar.imageUri.isBlank())
        assertEquals("未配置数字分身", avatar.statusLabel)
        assertEquals("未启用", avatar.actionLabel)
    }

    @Test
    fun `avatar disabled never implies a fabricated image`() {
        // A disabled avatar must not claim an image URI or a success label.
        val avatar = AvatarSection.notConfigured
        assertEquals("", avatar.imageUri)
        assertTrue(!avatar.updatedLabel.contains("已更新"))
        assertTrue(!avatar.statusLabel.contains("已更新"))
    }

    @Test
    fun `avatar status token parsing is deterministic`() {
        assertEquals(AvatarStatus.EMPTY, AvatarStatus.fromToken(""))
        assertEquals(AvatarStatus.CURRENT, AvatarStatus.fromToken("current"))
        assertEquals(AvatarStatus.STALE, AvatarStatus.fromToken("stale"))
        assertEquals(AvatarStatus.GENERATING, AvatarStatus.fromToken("generating"))
        assertEquals(AvatarStatus.FAILED, AvatarStatus.fromToken("failed"))
        assertEquals(AvatarStatus.EMPTY, AvatarStatus.fromToken("bogus"))
        assertEquals("current", AvatarStatus.of(AvatarStatus.CURRENT))
        assertEquals("disabled", AvatarStatus.of(AvatarStatus.DISABLED))
    }

    @Test
    fun `four default pillars are honest empty`() {
        val pillars = defaultHomeDomains()
        assertEquals(4, pillars.size)
        assertEquals(listOf("eat", "wear", "live", "travel"), pillars.map { it.domain })
        assertTrue(pillars.all { it.desc == "暂无数据" && it.fullDesc == "暂无数据" && it.claims.isEmpty() && it.eventCount == 0 })
        assertEquals("吃", pillars[0].title)
        assertEquals("#39C2A2", pillars[0].color)
    }

    @Test
    fun `empty profile completeness is zero`() {
        assertEquals(0, homeProfileCompleteness(defaultHomeDomains()))
    }

    @Test
    fun `completeness counts only populated domains`() {
        val pillars = defaultHomeDomains()
        val populated = pillars.mapIndexed { i, p ->
            if (i == 0) p.copy(desc = "常吃火锅") else p
        }
        assertEquals(25, homeProfileCompleteness(populated))
    }

    @Test
    fun `recommendation color mapping is stable with fallback`() {
        assertEquals("#39C2A2", recommendationColor("eat"))
        assertEquals("#7669FF", recommendationColor("wear"))
        assertEquals("#FF963D", recommendationColor("live"))
        assertEquals("#4B9BFF", recommendationColor("travel"))
        assertEquals("#E35D6A", recommendationColor("work"))
        assertEquals("#8B93A4", recommendationColor("other"))
        assertEquals("#8066FF", recommendationColor("unknown"))
        // Case-insensitive on input.
        assertEquals("#39C2A2", recommendationColor("EAT"))
    }

    @Test
    fun `empty profile model holds no total events or claims`() {
        val empty = HomeProfileViewModel.empty
        assertTrue(empty.empty)
        assertEquals(0, empty.totalEvents)
        assertEquals(0, empty.completeness)
        assertEquals("暂无画像", empty.updatedLabel)
        assertEquals(listOf("eat", "wear", "live", "travel"), empty.domains.map { it.domain })
    }
}
