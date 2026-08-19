package com.clawmate.app.accessibility

/**
 * Phase 47 — the live [AccessibilityAutoAgentService] instance holder that lets
 * the agent's GUI tools reach the real, gated executor.
 *
 * The [AccessibilityAutoAgentService] registers itself here on connect and
 * unregisters on disconnect/destroy, so the chat layer can build a
 * [SafeAccessibilityActionExecutor] from the *connected* service at tool-
 * execution time — never from a stale or fabricated instance.
 *
 * Safety:
 *  - [currentExecutor] returns `null` when no service is currently connected,
 *    and the caller treats that as BLOCKED_SERVICE (nothing is attempted);
 *  - the returned executor goes through
 *    [ControlledSafeAccessibilityDispatcher], which re-validates the opt-in,
 *    the connected service and the *live* foreground package allowlist before
 *    every action — this holder never bypasses the gate;
 *  - this holder only *reflects* the live service lifecycle; it performs no
 *    action itself.
 */
object SafeActionServiceHolder {

    @Volatile
    private var service: AccessibilityAutoAgentService? = null

    /** Called by [AccessibilityAutoAgentService.onServiceConnected]. */
    fun onConnected(instance: AccessibilityAutoAgentService) {
        synchronized(this) { service = instance }
    }

    /** Called by the service on unbind/interrupt/destroy, and by tests. */
    fun onDisconnected() {
        synchronized(this) { service = null }
    }

    /**
     * A live [SafeAccessibilityActionExecutor] backed by the connected service,
     * or `null` when no service is connected right now. The caller must treat a
     * `null` result as BLOCKED_SERVICE and attempt nothing.
     */
    fun currentExecutor(): SafeAccessibilityActionExecutor? =
        synchronized(this) { service?.let { AndroidSafeAccessibilityExecutor(it) } }
}
