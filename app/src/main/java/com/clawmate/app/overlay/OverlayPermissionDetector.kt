package com.clawmate.app.overlay

import android.content.Context
import android.provider.Settings

/**
 * Abstraction over "has the Android system granted the overlay runtime
 * permission (`SYSTEM_ALERT_WINDOW`)?" so the [OverlayGate] is deterministically
 * testable without a device.
 *
 * The user can flip the in-app opt-in toggle, but only Android's *system*
 * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` screen actually grants the
 * overlay permission. That grant is what [isOverlayPermissionGranted] reports,
 * and it must be *observed* (never assumed) before the boundary could be
 * considered available — otherwise we would claim a fake capability.
 *
 * This is a **read-only detection** seam: it never adds or removes any window,
 * never starts the overlay service, and never navigates/launches system
 * settings. (Launching the settings request screen is a separate, gated action
 * described by [OverlayPermissionRequest].)
 */
interface OverlayPermissionDetector {

    /**
     * True only when the Android system reports the overlay runtime permission
     * as granted (`Settings.canDrawOverlays`).
     */
    fun isOverlayPermissionGranted(): Boolean

    /** True when this build actually provides a safe overlay renderer (none today). */
    fun isRendererConfigured(): Boolean
}

/**
 * Live Android implementation reading the system overlay permission.
 *
 * Mirrors the canonical check via [androidx.core] `Settings.canDrawOverlays` /
 * the platform `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` grant. Read-only —
 * it only *observes* the user's system-level grant.
 */
class AndroidOverlayPermissionDetector(
    private val context: Context,
) : OverlayPermissionDetector {

    override fun isOverlayPermissionGranted(): Boolean =
        Settings.canDrawOverlays(context)

    override fun isRendererConfigured(): Boolean {
        // This build deliberately ships no on-device overlay renderer. Returning
        // false keeps the boundary BLOCKED (never a claimed-but-fake capability).
        return false
    }
}

/**
 * Device-free detector so unit tests can drive every [OverlayGate] state
 * without Android APIs. The test supplies the two facts directly.
 */
class FakeOverlayPermissionDetector(
    private var permissionGranted: Boolean = false,
    private var rendererConfigured: Boolean = false,
) : OverlayPermissionDetector {
    fun setPermissionGranted(value: Boolean) { permissionGranted = value }
    fun setRendererConfigured(value: Boolean) { rendererConfigured = value }
    override fun isOverlayPermissionGranted(): Boolean = permissionGranted
    override fun isRendererConfigured(): Boolean = rendererConfigured
}

/**
 * Detector backed by the live [OverlayStateRegistry] that the lifecycle-safe
 * scaffold populate. Used by the Settings UI/ViewModel so the displayed state
 * always mirrors what actually observed — never an assumption. The renderer is
 * deliberately absent in this build (false), keeping the boundary BLOCKED
 * rather than claiming a real overlay surface.
 */
class RegistryOverlayPermissionDetector : OverlayPermissionDetector {
    override fun isOverlayPermissionGranted(): Boolean = OverlayStateRegistry.isPermissionGranted()
    override fun isRendererConfigured(): Boolean = false
}
