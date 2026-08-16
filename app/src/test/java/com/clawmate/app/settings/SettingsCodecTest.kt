package com.clawmate.app.settings

import com.clawmate.app.settings.data.SettingsCodec
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.AutoConfirmMode
import com.clawmate.app.settings.domain.EmbeddingMode
import com.clawmate.app.settings.domain.SettingsKeys
import com.clawmate.app.settings.domain.SensitiveValueUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence round-trips: encode/decode and repository save/snapshot must be
 * deterministic and must never persist a masked secret verbatim.
 */
class SettingsCodecTest {

    @Test
    fun `encode then decode is a deterministic round trip`() {
        val original = AppSettings.defaults().copy(
            cloudPlannerBaseUrl = " https://planner.test/ ",
            cloudPlannerApiKey = "sk-secret",
            cloudPlannerModel = " planner-model ",
            galleryEmbeddingMode = EmbeddingMode.Remote,
            agentLocalConfirm = AutoConfirmMode.AutoApprove,
            gallerySkipOcr = true,
            chatCompressionThresholdPercent = 70,
            chatRecentRounds = 12,
        )
        val flat = SettingsCodec.encode(original)
        val decoded = SettingsCodec.decode(flat)
        assertEquals("https://planner.test", decoded.cloudPlannerBaseUrl)
        assertEquals("sk-secret", decoded.cloudPlannerApiKey)
        assertEquals("planner-model", decoded.cloudPlannerModel)
        assertEquals(EmbeddingMode.Remote, decoded.galleryEmbeddingMode)
        assertEquals(AutoConfirmMode.AutoApprove, decoded.agentLocalConfirm)
        assertTrue(decoded.gallerySkipOcr)
        assertEquals(70, decoded.chatCompressionThresholdPercent)
        assertEquals(12, decoded.chatRecentRounds)
    }

    @Test
    fun `decode of empty map yields safe empty defaults`() {
        val decoded = SettingsCodec.decode(emptyMap())
        assertEquals(AppSettings.defaults(), decoded)
        assertEquals("", decoded.cloudPlannerBaseUrl)
        assertEquals("", decoded.cloudPlannerApiKey)
        assertTrue(decoded.workflowAutomationEnabled)
        assertEquals(85, decoded.chatCompressionThresholdPercent)
    }

    @Test
    fun `accessibility opt-in round trips and defaults to false`() {
        // Phase 19 — the AutoAgent opt-in is persisted and decodes deterministically.
        assertFalse(AppSettings.defaults().accessibilityOptIn)
        val flat = SettingsCodec.encode(AppSettings.defaults().copy(accessibilityOptIn = true))
        assertEquals("true", flat[SettingsKeys.ACCESSIBILITY_OPT_IN])
        assertTrue(SettingsCodec.decode(emptyMap()).accessibilityOptIn ==
            false && SettingsCodec.decode(flat).accessibilityOptIn)

        // Corrupt value degrades to the safe off default.
        assertFalse(
            SettingsCodec.decode(
                mapOf(SettingsKeys.ACCESSIBILITY_OPT_IN to "not-a-bool"),
            ).accessibilityOptIn,
        )
    }

    @Test
    fun `overlay opt-in round trips and defaults to false`() {
        // Phase 20 — the float/edge overlay opt-in is persisted and decodes deterministically.
        assertFalse(AppSettings.defaults().overlayOptIn)
        val flat = SettingsCodec.encode(AppSettings.defaults().copy(overlayOptIn = true))
        assertEquals("true", flat[SettingsKeys.OVERLAY_OPT_IN])
        assertTrue(SettingsCodec.decode(emptyMap()).overlayOptIn ==
            false && SettingsCodec.decode(flat).overlayOptIn)

        // Corrupt value degrades to the safe off default.
        assertFalse(
            SettingsCodec.decode(
                mapOf(SettingsKeys.OVERLAY_OPT_IN to "not-a-bool"),
            ).overlayOptIn,
        )
    }

    @Test
    fun `gallery AI summary opt-in round trips and defaults to false`() {
        // Phase 21 — the cloud AI month-summary opt-in is persisted and decodes deterministically.
        assertFalse(AppSettings.defaults().galleryAiSummaryEnabled)
        val flat = SettingsCodec.encode(AppSettings.defaults().copy(galleryAiSummaryEnabled = true))
        assertEquals("true", flat[SettingsKeys.GALLERY_AI_SUMMARY_ENABLED])
        assertTrue(SettingsCodec.decode(emptyMap()).galleryAiSummaryEnabled ==
            false && SettingsCodec.decode(flat).galleryAiSummaryEnabled)

        // Corrupt value degrades to the safe off default.
        assertFalse(
            SettingsCodec.decode(
                mapOf(SettingsKeys.GALLERY_AI_SUMMARY_ENABLED to "not-a-bool"),
            ).galleryAiSummaryEnabled,
        )
    }

    @Test
    fun `decode degrades corrupt values instead of throwing`() {
        val corrupt: Map<String, String> = SettingsKeys.ALL_KEYS.associateWith { "not-a-number-or-bool" } +
            mapOf(
                SettingsKeys.CLOUD_PLANNER_BASE_URL to "https://planner.test",
                SettingsKeys.CHAT_COMPRESSION_THRESHOLD_PERCENT to "999",
            )
        // total function: never throws
        val decoded = SettingsCodec.decode(corrupt)
        assertEquals("https://planner.test", decoded.cloudPlannerBaseUrl)
        assertEquals(95, decoded.chatCompressionThresholdPercent) // clamped
        // String fields are kept verbatim (unless masked); corrupt numerics clamp.
        assertEquals("not-a-number-or-bool", decoded.cloudPlannerApiKey)
    }

    @Test
    fun `sanitizeForWrite drops masked secrets before persistence`() {
        val flat = mapOf(
            SettingsKeys.CLOUD_PLANNER_API_KEY to SensitiveValueUtils.maskSensitiveValue("sk-masked"),
            SettingsKeys.CLOUD_PLANNER_BASE_URL to "https://planner.test",
        )
        val clean = SettingsCodec.sanitizeForWrite(flat)
        // The masked secret must not survive as a literal value.
        assertNull("masked key should be removed", clean[SettingsKeys.CLOUD_PLANNER_API_KEY])
        assertEquals("https://planner.test", clean[SettingsKeys.CLOUD_PLANNER_BASE_URL])
    }
}
