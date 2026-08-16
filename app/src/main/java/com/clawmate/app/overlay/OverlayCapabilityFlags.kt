package com.clawmate.app.overlay

/**
 * Build-time capability flags for the float/edge overlay boundary (Phase 20).
 *
 * Mirrors the Phase 19 `RegistryAccessibilityServiceDetector` idea: whether this
 * build actually ships a safe overlay *renderer*. It deliberately does not —
 * so `rendererConfigured` is always `false` and the [OverlayGate] can never
 * reach [OverlayGateStatus.READY] in this build (no claimed-but-fake overlay).
 *
 * `overlayOptedIn` is the mutable seed the app may mirror from the user's
 * settings opt-in if/when a real renderer is ever wired. Its safe default of
 * `false` keeps the boundary NOT_CONFIGURED and the lifecycle-safe service
 * refusing to start until a user explicitly opts in. It is a plain observable
 * holder (manual DI, matching the app's existing pattern) so the pure policy is
 * tested device-free.
 */
object OverlayCapabilityFlags {

    /** Whether the user has explicitly opted in to the overlay boundary. */
    @Volatile
    var overlayOptedIn: Boolean = false

    /** Whether this build ships a safe overlay renderer (never in this build). */
    val rendererConfigured: Boolean
        get() = false
}
