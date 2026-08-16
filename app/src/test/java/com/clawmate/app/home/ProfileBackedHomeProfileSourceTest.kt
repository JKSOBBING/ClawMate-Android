package com.clawmate.app.home

import com.clawmate.app.home.data.ProfileBackedHomeProfileSource
import com.clawmate.app.profile.avatar.domain.PROFILE_DOMAIN_NO_DATA
import com.clawmate.app.profile.avatar.data.FakeProfileAvatarRepository
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic, device-free tests for the real [ProfileBackedHomeProfileSource]
 * that closes the A1 gap — the persisted local profile becomes the Home
 * recommendation pipeline's backing source. Uses the in-memory fake repository
 * so no Android device is required.
 */
class ProfileBackedHomeProfileSourceTest {

    @Test
    fun `empty profile loads as the honest empty home profile`() = runTest {
        val source = ProfileBackedHomeProfileSource(FakeProfileAvatarRepository(LocalAvatarProfile.empty))
        val vm = source.load()
        assertTrue(vm.empty)
        assertEquals(0, vm.completeness)
        assertTrue(vm.domains.all { it.desc == PROFILE_DOMAIN_NO_DATA })
        assertTrue(vm.recommendations.isEmpty())
        assertEquals("暂无画像", vm.updatedLabel)
    }

    @Test
    fun `populated profile loads real pillars and completeness`() = runTest {
        val repo = FakeProfileAvatarRepository(
            LocalAvatarProfile(
                displayName = "小林",
                domains = defaultProfileDomains().mapIndexed { i, d ->
                    if (i == 2) d.copy(shortSummary = "整租一居", summary = "喜欢明亮安静") else d
                },
                updatedAt = "2026-08-17T09:00:00",
            ),
        )
        val vm = ProfileBackedHomeProfileSource(repo).load()
        assertFalse(vm.empty)
        val live = vm.domains.first { it.domain == "live" }
        assertEquals("整租一居", live.desc)
        assertEquals("喜欢明亮安静", live.fullDesc)
        // 1 of a possible filled profile signals → index-derived count for populated pillar.
        assertEquals(25, vm.completeness)
        assertEquals(vm.updatedLabel, "2026-08-17T09:00:00")
    }

    @Test
    fun `recommendations stay empty and avatar stays not-configured`() = runTest {
        val repo = FakeProfileAvatarRepository(
            LocalAvatarProfile(domains = defaultProfileDomains().map { it.copy(shortSummary = "有内容") }),
        )
        val vm = ProfileBackedHomeProfileSource(repo).load()
        // Honest boundaries: no indexed-todo source, no avatar engine.
        assertTrue(vm.recommendations.isEmpty())
        assertFalse(vm.avatar.hasAvatarImage)
    }

    @Test
    fun `updated label falls back to the no-image sentinel when blank`() = runTest {
        val vm = ProfileBackedHomeProfileSource(
            FakeProfileAvatarRepository(LocalAvatarProfile(displayName = "X", updatedAt = "  ")),
        ).load()
        assertEquals("暂无画像", vm.updatedLabel)
    }
}
