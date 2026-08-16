package com.clawmate.app.overlay

/**
 * Thread-safe holder of the live float/edge overlay state (Phase 20). Mirrors
 * the Phase 19 `AccessibilityStateRegistry` manual-DI pattern: the
 * lifecycle-safe [OverlayFloatService] records what it has *actually*
 * observed/been granted into this holder so the rest of the app can build an
 * honest [OverlayGate] — never from assumption.
 *
 * The registry stores only two observable facts: whether the backend observed
 * the overlay permission as granted, and whether the scaffold observed the
 * overlay service present/connected. It deliberately holds **no screenshot
 * data, no agent content and no window state** — the scaffold cannot and does
 * not carry captured screen content across components.
 *
 * State is cleared on [reset] so tests and service disconnects start clean.
 */
object OverlayStateRegistry {

    @Volatile
    private var permissionGranted: Boolean = false

    @Volatile
    private var overlayPresent: Boolean = false

    /** Whether the system overlay permission has been observed as granted. */
    fun isPermissionGranted(): Boolean = synchronized(this) { permissionGranted }

    /** Whether the lifecycle-safe overlay scaffold currently reports present/connected. */
    fun isOverlayPresent(): Boolean = synchronized(this) { overlayPresent }

    /** Called when the system overlay permission is observed granted. */
    fun onPermissionGranted() {
        synchronized(this) { permissionGranted = true }
    }

    /** Called by the overlay service on start (guarded) and on bind/connect. */
    fun onOverlayPresent() {
        synchronized(this) { overlayPresent = true }
    }

    /** Called by the overlay service on stop/destroy, and by tests. */
    fun onOverlayGone() {
        synchronized(this) { overlayPresent = false }
    }

    /** Reset to the safe default (used in tests). */
    fun reset() {
        synchronized(this) {
            permissionGranted = false
            overlayPresent = false
        }
    }
}
