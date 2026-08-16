package com.clawmate.app.settings

import com.clawmate.app.settings.domain.SettingsNormalizers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key / default normalization determinism (mirrors HarmonyOS normalizers). */
class SettingsNormalizersTest {

    @Test
    fun `url normalization trims and strips trailing slashes`() {
        assertEquals("http://planner:9003", SettingsNormalizers.normalizeUrl("  http://planner:9003/// "))
        assertEquals("", SettingsNormalizers.normalizeUrl("   //// "))
        assertEquals("https://a.com/v1", SettingsNormalizers.normalizeUrl("https://a.com/v1/"))
    }

    @Test
    fun `model normalization trims but keeps empty`() {
        assertEquals("gpt-4o", SettingsNormalizers.normalizeModel("  gpt-4o  "))
        assertEquals("", SettingsNormalizers.normalizeModel("   "))
    }

    @Test
    fun `compression threshold coerces into the 50 to 95 range`() {
        assertEquals(85, SettingsNormalizers.compressionThresholdPercent("ab"))
        assertEquals(50, SettingsNormalizers.compressionThresholdPercent("10"))
        assertEquals(95, SettingsNormalizers.compressionThresholdPercent("999"))
        assertEquals(60, SettingsNormalizers.compressionThresholdPercent("60"))
    }

    @Test
    fun `recent rounds coerces into the 2 to 20 range`() {
        assertEquals(8, SettingsNormalizers.recentRounds("junk"))
        assertEquals(2, SettingsNormalizers.recentRounds("1"))
        assertEquals(20, SettingsNormalizers.recentRounds("200"))
        assertEquals(6, SettingsNormalizers.recentRounds("6"))
    }

    @Test
    fun `concurrency is positive and bounded`() {
        // '0' clamps up to the minimum of 1.
        assertEquals(1, SettingsNormalizers.concurrency("0", 3))
        assertEquals(16, SettingsNormalizers.concurrency("999", 3))
        assertEquals(5, SettingsNormalizers.concurrency("5", 3))
        assertEquals(3, SettingsNormalizers.concurrency("x", 3))
    }

    @Test
    fun `boolean parse is safe with fallback`() {
        assertTrue(SettingsNormalizers.boolean("true", false))
        assertFalse(SettingsNormalizers.boolean("false", true))
        assertTrue(SettingsNormalizers.boolean("garbage", true))
        assertTrue(SettingsNormalizers.boolean("TRUE", false))
    }

    @Test
    fun `embedding mode maps to enum with safe local default`() {
        assertEquals(
            com.clawmate.app.settings.domain.EmbeddingMode.Remote,
            SettingsNormalizers.embeddingMode("remote"),
        )
        assertEquals(
            com.clawmate.app.settings.domain.EmbeddingMode.HuaweiLocal,
            SettingsNormalizers.embeddingMode("huawei_local"),
        )
        assertEquals(
            com.clawmate.app.settings.domain.EmbeddingMode.HuaweiLocal,
            SettingsNormalizers.embeddingMode("unexpected"),
        )
    }

    @Test
    fun `double normalization is bounded`() {
        assertEquals(0.5, SettingsNormalizers.normalizedDouble("0.5", 0.9, 0.1, 1.0), 1e-9)
        assertEquals(0.1, SettingsNormalizers.normalizedDouble("-1", 0.9, 0.1, 1.0), 1e-9)
        assertEquals(1.0, SettingsNormalizers.normalizedDouble("99", 0.9, 0.1, 1.0), 1e-9)
        assertEquals(0.9, SettingsNormalizers.normalizedDouble("nope", 0.9, 0.1, 1.0), 1e-9)
    }
}
