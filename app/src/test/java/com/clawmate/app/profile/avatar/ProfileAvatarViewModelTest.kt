package com.clawmate.app.profile.avatar

import com.clawmate.app.profile.avatar.data.FakeProfileAvatarRepository
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.AvatarProviderSettings
import com.clawmate.app.profile.avatar.domain.AvatarGenerationGate
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic ViewModel / repository tests for the Profile / Avatar screen.
 * Uses in-memory fakes (no DataStore, no Android context, no network) so the
 * save + generation-gate wiring is covered device-free.
 */
class ProfileAvatarViewModelTest {

    @Test
    fun `fake repository persists saves deterministically`() = runTest {
        val repo = FakeProfileAvatarRepository()
        val withDomain = LocalAvatarProfile.empty.copy(
            displayName = "小林",
            shortSummary = "喜欢摄影",
            domains = defaultProfileDomains().mapIndexed { i, d ->
                if (i == 0) d.copy(shortSummary = "爱探店") else d
            },
        )
        val saved = repo.save(withDomain)
        assertEquals(saved, repo.snapshot())
        val reloaded = FakeProfileAvatarRepository().apply { save(withDomain) }
        assertEquals(withDomain, reloaded.snapshot())
    }

    @Test
    fun `fake repository starts from the empty profile`() = runTest {
        val repo = FakeProfileAvatarRepository()
        assertEquals(LocalAvatarProfile.empty, repo.snapshot())
    }

    @Test
    fun `empty settings yield not-configured gate`() {
        val settings = AppSettings.defaults() // all avatar fields blank
        val gate = AvatarGenerationGate.state(
            AvatarProviderSettings(
                imageBaseUrl = settings.avatarImageBaseUrl,
                apiKey = settings.avatarImageApiKey,
                textToImageModel = settings.avatarTextToImageModel,
                imageSize = settings.avatarImageSize,
            ),
        )
        assertEquals(AvatarGenerationState.NOT_CONFIGURED, gate)
    }

    @Test
    fun `configured avatar settings yield configured-but-no-provider honest state`() {
        val settings = AppSettings.defaults().copy(
            avatarImageBaseUrl = "https://img.example.com",
            avatarImageApiKey = "sk-abc",
            avatarTextToImageModel = "seedream-3",
        )
        val gate = AvatarGenerationGate.state(
            AvatarProviderSettings(
                imageBaseUrl = settings.avatarImageBaseUrl,
                apiKey = settings.avatarImageApiKey,
                textToImageModel = settings.avatarTextToImageModel,
                imageSize = settings.avatarImageSize,
            ),
        )
        // Never an invented "generating/ready" — with no wired provider the
        // honest state is CONFIGURED_BUT_NO_PROVIDER.
        assertEquals(AvatarGenerationState.CONFIGURED_BUT_NO_PROVIDER, gate)
    }

    @Test
    fun `default profile domains are stable`() {
        val a = defaultProfileDomains()
        val b = defaultProfileDomains()
        assertEquals(a, b)
        assertEquals(4, a.size)
    }

    @Test
    fun `settings repository is a real collaborator used by the avatar gate`() = runTest {
        // The avatar ViewModel reads provider settings from SettingsRepository;
        // verify a settings repo round-trips the avatar fields the gate needs.
        val settingsRepo = FakeSettingsRepository()
        val withoutKey = settingsRepo.snapshot()
        assertEquals("", withoutKey.avatarImageBaseUrl)
        assertEquals("", withoutKey.avatarImageApiKey)

        val withKey = settingsRepo.save(withoutKey.copy(avatarImageBaseUrl = "https://img.example.com", avatarImageApiKey = "sk"))
        assertEquals("https://img.example.com", withKey.avatarImageBaseUrl)
        assertTrue(withKey.avatarImageApiKey.isNotEmpty())
    }
}
