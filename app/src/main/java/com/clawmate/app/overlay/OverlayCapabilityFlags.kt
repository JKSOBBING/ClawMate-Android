package com.clawmate.app.overlay

/**
 * Build-time capability flags for the float/edge overlay boundary (Phase 20).
 *
 * Mirrors the Phase 19 `RegistryAccessibilityServiceDetector` idea: whether this
 * build actually ships a safe overlay *renderer*. Since Phase 26 a real
 * **status-only** renderer exists (the live, bounded agent-status surface in
 * [OverlayFloatService], driven by [OverlayStateRegistry]). It is deliberately
 * *not* the Harmony edge-flow shader or agent GUI control — it only renders the
 * bounded status text and never intercepts input or executes commands — so
 * `rendererConfigured` is `true` for exactly that narrow status-only surface,
 * and **nothing more**.
 *
 * `overlayOptedIn` is the mutable seed the app may mirror from the user's
 * settings opt-in. Its safe default of `false` keeps the boundary
 * NOT_CONFIGURED and the lifecycle-safe service refusing to start until a user
 * explicitly opts in. It is a plain observable holder (manual DI, matching the
 * app's existing pattern) so the pure policy is tested device-free.
 *
 * Setting `rendererConfigured = true` only changes the *gate*: reaching
 * [OverlayGateStatus.READY] still additionally requires the explicit user
 * opt-in (via [overlayOptedIn] / the persisted settings) **and** the observed
 * system `SYSTEM_ALERT_WINDOW` grant. Neither is auto-enabled here.
 */
object OverlayCapabilityFlags {

    /** Whether the user has explicitly opted in to the overlay boundary. */
    @Volatile
    var overlayOptedIn: Boolean = false

    /**
     * Whether this build ships a safe overlay renderer. Since Phase 26 a real
     * **status-only** renderer exists (no edge-flow shader, no agent GUI
     * control, no screen capture, no input interception, no command execution).
     */
    val rendererConfigured: Boolean
        get() = true
}
