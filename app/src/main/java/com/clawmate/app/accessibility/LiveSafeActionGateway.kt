package com.clawmate.app.accessibility

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Phase 47 — the *live* device-binding gateway that the chat layer uses to run
 * a safe semantic action with a bounded execution budget.
 *
 * It reads the four live facts (explicit opt-in, connected service, live
 * foreground package, closed package allowlist) from the app's current holders
 * / registry, then delegates the actual decision to the pure
 * [GuiSafeActionBridge]. The service-bound [SafeAccessibilityActionExecutor] is
 * obtained from [SafeActionServiceHolder] only for a truly connected service.
 *
 * Timeout/cancellation:
 *  - the synchronous (node-bounded) dispatch is wrapped in [withTimeout] and run
 *    on [Dispatchers.Default] so a pathological window traversal cannot stall
 *    the agent thread forever; a timeout folds into an honest
 *    ERROR result (never a fake success).
 *  - [dispatch] rethrows on timeout (so the caller observes cancellation);
 *    [dispatchOrTimeout] — what the agent adapter calls — folds it into a typed
 *    result.
 */
object LiveSafeActionGateway {

    /** Bounded budget for one safe action (generous; node traversal is already ≤300 nodes). */
    const val ACTION_TIMEOUT_MS: Long = 10_000L

    /** The closed foreground-package allowlist (conservative: ClawMate itself). */
    private val fallbackAllowlist: Set<String> = SafeAccessibilityDefaults.PACKAGE_ALLOWLIST

    /** The live foreground package, preferring the registry's last observation. */
    fun liveForegroundPackage(): String? = AccessibilityStateRegistry.currentForegroundPackage()

    /** Snapshot of the live service-bound boundary (observational, never fabricated). */
    private fun boundarySnapshot(): Boundary {
        val connected = AccessibilityStateRegistry.isServiceConnected()
        val executor = if (connected) SafeActionServiceHolder.currentExecutor() else null
        return Boundary(
            serviceEnabled = connected && executor != null,
            executor = executor,
        )
    }

    /**
     * Dispatch one safe semantic action using live facts, within a bounded
     * timeout. Pure decision is delegated to [GuiSafeActionBridge.dispatch].
     * Re-throws [TimeoutCancellationException] on timeout.
     */
    suspend fun dispatch(
        toolName: String,
        params: Map<String, Any?>,
        optedIn: Boolean = AccessibilityOptInHolder.isOptedIn(),
        foregroundPackage: String? = liveForegroundPackage(),
        allowlist: Set<String> = fallbackAllowlist,
    ): SafeActionToolResult {
        val boundary = boundarySnapshot()
        return withTimeout(ACTION_TIMEOUT_MS) {
            withContext(Dispatchers.Default) {
                GuiSafeActionBridge.dispatch(
                    toolName = toolName,
                    params = params,
                    optedIn = optedIn,
                    serviceEnabled = boundary.serviceEnabled,
                    foregroundPackage = foregroundPackage,
                    allowlist = allowlist,
                    executor = boundary.executor,
                )
            }
        }
    }

    /**
     * Same as [dispatch] but folds a timeout / unexpected platform error into a
     * typed result instead of rethrowing. This is what the agent tool adapter
     * calls, so the tool can never hang and never fake success.
     */
    suspend fun dispatchOrTimeout(
        toolName: String,
        params: Map<String, Any?>,
        optedIn: Boolean = AccessibilityOptInHolder.isOptedIn(),
        foregroundPackage: String? = liveForegroundPackage(),
        allowlist: Set<String> = fallbackAllowlist,
    ): SafeActionToolResult = try {
        dispatch(toolName, params, optedIn, foregroundPackage, allowlist)
    } catch (e: TimeoutCancellationException) {
        GuiSafeActionBridge.timeoutResult(ACTION_TIMEOUT_MS)
    } catch (e: Throwable) {
        GuiSafeActionBridge.exceptionResult(e)
    }

    /** Immutable snapshot of the observed service-bound boundary. */
    private data class Boundary(
        val serviceEnabled: Boolean,
        val executor: SafeAccessibilityActionExecutor?,
    )
}
