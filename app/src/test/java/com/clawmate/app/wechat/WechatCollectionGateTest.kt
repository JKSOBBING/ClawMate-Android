package com.clawmate.app.wechat

import com.clawmate.app.task.hdc.HdcActionResult
import com.clawmate.app.task.hdc.HdcBridgeErrorKind
import com.clawmate.app.task.hdc.HdcBridgeResponse
import com.clawmate.app.task.hdc.WorkflowDeviceAdapter
import com.clawmate.app.wechat.domain.WechatAutoSummaryModes
import com.clawmate.app.wechat.domain.WechatAutoSummaryRequest
import com.clawmate.app.wechat.domain.WechatCollectionGate
import com.clawmate.app.wechat.domain.WechatCollectionResult
import com.clawmate.app.wechat.domain.WechatCollectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull

/**
 * Deterministic tests for the Phase-25B typed PC/HDC WeChat collection boundary.
 *
 * [WechatCollectionGate.collect] must (1) make ZERO bridge calls when any gate
 * fails (invalid request, auto-summary disabled, not-configured adapter),
 * (2) invoke [WorkflowDeviceAdapter.wechatCollect] exactly once when every gate
 * passes, and (3) map [HdcActionResult.Success] / [HdcActionResult.BridgeError] /
 * [HdcActionResult.TransportError] / [HdcActionResult.Unsupported] to the typed
 * [WechatCollectionResult] vocabulary (reusing [WechatAutoSummaryResponse]
 * status/message) without ever fabricating a summary.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WechatCollectionGateTest {

    // ------------------------------------------------------------- fake utils

    /** A configurable fake adapter; records invocation count. */
    private class FakeAdapter(
        override var isConfigured: Boolean,
        var result: HdcActionResult = HdcActionResult.Unsupported("not wired"),
        var cooperativeDelay: Boolean = false,
    ) : WorkflowDeviceAdapter {
        var collectCalls: Int = 0
        var lastRequest: WechatAutoSummaryRequest? = null

        override suspend fun health(): HdcActionResult = result
        override suspend fun startApp(appName: String?, packageName: String?, resetFirst: Boolean): HdcActionResult = result
        override suspend fun screenshot(factor: Double): HdcActionResult = result
        override suspend fun guiAction(payload: Map<String, Any?>): HdcActionResult = result
        override suspend fun executeDeciderAction(responseText: String, width: Int, height: Int): HdcActionResult = result

        override suspend fun wechatCollect(request: WechatAutoSummaryRequest): HdcActionResult {
            lastRequest = request
            return try {
                if (cooperativeDelay) {
                    // Suspend cooperatively so outer cancellation propagates up
                    // through this suspend boundary as a CancellationException.
                    awaitCancellation()
                }
                result
            } finally {
                collectCalls += 1
            }
        }
    }

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

    private fun configuredAdapter(
        result: HdcActionResult = HdcActionResult.Success(
            HdcBridgeResponse(status = "ok", message = "collected 3 contacts"),
        ),
        cooperativeDelay: Boolean = false,
    ) = FakeAdapter(isConfigured = true, result = result, cooperativeDelay = cooperativeDelay)

    // ------------------------------------------------------------ no-call gates

    @Test
    fun `no verified bridge yields BLOCKED status`() {
        val gate = WechatCollectionGate(adapter = FakeAdapter(isConfigured = false), summaryEnabled = true)
        assertEquals(WechatCollectionStatus.BLOCKED, gate.status)
    }

    @Test
    fun `disabled summary yields NOT_CONFIGURED`() {
        val gate = WechatCollectionGate(adapter = FakeAdapter(isConfigured = true), summaryEnabled = false)
        assertEquals(WechatCollectionStatus.NOT_CONFIGURED, gate.status)
    }

    @Test
    fun `configured and enabled yields AVAILABLE`() {
        val gate = WechatCollectionGate(adapter = FakeAdapter(isConfigured = true), summaryEnabled = true)
        assertEquals(WechatCollectionStatus.AVAILABLE, gate.status)
    }

    @Test
    fun `invalid request is blocked without any bridge call`() = runTest {
        val adapter = configuredAdapter()
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = true)
        val result = gate.collect(validRequest().copy(days = 0))
        assertTrue(result is WechatCollectionResult.Blocked)
        assertEquals(0, adapter.collectCalls)
    }

    @Test
    fun `disabled summary is not configured without any bridge call`() = runTest {
        val adapter = FakeAdapter(isConfigured = true)
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = false)
        val result = gate.collect(validRequest())
        assertTrue("disabled summary must surface NotConfigured", result is WechatCollectionResult.NotConfigured)
        assertEquals(0, adapter.collectCalls)
    }

    @Test
    fun `not-configured adapter is blocked without any bridge call`() = runTest {
        val adapter = FakeAdapter(isConfigured = false)
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = true)
        val result = gate.collect(validRequest())
        assertTrue("not-configured adapter must not reach the bridge", result is WechatCollectionResult.Blocked)
        assertEquals(0, adapter.collectCalls)
    }

    @Test
    fun `default disabled adapter blocks without any bridge call`() = runTest {
        // No adapter supplied -> default disabled; isConfigured is false, so no call.
        val gate = WechatCollectionGate(summaryEnabled = true)
        assertTrue(gate.collect(validRequest()) is WechatCollectionResult.Blocked)
        assertEquals(WechatCollectionStatus.BLOCKED, gate.status)
    }

    // --------------------------------------------------------------- valid call

    @Test
    fun `valid request with configured adapter makes exactly one call and maps Success`() = runTest {
        val adapter = configuredAdapter(
            HdcActionResult.Success(HdcBridgeResponse(status = "ok", message = "done")),
        )
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = true)
        val result = gate.collect(validRequest())

        assertEquals(1, adapter.collectCalls)
        assertEquals(validRequest(), adapter.lastRequest)
        assertTrue(result is WechatCollectionResult.Success)
        val success = result as WechatCollectionResult.Success
        assertEquals("ok", success.response.status)
        assertNull("must never fabricate conversations", success.response.conversations)
        assertNull("must never fabricate collected contacts", success.response.contactsCollected)
        assertNull("must never fabricate a run id", success.response.runId)
    }

    // --------------------------------------------------------------- mapping

    @Test
    fun `bridge error envelope maps to typed Error with bridge status`() = runTest {
        val adapter = configuredAdapter(
            HdcActionResult.BridgeError(HdcBridgeResponse(status = "error", message = "wechat_collect_failed")),
        )
        val result = WechatCollectionGate(adapter = adapter, summaryEnabled = true).collect(validRequest())
        assertTrue(result is WechatCollectionResult.Error)
        val error = result as WechatCollectionResult.Error
        assertEquals("error", error.response.status)
        assertEquals("wechat_collect_failed", error.response.message)
    }

    @Test
    fun `transport error maps to typed TransportError with kind and bounded message`() = runTest {
        val adapter = configuredAdapter(
            HdcActionResult.TransportError(HdcBridgeErrorKind.TIMEOUT, "timed out collecting"),
        )
        val result = WechatCollectionGate(adapter = adapter, summaryEnabled = true).collect(validRequest())
        assertTrue(result is WechatCollectionResult.TransportError)
        val transport = result as WechatCollectionResult.TransportError
        assertEquals(HdcBridgeErrorKind.TIMEOUT, transport.kind)
        assertEquals("timed out collecting", transport.message)
    }

    @Test
    fun `unsupported adapter result maps to typed Unsupported`() = runTest {
        val adapter = configuredAdapter(HdcActionResult.Unsupported("no wechat_collect"))
        val result = WechatCollectionGate(adapter = adapter, summaryEnabled = true).collect(validRequest())
        assertTrue(result is WechatCollectionResult.Unsupported)
        assertTrue((result as WechatCollectionResult.Unsupported).reason.contains("wechat_collect"))
    }

    @Test
    fun `surfaced bridge messages are bounded and secret-redacted`() = runTest {
        val secretMessage = "Bearer sk-1234567890abcdefgh " + "x".repeat(500)
        val adapter = configuredAdapter(HdcActionResult.Success(HdcBridgeResponse(status = "ok", message = secretMessage)))
        val result = WechatCollectionGate(adapter = adapter, summaryEnabled = true).collect(validRequest())
        val message = (result as WechatCollectionResult.Success).response.message
        assertTrue("secret must be redacted", message != null && message.contains("[REDACTED]"))
        assertTrue("message must be bounded", message.isNullOrEmpty() || message.length <= 240)
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun `collect propagates cancellation from the adapter`() = runTest {
        val adapter = configuredAdapter(cooperativeDelay = true)
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = true)
        var thrown: Throwable? = null
        val job = launch {
            try {
                gate.collect(validRequest())
            } catch (t: Throwable) {
                thrown = t
            }
        }
        kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertTrue(
            "cancellation must propagate a CancellationException",
            thrown is kotlinx.coroutines.CancellationException,
        )
        assertEquals("adapter was entered exactly once", 1, adapter.collectCalls)
    }

    @Test
    fun `cooperative delay starts exactly one bridge call before cancellation`() = runTest {
        val adapter = configuredAdapter(cooperativeDelay = true)
        val gate = WechatCollectionGate(adapter = adapter, summaryEnabled = true)
        val job = launch { gate.collect(validRequest()) }
        // Let the call reach the cooperative suspension, then cancel.
        kotlinx.coroutines.yield()
        job.cancel()
        job.join()
        assertEquals("adapter was entered exactly once", 1, adapter.collectCalls)
    }
}
