package com.clawmate.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.clawmate.app.log.AppLogger

/**
 * Lifecycle-safe AutoAgent accessibility scaffold (Phase 19, migration row B1).
 *
 * Migration of the HarmonyOS `autoagent/AutoAgentAbility.ets`
 * (`AccessibilityExtensionAbility`). That source keeps a `context` and logs
 * accessibility events as a *reserved* stub — the real inter-process GUI is
 * driven by the PC bridge. This Android scaffold mirrors that restraint:
 *
 *  - **Lifecycle-safe:** on connect/unbind/destroy it only records
 *    availability into [AccessibilityStateRegistry] and logs — it never calls
 *    [AccessibilityService.performGlobalAction], never dispatches
 *    `AccessibilityNodeInfo` actions, never injects gestures/text, never reads
 *    or exfiltrates screenshots, and never drives other apps.
 *  - **No capability is declared or claimed.** Per the manifest/service
 *    config, `canPerformGestures`, `canRetrieveWindowContent`,
 *    `canControlMagnification` and unrestricted event types are **not**
 *    enabled, so the service genuinely cannot perform hidden input or
 *    cross-app control.
 *  - [onAccessibilityEvent] is kept to a minimal, read-only presence beacon for
 *    availability; window content is never captured or forwarded.
 *
 * The real availability decision is made by [AccessibilityGate] against the
 * registered state; this service is the compliant detection surface the gate
 * reads, not an executor.
 */
class AccessibilityAutoAgentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityStateRegistry.onServiceConnected()
        AppLogger.i(LOG_TAG, "AutoAgent accessibility service connected (lifecycle-safe scaffold; no actions performed)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Minimal presence beacon only. We deliberately do not read the window
        // content, do not forward package/text data, and never act on the
        // event. Nothing here can trigger hidden input / screenshots /
        // cross-app control.
    }

    override fun onInterrupt() {
        // No-op: nothing is executing that could be interrupted.
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

    companion object {
        private const val LOG_TAG = "AutoAgentAccess"
    }
}
