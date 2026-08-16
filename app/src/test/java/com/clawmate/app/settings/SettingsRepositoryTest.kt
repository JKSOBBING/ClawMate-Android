package com.clawmate.app.settings

import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.AutoConfirmMode
import com.clawmate.app.settings.domain.SensitiveValueUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository persistence round-trips using the deterministic in-memory store
 * that applies the same codec/sanitization as the DataStore-backed repository.
 */
class SettingsRepositoryTest {

    @Test
    fun `save then snapshot round-trips normalized values`() = runBlocking {
        val repo = FakeSettingsRepository()
        val toSave = AppSettings.defaults().copy(
            cloudPlannerBaseUrl = " https://planner.test/ ",
            cloudPlannerApiKey = "sk-abc",
            chatCompressionThresholdPercent = 999, // will clamp to 95 on write
        )
        repo.save(toSave)
        val snap = repo.snapshot()
        assertEquals("https://planner.test", snap.cloudPlannerBaseUrl)
        assertEquals("sk-abc", snap.cloudPlannerApiKey)
        assertEquals(95, snap.chatCompressionThresholdPercent)
    }

    @Test
    fun `flow emits the saved snapshot`() = runBlocking {
        val repo = FakeSettingsRepository()
        repo.save(AppSettings.defaults().copy(agentCloudConfirm = AutoConfirmMode.AutoApprove))
        val emitted = repo.settings.first()
        assertEquals(AutoConfirmMode.AutoApprove, emitted.agentCloudConfirm)
    }

    @Test
    fun `saving a masked secret does not persist it`() = runBlocking {
        val repo = FakeSettingsRepository()
        val masked = SensitiveValueUtils.maskSensitiveValue("topsecret")
        repo.save(
            AppSettings.defaults().copy(
                cloudPlannerApiKey = masked, // stale display mask
                cloudDeciderBaseUrl = "https://decider.test",
            ),
        )
        val snap = repo.snapshot()
        // The masked string must not be stored as a literal secret.
        assertEquals("", snap.cloudPlannerApiKey)
        assertEquals("https://decider.test", snap.cloudDeciderBaseUrl)
    }

    @Test
    fun `defaults are safe and unconfigured`() = runBlocking {
        val default = FakeSettingsRepository().snapshot()
        assertFalse("planner should not claim configured", default.cloudPlannerConfigured)
        assertFalse("decider should not claim configured", default.cloudDeciderConfigured)
        assertFalse("HDC should not claim configured", default.hdcBridgeConfigured)
        assertFalse("gallery vision should not claim configured", default.galleryVisionConfigured)
        assertTrue(default.cloudPlannerApiKey.isBlank())
        assertTrue(default.cloudPlannerBaseUrl.isBlank())
    }

    @Test
    fun `connectivity flags never persist true without a real endpoint`() = runBlocking {
        val repo = FakeSettingsRepository()
        repo.save(AppSettings.defaults().copy(hdcConnected = true, pcConnected = true))
        val snap = repo.snapshot()
        // Connectivity is derived, not stored; saved flags are ignored by design.
        assertFalse(snap.hdcConnected)
        assertFalse(snap.pcConnected)
    }
}
