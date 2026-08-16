package com.clawmate.app.accessibility

/**
 * Thread-safe holder of the live AutoAgent accessibility-service state
 * (Phase 19 + Phase 22). Mirrors the `ChatComponent` / `TaskComponent`
 * manual-DI pattern: the [AccessibilityAutoAgentService] records its lifecycle
 * (and, Phase 22, the best-effort foreground package seen in events) into this
 * holder so the rest of the app can build an honest boundary from what the
 * system has *actually* granted — never from assumption.
 *
 * State cleared on [reset] so tests and service disconnects start clean.
 */
object AccessibilityStateRegistry {

    @Volatile
    private var serviceConnected: Boolean = false

    @Volatile
    private var foregroundPackage: String? = null

    /** Whether the lifecycle-safe service currently reports `onServiceConnected`. */
    fun isServiceConnected(): Boolean = synchronized(this) { serviceConnected }

    /** The last foreground package observed by the service (best effort, nullable). */
    fun currentForegroundPackage(): String? = synchronized(this) { foregroundPackage }

    /** Called by the service on connect. */
    fun onServiceConnected() {
        synchronized(this) { serviceConnected = true }
    }

    /** Called by the service on unbind/destroy, and by tests. */
    fun onServiceDisconnected() {
        synchronized(this) {
            serviceConnected = false
            foregroundPackage = null
        }
    }

    /** Best-effort record of the foreground package seen in a window event. */
    fun onWindowPackage(pkg: String?) {
        synchronized(this) {
            if (pkg != null) foregroundPackage = pkg
        }
    }

    /** Reset to the safe default (used in tests). */
    fun reset() {
        synchronized(this) {
            serviceConnected = false
            foregroundPackage = null
        }
    }
}
