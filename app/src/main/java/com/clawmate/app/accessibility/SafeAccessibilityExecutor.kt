package com.clawmate.app.accessibility

/**
 * Phase 22 — the *gated, package-validating* executor for the safe semantic
 * AutoAgent action allowlist ([SafeAccessibilityAction]).
 *
 * Architecture (all of it device-free except the platform seam):
 *
 *  - [AccessibilityExecutionOutcome] — the honest typed result of a dispatch:
 *    NOT_CONFIGURED (no explicit opt-in), BLOCKED_SERVICE (service not
 *    connected), BLOCKED_PACKAGE (foreground/target package not allowlisted),
 *    UNSUPPORTED / ERROR (platform refusal / failure), SUCCESS (a real node or
 *    global action was actually performed). There is **no fake success**: SUCCESS
 *    is emitted only when the platform seam reports the action truly ran.
 *
 *  - [SafeAccessibilityExecutionGate] — a pure readiness derivation over the
 *    four observed facts (opt-in, service enabled, foreground package,
 *    allowlisted packages). It never executes and never fabricates availability.
 *
 *  - [SafeAccessibilityActionExecutor] — the *platform seam* (implemented by the
 *    real [AccessibilityAutoAgentService] via its lifecycle-safe
 *    [AndroidSafeAccessibilityExecutor]). It is the only thing that can touch
 *    `AccessibilityNodeInfo` / `performGlobalAction`, and it is reached **only**
 *    through [ControlledSafeAccessibilityDispatcher].
 *
 *  - [ControlledSafeAccessibilityDispatcher] — the single gated entry point used
 *    by the Settings demo boundary (and, in a live session, the agent). It:
 *      1. validates the action against the closed allowlist + length bounds;
 *      2. builds an honest preflight from the live facts (opt-in, service,
 *         foreground package, allowlist);
 *      3. performs the action **only** when every precondition holds, and only by
 *         calling the injected platform seam;
 *      4. maps the seam's result to a truthful [AccessibilityExecutionOutcome].
 *
 * The foreground package is validated from live state **before every action** —
 * never trusted from a stale value and never skipped.
 *
 * Safety: no arbitrary coordinates, raw key events, shell/adb/hdc, screenshots,
 * hidden input, cross-app data extraction, or automatic service start exist in
 * this path. The service never acts unless invoked through [dispatch].
 */

/** Honest, typed result of attempting a safe semantic AutoAgent action. */
sealed class AccessibilityExecutionOutcome {
    /** No explicit user opt-in — the boundary is dormant and nothing is attempted. */
    data class NotConfigured(val message: String) : AccessibilityExecutionOutcome()

    /** Opted in but the system accessibility service is not connected. */
    data class BlockedService(val reason: String) : AccessibilityExecutionOutcome()

    /**
     * The foreground (or requested target) package is not allowlisted, or the
     * request broke the allowlist precondition. Nothing is performed.
     */
    data class BlockedPackage(val reason: String) : AccessibilityExecutionOutcome()

    /**
     * The action is genuinely recognised but cannot be represented by the safe
     * Android surface (e.g. no matching node for a CLICK target). Not performed.
     */
    data class Unsupported(val reason: String) : AccessibilityExecutionOutcome()

    /** An unexpected platform failure occurred while the action was attempted. */
    data class Error(val reason: String) : AccessibilityExecutionOutcome()

    /**
     * A real node/global action was actually performed. [performedDescription]
     * is the honest "what ran" summary — never fabricated.
     */
    data class Success(val performedDescription: String) : AccessibilityExecutionOutcome()
}

/**
 * Pure, deterministic readiness gate for the safe semantic executor. One of
 * {NOT_CONFIGURED, BLOCKED_SERVICE, BLOCKED_PACKAGE, READY}.
 */
sealed class SafeAccessibilityReadiness {
    object NotConfigured : SafeAccessibilityReadiness()
    object BlockedService : SafeAccessibilityReadiness()
    object BlockedPackage : SafeAccessibilityReadiness()
    object Ready : SafeAccessibilityReadiness()
}

/**
 * Pure derivation of [SafeAccessibilityReadiness] from the four live facts. This
 * is the single place the "service performs no action unless the boundary is
 * genuinely ready" rule is computed. Injectable + fully host-testable.
 *
 * @param optedIn the user's explicit settings opt-in
 * @param serviceEnabled whether the Android system accessibility service is
 *   connected ([AccessibilityAutoAgentService] reporting connected)
 * @param currentForegroundPackage the *live* foreground package at dispatch time
 * @param allowlistedPackages the closed foreground package allowlist
 */
class SafeAccessibilityExecutionGate(
    private val optedIn: Boolean,
    private val serviceEnabled: Boolean,
    private val currentForegroundPackage: String?,
    private val allowlistedPackages: Set<String>,
) {

    val readiness: SafeAccessibilityReadiness
        get() = when {
            !optedIn -> SafeAccessibilityReadiness.NotConfigured
            !serviceEnabled -> SafeAccessibilityReadiness.BlockedService
            currentForegroundPackage == null -> SafeAccessibilityReadiness.BlockedPackage
            currentForegroundPackage !in allowlistedPackages -> SafeAccessibilityReadiness.BlockedPackage
            else -> SafeAccessibilityReadiness.Ready
        }

    /** A user-facing explanation of the current readiness (the honest status line). */
    fun statusMessage(): String = when (readiness) {
        SafeAccessibilityReadiness.NotConfigured ->
            "安全动作执行器未启用（NOT_CONFIGURED）：请在设置中显式开启 AutoAgent 辅助功能后重试。未执行任何操作。"
        SafeAccessibilityReadiness.BlockedService ->
            "已开启但未观察到系统辅助功能服务已连接（BLOCKED_SERVICE）：请在系统「无障碍」设置中授予 ClawMate 辅助功能访问后重试。未执行任何操作。"
        SafeAccessibilityReadiness.BlockedPackage ->
            "当前前台应用（$currentForegroundPackage）不在安全动作白名单内（BLOCKED_PACKAGE），拒绝执行任何跨应用/系统动作。未执行任何操作。"
        SafeAccessibilityReadiness.Ready -> "安全动作执行器就绪（READY）。"
    }
}

/**
 * The *platform output* of a single performed action. Only a successful, real
 * perform reports success; anything else is an honest failure.
 */
sealed class SafeActionPerformResult {
    /** One safe node/global accessibility action was actually executed. */
    data class Performed(val description: String) : SafeActionPerformResult()

    /** The action was recognised but cannot be represented by the safe surface. */
    data class Unsupported(val reason: String) : SafeActionPerformResult()

    /** The safe surface could not complete the action. */
    data class Failed(val reason: String) : SafeActionPerformResult()
}

/**
 * The platform seam the real AccessibilityService implements. It is the only
 * type that may touch `AccessibilityNodeInfo` / `performGlobalAction`. This
 * interface ensures the service cannot act "for free": it does nothing unless
 * invoked through [ControlledSafeAccessibilityDispatcher.dispatch].
 */
fun interface SafeAccessibilityActionExecutor {
    /** Perform one already-validated [action]; returns the honest perform result. */
    fun perform(action: SafeAccessibilityAction): SafeActionPerformResult
}

/**
 * The single gated entry point. Used by the Settings demo boundary and (in a
 * live session) the agent. Never trusts inputs and never bypasses the gate; the
 * foreground package is re-checked before every action. Fully device-free
 * (the [executor] seam is injected) so every outcome is unit-testable on host.
 */
class ControlledSafeAccessibilityDispatcher(
    private val gate: SafeAccessibilityExecutionGate,
    private val executor: SafeAccessibilityActionExecutor,
) {

    /**
     * Dispatch a raw [name] + [params] through the safe executor.
     *
     * Order of checks (nothing is performed unless every one passes):
     *  1. allowlist + bounded-input validation → rejection / UNSUPPORTED;
     *  2. readiness preflight → NOT_CONFIGURED / BLOCKED_SERVICE / BLOCKED_PACKAGE;
     *  3. platform perform → SUCCESS / UNSUPPORTED / ERROR.
     */
    fun dispatch(name: String, params: Map<String, Any?>): AccessibilityExecutionOutcome {
        val validated = SafeAccessibilityActionValidation.validate(name, params)
        return when (validated) {
            is SafeAccessibilityActionValidation.RejectedNotAllowlisted ->
                UnsupportOutcome.rejected(name)
            is SafeAccessibilityActionValidation.RejectedUnsupportedPayload ->
                UnsupportOutcome.unreachablePayload(validated.id, validated.reason)
            is SafeAccessibilityActionValidation.Valid ->
                dispatchValid(validated.action)
        }
    }

    private fun dispatchValid(action: SafeAccessibilityAction): AccessibilityExecutionOutcome {
        when (gate.readiness) {
            SafeAccessibilityReadiness.NotConfigured ->
                return AccessibilityExecutionOutcome.NotConfigured(gate.statusMessage())
            SafeAccessibilityReadiness.BlockedService ->
                return AccessibilityExecutionOutcome.BlockedService(gate.statusMessage())
            SafeAccessibilityReadiness.BlockedPackage ->
                return AccessibilityExecutionOutcome.BlockedPackage(gate.statusMessage())
            SafeAccessibilityReadiness.Ready -> Unit
        }

        // Package precondition satisfied by the gate above (foreground allowlisted)
        // before any action. Proceed to the platform seam.
        return when (val performed = executor.perform(action)) {
            is SafeActionPerformResult.Performed ->
                AccessibilityExecutionOutcome.Success(performed.description)
            is SafeActionPerformResult.Unsupported ->
                AccessibilityExecutionOutcome.Unsupported(performed.reason)
            is SafeActionPerformResult.Failed ->
                AccessibilityExecutionOutcome.Error(performed.reason)
        }
    }
}

/** Helpers to build the "recognised but unusable" outcomes. */
private object UnsupportOutcome {
    fun rejected(requested: String): AccessibilityExecutionOutcome =
        AccessibilityExecutionOutcome.Unsupported(
            "动作[$requested]不在安全语义白名单内（仅支持：click_text / click_content_description / back / " +
                "scroll_forward / scroll_backward / global_back / global_home）。未执行任何操作。",
        )

    fun unreachablePayload(id: SafeAccessibilityActionId, reason: String): AccessibilityExecutionOutcome =
        AccessibilityExecutionOutcome.Unsupported(
            "安全动作[${id.wireName}]参数不合法：$reason。未执行任何操作。",
        )
}

/**
 * Builds a [SafeAccessibilityExecutionGate] from the live facts in the shape the
 * Settings demo boundary uses, defaulting the allowlist to the app's own
 * package only.
 */
object SafeAccessibilityDefaults {
    /** The closed foreground package allowlist (conservative: ClawMate itself). */
    val PACKAGE_ALLOWLIST: Set<String> =
        setOf("com.clawmate.app")

    fun gate(
        optedIn: Boolean,
        serviceEnabled: Boolean,
        currentForegroundPackage: String?,
        allowlistedPackages: Set<String> = PACKAGE_ALLOWLIST,
    ): SafeAccessibilityExecutionGate = SafeAccessibilityExecutionGate(
        optedIn = optedIn,
        serviceEnabled = serviceEnabled,
        currentForegroundPackage = currentForegroundPackage,
        allowlistedPackages = allowlistedPackages.ifEmpty { PACKAGE_ALLOWLIST },
    )
}

/**
 * The honest, device-free *demo/test boundary* model surfaced in Settings. It is
 * the read-only, preflight shape the demo renders: it never claims an action ran.
 *
 * [readinessLabel] is one of the safe readiness categories; [allowlist] is the
 * closed semantic action set; [packageAllowlist] is the closed foreground
 * package set. The panel additionally calls
 * [ControlledSafeAccessibilityDispatcher.dispatch] with a **no-perform** seam so
 * the reported result is always an honest preflight/rejection — never a fake
 * SUCCESS — and it never triggers a real system/navigation action from the panel.
 */
data class SafeAccessibilityExecutorBoundary(
    val readinessLabel: String,
    val statusMessage: String,
    val allowlist: List<String>,
    val packageAllowlist: Set<String>,
) {
    companion object {
        /** The closed semantic action allowlist rendered in the demo panel. */
        val SAFE_ACTION_ALLOWLIST: List<String> =
            SafeAccessibilityActionId.entries.map { it.wireName }
    }
}
