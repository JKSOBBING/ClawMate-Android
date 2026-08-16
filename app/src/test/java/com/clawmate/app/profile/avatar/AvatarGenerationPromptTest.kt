package com.clawmate.app.profile.avatar

import com.clawmate.app.profile.avatar.domain.AvatarRunState
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.avatarRunStateLabel
import com.clawmate.app.profile.avatar.domain.buildAvatarGenerationPrompt
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the bounded prompt builder and the honest run-state
 * mapping used by the avatar generation UI (migration row A2).
 */
class AvatarGenerationPromptTest {

    @Test
    fun `built prompt is bounded and always non-empty`() {
        val prompt = buildAvatarGenerationPrompt(LocalAvatarProfile.empty)
        assertTrue(prompt.text.isNotEmpty())
        assertTrue(prompt.text.length <= com.clawmate.app.profile.avatar.domain.AvatarGenerationPrompt.MAX_PROMPT_LENGTH)
        assertTrue(prompt.consented)
        assertEquals(com.clawmate.app.profile.avatar.domain.AvatarGenerationMode.TEXT_TO_IMAGE, prompt.mode)
    }

    @Test
    fun `built prompt includes local profile content`() {
        val profile = LocalAvatarProfile(
            displayName = "小林",
            gender = "男",
            age = "28",
            domains = defaultProfileDomains().mapIndexed { i, d ->
                if (i == 0) d.copy(shortSummary = "爱探店") else d
            },
        )
        val prompt = buildAvatarGenerationPrompt(profile)
        assertTrue(prompt.text.contains("小林"))
        assertTrue(prompt.text.contains("male"))
        assertTrue(prompt.text.contains("年龄: 28"))
        assertTrue(prompt.text.contains("爱探店"))
    }

    @Test
    fun `oversized profiles are truncated to the hard cap`() {
        val hugeShortSummary = "大".repeat(10_000)
        val profile = LocalAvatarProfile(
            displayName = "名",
            domains = defaultProfileDomains().map { it.copy(shortSummary = hugeShortSummary) },
        )
        val prompt = buildAvatarGenerationPrompt(profile)
        assertTrue(prompt.text.length <= com.clawmate.app.profile.avatar.domain.AvatarGenerationPrompt.MAX_PROMPT_LENGTH)
    }

    @Test
    fun `run state mapping is stable and honest`() {
        assertEquals("生成中…", com.clawmate.app.profile.avatar.domain.AvatarRunState.RUNNING.label)
        assertEquals(AvatarRunState.RUNNING, AvatarRunState.fromToken("RUNNING"))
        assertEquals(AvatarRunState.IDLE, AvatarRunState.fromToken("bogus"))
        assertTrue(avatarRunStateLabel(AvatarRunState.ERROR).isNotEmpty())
        assertEquals("", avatarRunStateLabel(AvatarRunState.IDLE))
    }
}
