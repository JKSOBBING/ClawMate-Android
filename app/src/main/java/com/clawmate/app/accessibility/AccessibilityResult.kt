package com.clawmate.app.accessibility

/**
 * Truthful availability and dispatch results for the Phase 19 AutoAgent
 * controlled-accessibility boundary (migration row B1).
 *
 * Android can never *silently* provide the HarmonyOS AutoAgent GUI automation
 * (`AutoAgentAbility.ets` + `ChatReactAgent.ets`). This boundary makes that
 * honesty explicit:
 *  - [AccessibilityGateStatus] is the live availability of the boundary;
 *  - [AccessibilityActionResult] is what a dispatch attempt yields — it is
 *    always a typed gate/unavailable/rejection, **never a fake success**.
 */

/**
 * The live availability of the AutoAgent accessibility boundary.
 *
 *  - [NOT_CONFIGURED] — the user has not explicitly opted in (the settings
 *    gate is off); the boundary is dormant and nothing is attempted.
 *  - [BLOCKED] — the user opted in but Android has not granted/connected the
 *    accessibility service, so nothing can be attempted.
 *  - [READY] — the user opted in AND the system accessibility service is
 *    connected AND a safe action provider is configured. This build ships no
 *    action provider, so [READY] is not reachable with the defaults; it exists
 *    so a future real provider is represented without claiming capability today.
 */
enum class AccessibilityGateStatus {
    NOT_CONFIGURED,
    BLOCKED,
    READY,
}

/**
 * The typed result of attempting an AutoAgent action through the boundary.
 * One of these is returned every time; there is intentionally no success
 * variant in this build because the safe scaffold performs no on-device GUI
 * control and a fake success is forbidden.
 */
sealed class AccessibilityActionResult {
    /** User has not opted in / auto-agent is switched off. */
    data class NotConfigured(val message: String) : AccessibilityActionResult()

    /** Opted in but the system accessibility permission/service is absent. */
    data class BlockedPermission(val reason: String) : AccessibilityActionResult()

    /** Opted in + service present but no safe action provider is configured. */
    data class BlockedNoProvider(val action: AccessibilityActionId, val reason: String) : AccessibilityActionResult()

    /** The requested action is not on the closed allowlist. */
    data class RejectedNotAllowlisted(val requested: String) : AccessibilityActionResult()

    /** A recognised action was rejected because its payload is out of bounds. */
    data class RejectedUnsupportedPayload(val action: AccessibilityActionId, val reason: String) : AccessibilityActionResult()
}

/**
 * Pure, deterministic gate that derives the AutoAgent accessibility boundary
 * from three independent facts and returns honest results for any dispatch.
 *
 * Inputs are injected so the gate is fully host-testable:
 *  - [optedIn] — the user's explicit settings opt-in ([AppSettings.accessibilityOptIn]);
 *  - [systemServiceEnabled] — whether the Android system reports our
 *    accessibility service as enabled/connected (see
 *    [AccessibilityServiceDetector] — an abstraction so this is unit-testable
 *    without a device);
 *  - [providerConfigured] — whether a real safe action provider is configured
 *    (no provider exists in this build, so [READY] is normally unreachable).
 *
 * The gate never executes anything and never fakes a result: it only derives
 * status and maps a dispatched action to a typed [AccessibilityActionResult].
 */
class AccessibilityGate(
    private val optedIn: Boolean,
    private val systemServiceEnabled: Boolean,
    private val providerConfigured: Boolean,
) {

    val status: AccessibilityGateStatus
        get() = when {
            !optedIn -> AccessibilityGateStatus.NOT_CONFIGURED
            !systemServiceEnabled -> AccessibilityGateStatus.BLOCKED
            !providerConfigured -> AccessibilityGateStatus.BLOCKED
            else -> AccessibilityGateStatus.READY
        }

    /**
     * Evaluate a raw dispatch by wire [name] + [params]. The action is first
     * checked against the closed allowlist and shape, then mapped to the
     * truthful result for the current status. Never executes, never fakes.
     */
    fun evaluate(name: String, params: Map<String, Any?>): AccessibilityActionResult {
        val validated = AccessibilityActionValidation.validate(name, params)
        return when (validated) {
            is AccessibilityActionValidation.RejectedNotAllowlisted ->
                AccessibilityActionResult.RejectedNotAllowlisted(validated.requested)
            is AccessibilityActionValidation.RejectedUnsupportedPayload ->
                AccessibilityActionResult.RejectedUnsupportedPayload(validated.id, validated.reason)
            is AccessibilityActionValidation.Valid -> evaluateValid(validated.action)
        }
    }

    private fun evaluateValid(action: AccessibilityAction): AccessibilityActionResult {
        return when {
            !optedIn -> AccessibilityActionResult.NotConfigured(
                "AutoAgent 辅助功能未启用：请在设置中显式开启后重试。未执行任何操作。",
            )
            !systemServiceEnabled -> AccessibilityActionResult.BlockedPermission(
                "已开启 AutoAgent 但我们未观察到系统辅助功能服务已连接。请在系统「无障碍」设置中授予 ClawMate 的辅助功能访问权限后重试。未执行任何操作。",
            )
            !providerConfigured -> AccessibilityActionResult.BlockedNoProvider(
                action.id,
                "AutoAgent 辅助功能服务已连接，但当前构建未配置任何安全动作执行器；'${action.id.wireName}' 不会在本机执行。请使用 PC/HDC 桥接（已作为受控禁用状态暴露）完成该操作。",
            )
            else -> AccessibilityActionResult.BlockedNoProvider(
                action.id,
                "AutoAgent 边界就绪，但安全脚手架不执行任何跨应用动作；'${action.id.wireName}' 保持受控禁用。",
            )
        }
    }
}
