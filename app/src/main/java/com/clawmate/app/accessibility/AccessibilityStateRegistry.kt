package com.clawmate.app.accessibility

/**
 * Thread-safe holder of the live AutoAgent accessibility-service state
 * (Phase 19). Mirrors the `ChatComponent` / `TaskComponent` manual-DI pattern:
 * the [AccessibilityAutoAgentService] records its lifecycle into this holder so
 * the rest of the app can build an honest [AccessibilityGate] from what the
 * system has *actually* granted — never from assumption.
 *
 * State cleared on [reset] so tests and service disconnects start clean.
 */
object AccessibilityStateRegistry {

    @Volatile
    private var serviceConnected: Boolean = false

    /** Whether the lifecycle-safe service currently reports `onServiceConnected`. */
    fun isServiceConnected(): Boolean = synchronized(this) { serviceConnected }

    /** Called by the service on connect. */
    fun onServiceConnected() {
        synchronized(this) { serviceConnected = true }
    }

    /** Called by the service on unbind/destroy, and by tests. */
    fun onServiceDisconnected() {
        synchronized(this) { serviceConnected = false }
    }

    /** Reset to the safe default (used in tests). */
    fun reset() {
        synchronized(this) {
            serviceConnected = false
        }
    }
}
