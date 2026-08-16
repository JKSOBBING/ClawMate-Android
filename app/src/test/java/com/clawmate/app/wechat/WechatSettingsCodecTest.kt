package com.clawmate.app.wechat

import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.data.SettingsCodec
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.SettingsKeys
import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence round-trips for the ported WeChat auto-summary settings: encode /
 * decode and repository save/snapshot must normalize into the bounded ranges
 * and keep the values deterministic.
 */
class WechatSettingsCodecTest {

    @Test
    fun `encode then decode round-trips normalized WeChat settings`() {
        val original = AppSettings.defaults().copy(
            wechatAutoSummaryEnabled = true,
            wechatAutoSummaryDays = 14,
            wechatAutoSummaryMaxContacts = 20,
            wechatAutoSummarySwipeSpeed = 1000,
            wechatAutoSummaryHistorySwipeRatio = 0.6,
            wechatAutoSummaryStableSwipes = 5,
            wechatAutoSummaryMaxHistorySwipes = 120,
            wechatAutoSummaryWait = 2.0,
            wechatCollectionMode = WechatAutoSummaryModes.TARGET,
            wechatCollectionTargetContact = "  Alice  ",
        )
        val flat = SettingsCodec.encode(original)
        val decoded = SettingsCodec.decode(flat)
        assertTrue(decoded.wechatAutoSummaryEnabled)
        assertEquals(14, decoded.wechatAutoSummaryDays)
        assertEquals(20, decoded.wechatAutoSummaryMaxContacts)
        assertEquals(1000, decoded.wechatAutoSummarySwipeSpeed)
        assertEquals(0.6, decoded.wechatAutoSummaryHistorySwipeRatio, 1e-9)
        assertEquals(5, decoded.wechatAutoSummaryStableSwipes)
        assertEquals(120, decoded.wechatAutoSummaryMaxHistorySwipes)
        assertEquals(2.0, decoded.wechatAutoSummaryWait, 1e-9)
        assertEquals(WechatAutoSummaryModes.TARGET, decoded.wechatCollectionMode)
        assertEquals("Alice", decoded.wechatCollectionTargetContact)
    }

    @Test
    fun `decode of corrupt WeChat numerics clamps instead of throwing`() {
        val corrupt: Map<String, String> = mapOf(
            SettingsKeys.WECHAT_AUTO_SUMMARY_DAYS to "999",
            SettingsKeys.WECHAT_AUTO_SUMMARY_MAX_CONTACTS to "0",
            SettingsKeys.WECHAT_AUTO_SUMMARY_WAIT to "junk",
            SettingsKeys.WECHAT_COLLECTION_MODE to "mystery",
        )
        val decoded = SettingsCodec.decode(corrupt)
        assertEquals(90, decoded.wechatAutoSummaryDays)
        assertEquals(1, decoded.wechatAutoSummaryMaxContacts)
        assertEquals(1.0, decoded.wechatAutoSummaryWait, 1e-9)
        assertEquals(WechatAutoSummaryModes.RECENT, decoded.wechatCollectionMode)
    }

    @Test
    fun `empty map decodes to the safe WeChat defaults`() {
        val decoded = SettingsCodec.decode(emptyMap())
        assertEquals(AppSettings.defaults(), decoded)
        assertEquals(7, decoded.wechatAutoSummaryDays)
        assertEquals(10, decoded.wechatAutoSummaryMaxContacts)
        assertEquals(2000, decoded.wechatAutoSummarySwipeSpeed)
        assertEquals(0.4, decoded.wechatAutoSummaryHistorySwipeRatio, 1e-9)
        assertEquals(1.0, decoded.wechatAutoSummaryWait, 1e-9)
    }

    @Test
    fun `fake repository persists normalized values deterministically`() = runTest {
        val repo = FakeSettingsRepository(AppSettings.defaults())
        val saved = repo.save(
            AppSettings.defaults().copy(
                wechatAutoSummaryEnabled = true,
                wechatAutoSummaryDays = 45,
                wechatAutoSummaryMaxContacts = 30,
                wechatCollectionMode = WechatAutoSummaryModes.RECENT,
            ),
        )
        assertTrue(saved.wechatAutoSummaryEnabled)
        assertEquals(45, saved.wechatAutoSummaryDays)
        assertEquals(30, saved.wechatAutoSummaryMaxContacts)
        val snapshot = repo.snapshot()
        assertEquals(saved, snapshot)
        assertEquals(saved, repo.settings.first())
    }
}
