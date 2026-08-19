package com.clawmate.app.accessibility

/**
 * Phase 47 — the pure, device-free bridge that connects the agent's GUI tools
 * to the existing, gated safe semantic executor
 * ([ControlledSafeAccessibilityDispatcher] / [SafeAccessibilityExecutor]).
 *
 * This is the *only* production caller of
 * [AccessibilityAutoAgentService.executeSafeAction] (via the live service), but
 * the core logic here is fully injectable so every gate, allowlist, bound and
 * sanitization rule is unit-testable on the host without a device.
 *
 * Two responsibilities:
 *
 *  1. **Action mapping** — map a GUI tool wire name to one of the seven closed
 *     [SafeAccessibilityActionId]s. There is NO mapping for the Phase-19 gated
 *     coordinate/gesture/extraction actions (`tap`, `swipe`, `type_text`,
 *     `open_app`, `capture_screen`) — those are never reachable here.
 *
 *  2. **Gated dispatch + sanitized result** — build the live
 *     [SafeAccessibilityExecutionGate] from injected facts, run it through
 *     [ControlledSafeAccessibilityDispatcher], and fold the honest
 *     [AccessibilityExecutionOutcome] into a **bounded, de-identified** tool
 *     result. No full [AccessibilityNodeInfo], no screen text, no screenshot,
 *     no URI and no token ever crosses this boundary: success is reported only
 *     as a fixed semantic label, and every failure is an explicit
 *     BLOCKED / NOT_CONFIGURED / UNSUPPORTED status.
 *
 * The default behavior is REJECTED: when the user has not explicitly opted in,
 * no action is attempted (NOT_CONFIGURED).
 */

/** The sanitized, bounded textual result handed back to the agent's model. */
data class SafeActionToolResult(
    /** `true` only when a real safe node/global action actually ran. */
    val ok: Boolean,
    /** One of NOT_CONFIGURED / BLOCKED_SERVICE / BLOCKED_PACKAGE / UNSUPPORTED / ERROR / SUCCESS. */
    val status: String,
    /** A bounded, de-identified human-readable summary (≤ [MAX_MESSAGE_LENGTH]). */
    val message: String,
) {
    /**
     * A bounded JSON object the chat adapter can return to the model. It never
     * carries the raw performed description, node text, coordinates, URI or
     * secrets — only `ok`, `status` and the sanitized message.
     */
    fun toJsonString(): String =
        """{"ok":$ok,"status":${jsonString(status)},"message":${jsonString(message)}}"""

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(value),
        )

    companion object {
        /** Bounded length of any message that reaches the model. */
        const val MAX_MESSAGE_LENGTH: Int = 160
    }
}

/**
 * The pure bridge. Constructed with the parameters already parsed by the tool
 * adapter; [dispatch] decides what (if anything) is performed.
 */
object GuiSafeActionBridge {

    /** The single wire name for each safe semantic action the tools expose. */
    fun toolNameFor(id: SafeAccessibilityActionId): String = when (id) {
        SafeAccessibilityActionId.CLICK_TEXT -> "gui_click_text"
        SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION -> "gui_click_content_description"
        SafeAccessibilityActionId.BACK -> "gui_back"
        SafeAccessibilityActionId.SCROLL_FORWARD -> "gui_scroll_forward"
        SafeAccessibilityActionId.SCROLL_BACKWARD -> "gui_scroll_backward"
        SafeAccessibilityActionId.GLOBAL_BACK -> "gui_global_back"
        SafeAccessibilityActionId.GLOBAL_HOME -> "gui_global_home"
    }

    /** The closed set of wire names the tools expose (mirrors SAFE_ACTION_ALLOWLIST). */
    fun toolNames(): Set<String> =
        SafeAccessibilityActionId.entries.map { toolNameFor(it) }.toSet()

    /**
     * Resolve a tool wire name back to a [SafeAccessibilityActionId], or `null`
     * when it is not one of the safe semantic tools. The
     * coordinate/gesture/extraction names (`tap`/`swipe`/`type_text`/`open_app`/
     * `capture_screen`) deliberately resolve to `null`.
     */
    fun actionIdForToolName(toolName: String): SafeAccessibilityActionId? =
        SafeAccessibilityActionId.entries.firstOrNull { toolNameFor(it) == toolName.trim() }

    /**
     * Dispatch a safe semantic action through the gate with the given live
     * facts, and fold the outcome into a sanitized, bounded result.
     *
     * @param params already-validated-shape params (`text` /
     *   `content_description` / `package_name`); the closed shape + length
     *   bounds are re-checked inside by [ControlledSafeAccessibilityDispatcher].
     * @param optedIn the user's explicit settings opt-in
     * @param serviceEnabled whether a live [SafeAccessibilityActionExecutor] /
     *   connected service is available
     * @param foregroundPackage the *live* foreground package at dispatch time
     * @param allowlist the closed foreground package allowlist
     * @param executor the live platform seam; may be `null` when the service is
     *   down -> BLOCKED_SERVICE and nothing is attempted
     */
    fun dispatch(
        toolName: String,
        params: Map<String, Any?>,
        optedIn: Boolean,
        serviceEnabled: Boolean,
        foregroundPackage: String?,
        allowlist: Set<String>,
        executor: SafeAccessibilityActionExecutor?,
    ): SafeActionToolResult {
        val id = actionIdForToolName(toolName)
            ?: return SafeActionToolResult(
                ok = false,
                status = "UNSUPPORTED",
                message = "动作[$toolName]不在安全语义白名单内（仅支持：${toolNames().sorted().joinToString("/")}）。未执行任何操作。",
            )

        // If the user never explicitly opted in, reject before building a
        // service-backed dispatcher. This is the default and matches the app's
        // safe `accessibilityOptIn = false` default.
        if (!optedIn) {
            return SafeActionToolResult(
                ok = false,
                status = "NOT_CONFIGURED",
                message = "安全动作执行器未启用：请在设置中显式开启 AutoAgent 辅助功能后重试。未执行任何操作。",
            )
        }
        if (!serviceEnabled || executor == null) {
            return SafeActionToolResult(
                ok = false,
                status = "BLOCKED_SERVICE",
                message = "辅助功能服务未连接，无法执行 $id.wireName。请在系统「无障碍」设置中授予 ClawMate 访问权限后重试。未执行任何操作。",
            )
        }

        val gate = SafeAccessibilityExecutionGate(
            optedIn = optedIn,
            serviceEnabled = serviceEnabled,
            currentForegroundPackage = foregroundPackage,
            allowlistedPackages = allowlist.ifEmpty { SafeAccessibilityDefaults.PACKAGE_ALLOWLIST },
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(gate = gate, executor = executor)
        return when (val outcome = dispatcher.dispatch(id.wireName, params)) {
            is AccessibilityExecutionOutcome.NotConfigured -> SafeActionToolResult(
                ok = false, status = "NOT_CONFIGURED", message = sanitize(outcome.message),
            )
            is AccessibilityExecutionOutcome.BlockedService -> SafeActionToolResult(
                ok = false, status = "BLOCKED_SERVICE", message = sanitize(outcome.reason),
            )
            is AccessibilityExecutionOutcome.BlockedPackage -> SafeActionToolResult(
                ok = false, status = "BLOCKED_PACKAGE", message = sanitize(outcome.reason),
            )
            is AccessibilityExecutionOutcome.Unsupported -> SafeActionToolResult(
                ok = false, status = "UNSUPPORTED", message = sanitize(outcome.reason),
            )
            is AccessibilityExecutionOutcome.Error -> SafeActionToolResult(
                ok = false, status = "ERROR", message = sanitize(outcome.reason),
            )
            is AccessibilityExecutionOutcome.Success -> SafeActionToolResult(
                ok = true,
                status = "SUCCESS",
                // De-identified: never echo the clicked node's text / description.
                message = safeSuccessLabel(id),
            )
        }
    }

    /** A fixed, de-identified success label that never contains screen content. */
    private fun safeSuccessLabel(id: SafeAccessibilityActionId): String = when (id) {
        SafeAccessibilityActionId.CLICK_TEXT -> "已点击匹配的文本节点"
        SafeAccessibilityActionId.CLICK_CONTENT_DESCRIPTION -> "已点击匹配的内容描述节点"
        SafeAccessibilityActionId.BACK -> "已执行返回"
        SafeAccessibilityActionId.SCROLL_FORWARD -> "已执行向前滚动"
        SafeAccessibilityActionId.SCROLL_BACKWARD -> "已执行向后滚动"
        SafeAccessibilityActionId.GLOBAL_BACK -> "已执行全局返回"
        SafeAccessibilityActionId.GLOBAL_HOME -> "已执行回到桌面"
    }

    /** Honest result for a timed-out safe action (never a fake success). Bounded. */
    fun timeoutResult(timeoutMs: Long): SafeActionToolResult = SafeActionToolResult(
        ok = false,
        status = "ERROR",
        message = "安全动作执行超时（${timeoutMs}ms）。未执行任何操作。",
    )

    /** Honest result for an unexpected platform exception during a safe action. Bounded. */
    fun exceptionResult(e: Throwable): SafeActionToolResult = SafeActionToolResult(
        ok = false,
        status = "ERROR",
        message = "安全动作执行异常（${e.javaClass.simpleName}）。未执行任何操作。",
    )

    /** Cuts any message down to the bounded length (and collapses newlines). */
    private fun sanitize(raw: String): String {
        val flat = raw.replace("\r", " ").replace("\n", " ")
        return if (flat.length <= SafeActionToolResult.MAX_MESSAGE_LENGTH) flat
        else flat.take(SafeActionToolResult.MAX_MESSAGE_LENGTH).trimEnd() + "…"
    }
}
