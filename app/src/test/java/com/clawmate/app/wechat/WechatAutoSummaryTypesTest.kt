package com.clawmate.app.wechat

import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import com.clawmate.app.wechat.domain.WechatAutoSummaryRequest
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryDays
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryHistorySwipeRatio
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxContacts
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMaxHistorySwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryMode
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryStableSwipes
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummarySwipeSpeed
import com.clawmate.app.wechat.domain.normalizeWechatAutoSummaryWait
import com.clawmate.app.wechat.domain.validateWechatAutoSummaryRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the ported WeChat auto-summary normalizers and
 * validator, mirroring the HarmonyOS `WechatAutoSummaryTypes.ets` behaviour.
 */
class WechatAutoSummaryTypesTest {

    @Test
    fun `days clamps into the 1 to 90 range with default 7`() {
        assertEquals(7, normalizeWechatAutoSummaryDays("junk"))
        assertEquals(7, normalizeWechatAutoSummaryDays(""))
        assertEquals(1, normalizeWechatAutoSummaryDays("0"))
        assertEquals(90, normalizeWechatAutoSummaryDays("999"))
        assertEquals(30, normalizeWechatAutoSummaryDays("30.9"))
        assertEquals(12, normalizeWechatAutoSummaryDays("12"))
    }

    @Test
    fun `max contacts clamps into the 1 to 50 range with default 10`() {
        assertEquals(10, normalizeWechatAutoSummaryMaxContacts(""))
        assertEquals(1, normalizeWechatAutoSummaryMaxContacts("0"))
        assertEquals(50, normalizeWechatAutoSummaryMaxContacts("500"))
        assertEquals(10, normalizeWechatAutoSummaryMaxContacts("x"))
        assertEquals(5, normalizeWechatAutoSummaryMaxContacts("5"))
    }

    @Test
    fun `swipe speed clamps into the 0 to 20000 range`() {
        assertEquals(2000, normalizeWechatAutoSummarySwipeSpeed(""))
        assertEquals(0, normalizeWechatAutoSummarySwipeSpeed("-5"))
        assertEquals(20000, normalizeWechatAutoSummarySwipeSpeed("999999"))
        assertEquals(1500, normalizeWechatAutoSummarySwipeSpeed("1500"))
        assertEquals(2000, normalizeWechatAutoSummarySwipeSpeed("abc"))
    }

    @Test
    fun `history swipe ratio clamps into the 0_1 to 0_95 range`() {
        assertEquals(0.4, normalizeWechatAutoSummaryHistorySwipeRatio(""), 1e-9)
        assertEquals(0.1, normalizeWechatAutoSummaryHistorySwipeRatio("0.01"), 1e-9)
        assertEquals(0.95, normalizeWechatAutoSummaryHistorySwipeRatio("1.0"), 1e-9)
        assertEquals(0.5, normalizeWechatAutoSummaryHistorySwipeRatio("0.5"), 1e-9)
        assertEquals(0.4, normalizeWechatAutoSummaryHistorySwipeRatio("bad"), 1e-9)
    }

    @Test
    fun `stable swipes clamps into the 1 to 10 range`() {
        assertEquals(3, normalizeWechatAutoSummaryStableSwipes(""))
        assertEquals(1, normalizeWechatAutoSummaryStableSwipes("0"))
        assertEquals(10, normalizeWechatAutoSummaryStableSwipes("99"))
        assertEquals(5, normalizeWechatAutoSummaryStableSwipes("5"))
    }

    @Test
    fun `max history swipes clamps into the 1 to 300 range`() {
        assertEquals(80, normalizeWechatAutoSummaryMaxHistorySwipes(""))
        assertEquals(1, normalizeWechatAutoSummaryMaxHistorySwipes("0"))
        assertEquals(300, normalizeWechatAutoSummaryMaxHistorySwipes("9999"))
        assertEquals(120, normalizeWechatAutoSummaryMaxHistorySwipes("120"))
    }

    @Test
    fun `wait clamps into the 0 to 10 range`() {
        assertEquals(1.0, normalizeWechatAutoSummaryWait(""), 1e-9)
        assertEquals(0.0, normalizeWechatAutoSummaryWait("-3"), 1e-9)
        assertEquals(10.0, normalizeWechatAutoSummaryWait("50"), 1e-9)
        assertEquals(2.5, normalizeWechatAutoSummaryWait("2.5"), 1e-9)
    }

    @Test
    fun `mode defaults to recent and accepts target`() {
        assertEquals(WechatAutoSummaryModes.RECENT, normalizeWechatAutoSummaryMode(""))
        assertEquals(WechatAutoSummaryModes.RECENT, normalizeWechatAutoSummaryMode("garbage"))
        assertEquals(WechatAutoSummaryModes.TARGET, normalizeWechatAutoSummaryMode(WechatAutoSummaryModes.TARGET))
        assertEquals(WechatAutoSummaryModes.RECENT, normalizeWechatAutoSummaryMode(WechatAutoSummaryModes.RECENT))
    }

    // ---------------------------------------------------------- validator

    private fun validRequest() = WechatAutoSummaryRequest(
        mode = WechatAutoSummaryModes.RECENT,
        days = 7,
        maxContacts = 10,
        targetContact = "",
        swipeSpeed = 2000,
        historySwipeRatio = 0.4,
        stableSwipes = 3,
        maxHistorySwipes = 80,
        wait = 1.0,
    )

    @Test
    fun `valid recent request passes validation`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest()).isEmpty())
    }

    @Test
    fun `invalid mode is rejected`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(mode = "bogus")).isNotEmpty())
    }

    @Test
    fun `days out of range is rejected`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(days = 0)).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(days = 91)).isNotEmpty())
    }

    @Test
    fun `max contacts out of range is rejected`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(maxContacts = 0)).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(maxContacts = 51)).isNotEmpty())
    }

    @Test
    fun `ratio out of range is rejected`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(historySwipeRatio = 0.05)).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(historySwipeRatio = 0.99)).isNotEmpty())
    }

    @Test
    fun `target mode requires a non-blank contact`() {
        val target = validRequest().copy(mode = WechatAutoSummaryModes.TARGET, targetContact = "")
        assertTrue(validateWechatAutoSummaryRequest(target).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(target.copy(targetContact = "   ")).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(target.copy(targetContact = "Alice")).isEmpty())
    }

    @Test
    fun `max list swipes bounds are validated`() {
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(maxListSwipes = -1)).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(maxListSwipes = 101)).isNotEmpty())
        assertTrue(validateWechatAutoSummaryRequest(validRequest().copy(maxListSwipes = 50)).isEmpty())
    }
}
