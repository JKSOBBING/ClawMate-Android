package com.clawmate.app.overlay

/**
 * Truthful availability and surfacing results for the Phase 20 float/edge
 * overlay boundary (migration row C1).
 *
 * The HarmonyOS product renders a real `SYSTEM_FLOAT_WINDOW` two-layer overlay
 * (`pages/FloatWindow.ets` + `pages/EdgeFlowWindow.ets`, torn down in
 * `entryability/EntryAbility.ets`) that shows live agent status and edge glow.
 * Android cannot silently provide that capability: it requires the
 * [android.Manifest.permission.SYSTEM_ALERT_WINDOW] runtime permission granted
 * through the system's `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` screen, plus
 * an explicit user opt-in, plus a real safe overlay renderer.
 *
 * This boundary makes that honesty explicit through the same three-state shape
 * the AutoAgent accessibility boundary (Phase 19) uses:
 *  - [OverlayGateStatus] is the live availability of the boundary;
 *  - [OverlaySurfaceResult] is what surfacing the float overlay yields — always
 *    a typed gate/unavailable/rejection, **never a fake agent surface**.
 */

/**
 * The live availability of the float/edge overlay boundary.
 *
 *  - [NOT_CONFIGURED] — the user has not explicitly opted in (the settings
 *    gate is off); the overlay is dormant and nothing renders.
 *  - [BLOCKED] — the user opted in but the system has not granted the overlay
 *    runtime permission (`Settings.canDrawOverlays`), so nothing can render on
 *    top of other apps.
 *  - [READY] — the user opted in AND the system granted overlay permission AND
 *    a safe overlay renderer is configured. This build ships no such renderer,
 *    so [READY] is not reachable with the defaults; it exists so a future real
 *    renderer is represented without claiming capability today.
 */
enum class OverlayGateStatus {
    NOT_CONFIGURED,
    BLOCKED,
    READY,
}

/**
 * The typed result of attempting to show/surface the float overlay. One of
 * these is returned every time; there is intentionally no "success" variant in
 * this build because the safe scaffold renders only an honest neutral/status
 * surface and never fakes agent content, never captures/exfiltrates screens
 * and never executes GUI actions.
 */
sealed class OverlaySurfaceResult {
    /** User has not opted in / overlay switched off. */
    data class NotConfigured(val message: String) : OverlaySurfaceResult()

    /** Opted in but the system overlay runtime permission is not granted. */
    data class BlockedPermission(val reason: String) : OverlaySurfaceResult()

    /** Opted in + permission granted but no safe overlay renderer is configured. */
    data class BlockedNoRenderer(val reason: String) : OverlaySurfaceResult()

    /**
     * The boundary is READY and would render only the honest neutral/status
     * surface. [surfaceText] is static, non-agent status text — it never
     * contains fabricated agent activity or captured screen content.
     */
    data class ReadyNeutralSurface(val surfaceText: String) : OverlaySurfaceResult()
}

/**
 * The static, honest text the READY boundary would put on the neutral overlay
 * surface. Deliberately contains no dynamic agent content, no screenshot data
 * and no user data — only a truthful capability-boundary statement.
 */
object OverlayNeutralSurfaceText {
    /** The single neutral/status line rendered by the READY surface scaffold. */
    const val STATUS_LINE: String =
        "ClawMate 浮窗边界就绪 — 安全脚手架，仅显示状态，不渲染任何智能体内容"
}

/**
 * A pure, deterministic description of the single Android "system-settings
 * permission request action" for the overlay boundary.
 *
 * Android exposes overlay-grant via the system Settings screen
 * (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, carried to the system settings
 * app with the package name as data). This model is the *actionable request*:
 *  - [canRequest] tells whether showing the request makes sense in the current
 *    gate (only when the user opted in but permission is still missing);
 *  - [action] is the Android intent action constant, and [packageName] the data
 *    it should carry — kept as plain string data so it is unit-testable
 *    without Android framework stubs.
 *
 * It never *launches* anything by itself; it only describes the request a UI
 * layer may dispatch, and [canRequest] keeps that launch gated.
 */
data class OverlayPermissionRequest(
    /** Bare string of `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. */
    val action: String,
    /** The application package to request overlay permission for. */
    val packageName: String,
    /** Whether surfacing this request action is valid in the current gate. */
    val canRequest: Boolean,
) {
    companion object {
        /**
         * Builds the request for [overlayOptIn] state and the granted
         * [overlayPermissionGranted] fact. The request is only actionable when
         * the user has opted in but permission is still missing — never when
         * already granted, and never when the boundary is not configured.
         */
        fun forGate(
            baselineAction: String,
            packageName: String,
            overlayOptIn: Boolean,
            overlayPermissionGranted: Boolean,
        ): OverlayPermissionRequest = OverlayPermissionRequest(
            action = baselineAction,
            packageName = packageName,
            canRequest = overlayOptIn && !overlayPermissionGranted,
        )
    }
}

/**
 * Pure, deterministic gate that derives the float/edge overlay boundary from
 * three independent facts and returns honest results for any surface attempt.
 *
 * Inputs are injected so the gate is fully host-testable:
 *  - [optedIn] — the user's explicit settings opt-in ([AppSettings.overlayOptIn]);
 *  - [overlayPermissionGranted] — whether the Android system reports the
 *    overlay runtime permission granted (see [OverlayPermissionDetector], an
 *    abstraction so this is unit-testable without a device);
 *  - [rendererConfigured] — whether a real safe overlay renderer is configured
 *    (no renderer exists in this build, so [READY] is normally unreachable).
 *
 * The gate never renders anything and never fakes a surface: it only derives
 * status and maps a "show overlay" attempt to a typed [OverlaySurfaceResult].
 */
class OverlayGate(
    private val optedIn: Boolean,
    private val overlayPermissionGranted: Boolean,
    private val rendererConfigured: Boolean,
) {

    val status: OverlayGateStatus
        get() = when {
            !optedIn -> OverlayGateStatus.NOT_CONFIGURED
            !overlayPermissionGranted -> OverlayGateStatus.BLOCKED
            !rendererConfigured -> OverlayGateStatus.BLOCKED
            else -> OverlayGateStatus.READY
        }

    /**
     * The result of attempting to surface the float overlay, derived purely
     * from the current gate. Always returns a typed result — never a fake
     * success:
     *  - NOT_CONFIGURED → [OverlaySurfaceResult.NotConfigured];
     *  - opted-in but permission missing → [OverlaySurfaceResult.BlockedPermission];
     *  - opted-in + granted but no renderer → [OverlaySurfaceResult.BlockedNoRenderer];
     *  - fully READY → a static [OverlaySurfaceResult.ReadyNeutralSurface] that
     *    carries only the honest neutral status line.
     */
    fun surface(): OverlaySurfaceResult = when {
        !optedIn -> OverlaySurfaceResult.NotConfigured(
            "悬浮窗边界未启用：请在设置中显式开启后重试。未渲染任何内容。",
        )
        !overlayPermissionGranted -> OverlaySurfaceResult.BlockedPermission(
            "已开启悬浮窗边界，但我们未观察到系统已授予悬浮窗（SYSTEM_ALERT_WINDOW）权限。请先在系统「悬浮窗」设置中授予 ClawMate 权限。未渲染任何内容。",
        )
        !rendererConfigured -> OverlaySurfaceResult.BlockedNoRenderer(
            "系统已授予悬浮窗权限，但当前构建未配置任何安全浮窗渲染器；不渲染任何智能体浮层。请使用 PC/HDC 桥接（已作为受控禁用状态暴露）查看智能体状态。",
        )
        else -> OverlaySurfaceResult.ReadyNeutralSurface(OverlayNeutralSurfaceText.STATUS_LINE)
    }
}
