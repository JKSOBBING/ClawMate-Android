package com.clawmate.app.wechat

import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import com.clawmate.app.wechat.domain.WechatAutoSummaryRequest
import com.clawmate.app.wechat.domain.WechatCollectionGate
import com.clawmate.app.wechat.domain.WechatCollectionResult
import com.clawmate.app.wechat.domain.WechatCollectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the Phase-13 typed PC/HDC WeChat collection boundary.
 *
 * The boundary must stay BLOCKED when no verified bridge is configured, and it
 * must never dispatch a collection or fabricate a summary even when a bridge
 * descriptor is present. Because the Android [com.clawmate.app.task.hdc
 * .WorkflowDeviceAdapter] interface exposes no `wechat_collect` action, the
 * boundary stays BLOCKED rather than ever running or faking collection.
 */
class WechatCollectionGateTest {

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
    fun `no verified bridge yields BLOCKED even when enabled`() {
        val gate = WechatCollectionGate(
            verifiedBridgeConfigured = false,
            summaryEnabled = true,
        )
        assertEquals(WechatCollectionStatus.BLOCKED, gate.status)
        val result = gate.collect(validRequest())
        assertTrue(result is WechatCollectionResult.Blocked)
    }

    @Test
    fun `disabled summary yields NOT_CONFIGURED`() {
        val gate = WechatCollectionGate(
            verifiedBridgeConfigured = true,
            summaryEnabled = false,
        )
        assertEquals(WechatCollectionStatus.NOT_CONFIGURED, gate.status)
        assertTrue(gate.collect(validRequest()) is WechatCollectionResult.NotConfigured)
    }

    @Test
    fun `configured bridge still reports BLOCKED rather than faking a summary`() {
        // A health-verified adapter exists, but the Android interface exposes no
        // safe wechat_collect action; collection must remain an explicit blocked
        // boundary and never return a fabricated success.
        val gate = WechatCollectionGate(
            verifiedBridgeConfigured = true,
            summaryEnabled = true,
        )
        assertEquals("configured bridge must still be BLOCKED", WechatCollectionStatus.BLOCKED, gate.status)
        val result = gate.collect(validRequest())
        assertTrue("must never fake a summary", result is WechatCollectionResult.Blocked)
    }

    @Test
    fun `an invalid request is rejected as BLOCKED before anything else`() {
        val gate = WechatCollectionGate(verifiedBridgeConfigured = false, summaryEnabled = true)
        val invalid = validRequest().copy(days = 0)
        val result = gate.collect(invalid)
        assertTrue("invalid request surfaces a bounded blocked reason", result is WechatCollectionResult.Blocked)
    }
}
