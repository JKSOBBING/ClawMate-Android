package com.clawmate.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.clawmate.app.log.AppLogger

/**
 * Lifecycle-safe AutoAgent accessibility service (Phase 19 scaffold + Phase 22
 * safe semantic executor).
 *
 * Migration of the HarmonyOS `autoagent/AutoAgentAbility.ets`
 * (`AccessibilityExtensionAbility`). That source keeps a `context` and logs
 * accessibility events as a *reserved* stub — the real inter-process GUI is
 * driven by the PC bridge. This Android service mirrors that restraint but
 * additionally ships a **tightly controlled on-device** executor for the Phase 22
 * safe semantic allowlist ([SafeAccessibilityAction]):
 *
 *  - **Lifecycle-safe:** on connect/unbind/destroy it only records availability
 *    into [AccessibilityStateRegistry] and logs — it never dispatches
 *    `AccessibilityNodeInfo` actions on its own and never reads or exfiltrates
 *    screenshots.
 *  - **No capability is auto-claimed.** The service performs **no action unless
 *    invoked through the typed executor** ([executeSafeAction]); that entry point
 *    validates the user opt-in, the connected service, and the *live* foreground
 *    package against the allowlist **before every action**, then hands the action
 *    to [AndroidSafeAccessibilityExecutor] (the only type that touches the
 *    framework node/global APIs).
 *  - Phase 19 behavior (the coordinate/gesture boundary reported as
 *    BLOCKED / NOT_CONFIGURED via [AccessibilityGate]) is preserved unmodified:
 *    doing TAP / TYPE_TEXT / SWIPE / OPEN_APP / CAPTURE_SCREEN remains controlled
 *    and off-device, because those require gestures / screenshots / cross-app
 *    control that are never enabled here.
 */
class AccessibilityAutoAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityStateRegistry.onServiceConnected()
        AppLogger.i(LOG_TAG, "AutoAgent accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal presence beacon + best-effort foreground-package observation
        // for the Phase 22 executor's live package validation. We deliberately
        // do not read or forward the window content and never act on the event.
        AccessibilityStateRegistry.onWindowPackage(event?.packageName?.toString())
    }

    override fun onInterrupt() {
        // No-op: nothing executing that could be interrupted.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityStateRegistry.onServiceDisconnected()
        AppLogger.i(LOG_TAG, "AutoAgent accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityStateRegistry.onServiceDisconnected()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Phase 22 — the typed safe-executor entry point
    // ------------------------------------------------------------------

    /**
     * The live foreground package at dispatch time (from the active window root),
     * or `null` when the service cannot read a window right now.
     */
    fun currentForegroundPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Throwable) {
            null
        }

    /**
     * Performs a safe semantic AutoAgent action, **only** through the typed
     * executor. Re-derives the live boundary from [optedIn] (the caller's
     * observed settings opt-in) and the connected service + live foreground
     * package, then delegates to [AndroidSafeAccessibilityExecutor]. Returns an
     * honest [AccessibilityExecutionOutcome]; no action is performed unless every
     * precondition holds and the foreground package was allow listed.
     */
    fun executeSafeAction(
        optedIn: Boolean,
        name: String,
        params: Map<String, Any?> = emptyMap(),
    ): AccessibilityExecutionOutcome {
        val gate = SafeAccessibilityDefaults.gate(
            optedIn = optedIn,
            serviceEnabled = true, // we are connected if this method is reachable
            currentForegroundPackage = currentForegroundPackage(),
        )
        val dispatcher = ControlledSafeAccessibilityDispatcher(
            gate = gate,
            executor = AndroidSafeAccessibilityExecutor(this),
        )
        return dispatcher.dispatch(name, params)
    }

    companion object {
        private const val LOG_TAG = "AutoAgentAccess"
    }
}
