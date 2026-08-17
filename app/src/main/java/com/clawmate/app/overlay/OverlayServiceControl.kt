package com.clawmate.app.overlay

import android.content.Context
import android.content.Intent

/**
 * Pure, device-free decisions for explicitly starting / stopping the
 * lifecycle-safe [OverlayFloatService] (Phase 26).
 *
 * The overlay service may only be launched by an explicit user action while
 * the [OverlayGate] is genuinely [OverlayGateStatus.READY] — i.e. the user
 * opted in AND the system granted `SYSTEM_ALERT_WINDOW` AND the status-only
 * renderer is configured. It must never start on app launch or merely on
 * recomposition. The derived [OverlayServiceStartStep] keeps that rule
 * unit-testable with no Android framework; the Compose layer only ever
 * dispatches `Start` inside an explicit click handler.
 */
sealed class OverlayServiceStartStep {
    /** The user explicitly asked and the gate is READY — may start. */
    data object START : OverlayServiceStartStep()

    /** Do not start; [reason] explains why the gate is not READY. */
    data class KEEP_STOPPED(val reason: String) : OverlayServiceStartStep()
}

/**
 * Deterministic rule for the Settings "start overlay service" action.
 *
 * [START] only when [gate.status] is [OverlayGateStatus.READY] (opt-in +
 * observed `SYSTEM_ALERT_WINDOW` grant + status-only renderer). Any other gate
 * yields [OverlayServiceStartStep.KEEP_STOPPED] with the honest reason. Pure —
 * used by [OverlayServiceController] and the Settings UI, and covered by
 * device-free tests.
 */
object OverlayServiceStartPolicy {

    fun startStep(gate: OverlayGate): OverlayServiceStartStep = when (gate.status) {
        OverlayGateStatus.NOT_CONFIGURED ->
            OverlayServiceStartStep.KEEP_STOPPED("未开启悬浮窗 Overlay，不启动。")
        OverlayGateStatus.BLOCKED ->
            OverlayServiceStartStep.KEEP_STOPPED("尚未观察到系统悬浮窗授权，无法启动。请在系统「悬浮窗」设置中授予 ClawMate 权限。")
        OverlayGateStatus.READY ->
            OverlayServiceStartStep.START
    }

    /**
     * Whether the service should be stopped given the current gate and whether
     * it is still present. True when the service is running but the gate no
     * longer satisfies [START] — e.g. the user turned opt-in off. Pure.
     */
    fun stopRequired(gate: OverlayGate, servicePresent: Boolean): Boolean =
        servicePresent && startStep(gate) != OverlayServiceStartStep.START
}

/**
 * Injectable seam the Settings layer uses to start/stop [OverlayFloatService].
 * The device implementation wraps a [Context]; tests can substitute a fake that
 * only records the calls (device-free).
 */
interface OverlayServiceController {
    /** Explicit user-driven start — the caller must have passed [OverlayServiceStartPolicy]. */
    fun startService()

    /** Explicit stop (e.g. when opt-in is turned off). */
    fun stopService()

    /** True when [OverlayStateRegistry.isOverlayPresent] reports the service live. */
    fun isServicePresent(): Boolean = OverlayStateRegistry.isOverlayPresent()
}

/**
 * Device implementation that starts/stops [OverlayFloatService] via the
 * application [Context]. It contains no logic of its own — it only dispatches
 * the explicit start/stop that the pure [OverlayServiceStartPolicy] authorised.
 * The service itself remains lifecycle-safe ([START_NOT_STICKY], and it further
 * re-derives the [OverlayGate] on start and self-terminates if not READY), so a
 * stray start can never silently render outside the gate.
 */
class AndroidOverlayServiceController(
    private val context: Context,
) : OverlayServiceController {

    override fun startService() {
        context.startService(
            Intent(context.applicationContext, OverlayFloatService::class.java),
        )
    }

    override fun stopService() {
        context.stopService(
            Intent(context.applicationContext, OverlayFloatService::class.java),
        )
    }
}
