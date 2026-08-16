package com.clawmate.app.overlay

/**
 * Pure, device-free policy for the lifecycle-safe [OverlayFloatService].
 *
 * An Android overlay service must **never start silently**: it must only stay
 * alive (and only render its neutral surface) when the [OverlayGate] is truly
 * [OverlayGateStatus.READY] — i.e. the user explicitly opted in, the system
 * granted the overlay permission, and a safe renderer is configured. In every
 * other case the service must record an honest state and terminate itself
 * (`STOP`) rather than linger or draw anything.
 *
 * The decision is kept pure and injected so it is unit-testable with no Android
 * framework. [OverlayFloatService] is a thin shell that applies the policy.
 */
sealed class OverlayServiceDecision {
    /** The service must stop itself immediately — the boundary is not READY. */
    data class Stop(val reason: String) : OverlayServiceDecision()

    /**
     * The service may live and render **only** the honest neutral surface.
     * [surfaceText] is [OverlayNeutralSurfaceText.STATUS_LINE] (static, no
     * agent content); the service never draws anything else.
     */
    data class RenderNeutralSurface(val surfaceText: String) : OverlayServiceDecision()
}

/**
 * Deterministically derives what a lifecycle-safe overlay service may do from
 * the [gate] in effect. Pure — used by the service at start and covered by
 * device-free tests for safe disabled behavior.
 */
object OverlayServicePolicy {

    /**
     * Decides the service outcome for [gate].
     *
     * - NOT_CONFIGURED → [OverlayServiceDecision.Stop] (not opted in);
     * - BLOCKED (permission missing) → [OverlayServiceDecision.Stop];
     * - BLOCKED (no renderer) → [OverlayServiceDecision.Stop];
     * - READY → [OverlayServiceDecision.RenderNeutralSurface], rendering only
     *   the honest static [OverlayNeutralSurfaceText]. This build has no
     *   renderer configured, so `READY` is normally unreachable and the
     *   neutral surface stays a represented-but-unrendered capability.
     */
    fun decide(gate: OverlayGate): OverlayServiceDecision = when (val surface = gate.surface()) {
        is OverlaySurfaceResult.NotConfigured ->
            OverlayServiceDecision.Stop(surface.message + " Refusing to start silently.")
        is OverlaySurfaceResult.BlockedPermission ->
            OverlayServiceDecision.Stop(surface.reason + " Refusing to start.")
        is OverlaySurfaceResult.BlockedNoRenderer ->
            OverlayServiceDecision.Stop(surface.reason + " Refusing to start.")
        is OverlaySurfaceResult.ReadyNeutralSurface ->
            OverlayServiceDecision.RenderNeutralSurface(surface.surfaceText)
    }
}
