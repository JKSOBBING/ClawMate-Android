package com.clawmate.app.wechat.domain

import com.clawmate.app.log.domain.SensitiveLogRedactor
import com.clawmate.app.task.hdc.DisabledWorkflowDeviceAdapter
import com.clawmate.app.task.hdc.HdcActionResult
import com.clawmate.app.task.hdc.HdcBridgeErrorKind
import com.clawmate.app.task.hdc.HdcBridgeResponse
import com.clawmate.app.task.hdc.WorkflowDeviceAdapter

/**
 * Typed PC/HDC collection boundary for WeChat auto-summary, wired (Phase 25B)
 * to the safe [WorkflowDeviceAdapter.wechatCollect] action.
 *
 * Android cannot perform WeChat GUI collection on-device (WeChat is not running
 * on the phone's Android process in a controllable way here, and we must not
 * drive WeChat, send arbitrary commands, or fabricate summaries). Collection is
 * a PC-side Python/HDC service (`Python wechat_collect`); this boundary exposes
 * an *explicit*, caller-triggered [collect] that invokes the safe adapter
 * action **only** when every gate passes:
 *  1. strict request validation ([validateWechatAutoSummaryRequest]),
 *  2. the `summaryEnabled` opt-in,
 *  3. a health-verified [WorkflowDeviceAdapter] (its [WorkflowDeviceAdapter
 *     .isConfigured] is true, i.e. a live health reply with `status=ok` and both
 *     HDC forward+reverse port mappings ready).
 *
 * [collect] never sends `/api/run_cmd`, never drives WeChat directly, and never
 * fabricates a summary. It maps the adapter's [HdcActionResult] into a typed
 * [WechatCollectionResult] that reuses the [WechatAutoSummaryResponse] fields /
 * status vocabulary; messages surfaced to the caller are redacted of secrets
 * and bounded so untrusted bridge text cannot carry sensitive payloads back.
 * Failing any gate returns an honest typed result **without performing any
 * bridge call**.
 *
 * The passive [status] reflects the same gates: [WechatCollectionStatus.BLOCKED]
 * when no verified bridge is configured, [WechatCollectionStatus
 * .NOT_CONFIGURED] when auto-summary is off, and [WechatCollectionStatus
 * .AVAILABLE] only when a health-verified adapter is present and auto-summary is
 * enabled (the explicit [collect] may then be invoked).
 */

/** Truthful collection availability reported to the Settings UI. */
enum class WechatCollectionStatus {
    /** No health-verified PC/HDC bridge is configured, so collection is blocked. */
    BLOCKED,

    /** Auto-summary is switched off / not configured, so collection is dormant. */
    NOT_CONFIGURED,

    /** A health-verified adapter is present and auto-summary is enabled; an explicit [WechatCollectionGate.collect] is allowed. */
    AVAILABLE,

    /** Aggregate placeholder kept for typed exhaustiveness; not produced by the gate. */
    UNAVAILABLE,
}

/** A typed, always-honest result from a collection attempt. */
sealed class WechatCollectionResult {
    /**
     * Collection is not possible: invalid request, or a missing / not
     * health-verified bridge. [reason] is bounded and redacted.
     */
    data class Blocked(val reason: String) : WechatCollectionResult()

    /** Auto-summary is disabled; enable it before doing anything. */
    data class NotConfigured(val message: String) : WechatCollectionResult()

    /** The adapter ran the collection and the bridge replied `ok`. No summary is fabricated. */
    data class Success(val response: WechatAutoSummaryResponse) : WechatCollectionResult()

    /** The adapter ran the collection but the bridge replied a non-ok envelope (honest failure). */
    data class Error(val response: WechatAutoSummaryResponse) : WechatCollectionResult()

    /** Transport / HTTP / parse / cancellation failure prior to a usable bridge envelope. */
    data class TransportError(val kind: HdcBridgeErrorKind, val message: String) : WechatCollectionResult()

    /** The configured adapter refuses / cannot perform the collection (e.g. disabled default). */
    data class Unsupported(val reason: String) : WechatCollectionResult()
}

/** Upper bound on messages surfaced from the bridge into the typed result. */
private const val SURFACED_MESSAGE_BOUND = 240

/**
 * Redacts secrets from untrusted bridge text and bounds its length so a corrupt
 * or verbose bridge can never flood the caller or leak a sensitive value.
 */
private fun boundedRedact(raw: String?): String? {
    val text = raw ?: return null
    val clean = SensitiveLogRedactor.redact(text).trim()
    if (clean.isEmpty()) return null
    return clean.take(SURFACED_MESSAGE_BOUND)
}

/**
 * Derives collection availability and performs the explicit collection from a
 * [WorkflowDeviceAdapter] (the default [DisabledWorkflowDeviceAdapter] keeps the
 * boundary safe and deterministic when nothing is configured). Construction
 * never performs a network call.
 */
class WechatCollectionGate(
    private val adapter: WorkflowDeviceAdapter = DisabledWorkflowDeviceAdapter,
    private val summaryEnabled: Boolean,
) {

    /** True only when the wrapped adapter is health-verified and control-ready. */
    val isBridgeConfigured: Boolean get() = adapter.isConfigured

    val status: WechatCollectionStatus
        get() {
            if (!adapter.isConfigured) return WechatCollectionStatus.BLOCKED
            return if (summaryEnabled) {
                WechatCollectionStatus.AVAILABLE
            } else {
                WechatCollectionStatus.NOT_CONFIGURED
            }
        }

    /**
     * Explicitly collects a WeChat auto-summary through the safe adapter action,
     * performing a bridge call **only** when every gate passes. Honest typed
     * results are returned without any call when a gate fails:
     *  - an invalid [request] -> [WechatCollectionResult.Blocked] (validation),
     *  - auto-summary disabled -> [WechatCollectionResult.NotConfigured],
     *  - no health-verified adapter -> [WechatCollectionResult.Blocked].
     *
     * When allowed, [WorkflowDeviceAdapter.wechatCollect] drives the fixed
     * `wechat_collect` action; the adapter's [HdcActionResult.Success] /
     * [HdcActionResult.BridgeError] / [HdcActionResult.TransportError] /
     * [HdcActionResult.Unsupported] are mapped to typed [WechatCollectionResult]
     * reusing [WechatAutoSummaryResponse] fields/status vocabulary. A summary is
     * never fabricated and a [WechatCollectionResult.Success] is produced only
     * on a real `ok` bridge envelope.
     */
    suspend fun collect(request: WechatAutoSummaryRequest): WechatCollectionResult {
        val validation = validateWechatAutoSummaryRequest(request)
        if (validation.isNotEmpty()) {
            return WechatCollectionResult.Blocked("采集请求校验未通过：$validation")
        }
        if (!summaryEnabled) {
            return WechatCollectionResult.NotConfigured("微信自动汇总未启用，未发起任何采集。")
        }
        if (!adapter.isConfigured) {
            return WechatCollectionResult.Blocked(
                "微信汇总采集需要已配置并健康校验的 PC/HDC bridge；当前未配置，未发起任何采集。",
            )
        }
        return when (val action = adapter.wechatCollect(request)) {
            is HdcActionResult.Success -> WechatCollectionResult.Success(
                toSummaryResponse(status = "ok", bridgeValue = action.response),
            )
            is HdcActionResult.BridgeError -> WechatCollectionResult.Error(
                toSummaryResponse(
                    status = action.response.status,
                    bridgeValue = action.response,
                ),
            )
            is HdcActionResult.TransportError -> WechatCollectionResult.TransportError(
                kind = action.kind,
                message = boundedRedact(action.message) ?: "微信汇总采集发生传输错误（${action.kind}）。",
            )
            is HdcActionResult.Unsupported -> WechatCollectionResult.Unsupported(
                "微信汇总采集被适配器阻止：${boundedRedact(action.reason) ?: "适配器未提供安全的 wechat_collect 动作。"}",
            )
        }
    }

    /**
     * Builds a [WechatAutoSummaryResponse] from a bridge envelope using the
     * existing status/message vocabulary. The rich summary fields
     * ([WechatAutoSummaryResponse.conversations] /
     * [WechatAutoSummaryResponse.contactsCollected] /
     * [WechatAutoSummaryResponse.runId]) are left `null` unless the bridge
     * explicitly carried them; nothing is fabricated or inferred.
     */
    private fun toSummaryResponse(status: String, bridgeValue: HdcBridgeResponse): WechatAutoSummaryResponse =
        WechatAutoSummaryResponse(
            status = status,
            message = boundedRedact(bridgeValue.message) ?: boundedRedact(bridgeValue.result),
            runId = null,
        )
}
