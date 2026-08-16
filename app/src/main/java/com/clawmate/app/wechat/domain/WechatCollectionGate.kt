package com.clawmate.app.wechat.domain

import com.clawmate.app.task.hdc.WorkflowDeviceAdapter

/**
 * Typed PC/HDC collection boundary for WeChat auto-summary.
 *
 * Android cannot perform WeChat GUI collection on-device (WeChat is not running
 * on the phone's Android process in a controllable way here, and we must not
 * drive WeChat, send arbitrary commands, or fabricate summaries). Collection is
 * a PC-side Python/HDC service (Harmony `utils/wechat/WechatAutoSummaryClient.ets`
 * sends `POST /api/workflow` `{action:"wechat_collect", payload}`). Android may
 * only surface a *truthful* availability state derived from the Phase-8 verified
 * bridge.
 *
 * The verified bridge is a [WorkflowDeviceAdapter] whose [WorkflowDeviceAdapter
 * .isConfigured] is true (i.e. a health reply with `status=ok` and both HDC
 * forward+reverse port mappings ready) AND, critically, that safely exposes a
 * `wechat_collect` action. The Android [WorkflowDeviceAdapter] interface only
 * exposes the safe actions `health/startApp/screenshot/guiAction/
 * executeDeciderAction` — it does **not** expose a `wechat_collect` action and
 * must never send `run_cmd`. So even a configured bridge cannot be used to
 * collect WeChat through this adapter: [collect] always returns a typed
 * [WechatCollectionResult.Blocked] and the boundary never emulates or fakes a
 * summary.
 *
 * Status derivation is strict and honest:
 *  - [WechatCollectionStatus.BLOCKED] — no verified bridge is configured at all,
 *    or the bridge cannot provide a safe `wechat_collect` action (always, here).
 *  - [WechatCollectionStatus.NOT_CONFIGURED] — a verified bridge descriptor is
 *    present but WeChat auto-summary is disabled / not configured.
 */

/** Truthful collection availability reported to the Settings UI. */
enum class WechatCollectionStatus {
    /** Collection is possible only after a verified PC/HDC bridge + safe action. */
    BLOCKED,

    /** Auto-summary is switched off / not configured, so collection is dormant. */
    NOT_CONFIGURED,

    /** Aggregate of [BLOCKED] / [NOT_CONFIGURED]; present for typed exhaustiveness. */
    UNAVAILABLE,
}

/** A typed, always-honest result from a collection attempt. */
sealed class WechatCollectionResult {
    /** WeChat auto-summary collection is not possible in this build. */
    data class Blocked(val reason: String) : WechatCollectionResult()

    /** Auto-summary is disabled; enable it before doing anything. */
    data class NotConfigured(val message: String) : WechatCollectionResult()
}

/**
 * Derives the collection availability from the Phase-8 verified-bridge state.
 *
 * [verifiedBridgeConfigured] is `true` only when a health-verified
 * [WorkflowDeviceAdapter] is registered (see
 * `SettingsViewModel.testHdcHealth` / `TaskComponent.setDeviceAdapter`). The
 * default params keep the boundary safe and deterministic when nothing is
 * configured.
 */
class WechatCollectionGate(
    private val verifiedBridgeConfigured: Boolean,
    private val summaryEnabled: Boolean,
) {

    val status: WechatCollectionStatus
        get() {
            if (!verifiedBridgeConfigured) return WechatCollectionStatus.BLOCKED
            return if (summaryEnabled) {
                // A bridge is configured, but the Android adapter exposes no safe
                // wechat_collect action; remain blocked rather than faking.
                WechatCollectionStatus.BLOCKED
            } else {
                WechatCollectionStatus.NOT_CONFIGURED
            }
        }

    /**
     * Attempting to collect never dispatches anything. Returns a typed,
     * honest [WechatCollectionResult] and never fabricates a summary. The
     * [request] is validated locally so an illegal request surfaces a bounded
     * [WechatCollectionResult.Blocked] reason, but no action is ever sent.
     */
    fun collect(request: WechatAutoSummaryRequest): WechatCollectionResult {
        val validation = validateWechatAutoSummaryRequest(request)
        val base = when (status) {
            WechatCollectionStatus.BLOCKED -> WechatCollectionResult.Blocked(
                "微信汇总采集需要已配置并健康校验的 PC/HDC bridge，且 bridge 需安全暴露 wechat_collect 动作；当前 Android 适配器不会执行任意命令或伪造汇总。",
            )
            WechatCollectionStatus.NOT_CONFIGURED -> WechatCollectionResult.NotConfigured(
                "微信自动汇总未启用，未发起任何采集。",
            )
            WechatCollectionStatus.UNAVAILABLE -> WechatCollectionResult.Blocked(
                "微信汇总采集当前不可用。",
            )
        }
        if (validation.isNotEmpty()) {
            return WechatCollectionResult.Blocked("采集请求校验未通过：$validation")
        }
        return base
    }
}
