package com.clawmate.app.overlay

/**
 * Contract for the one Android "system-settings permission request action" the
 * float/edge overlay boundary may surface (Phase 20, migration row C1).
 *
 * Android grants the overlay runtime permission (`SYSTEM_ALERT_WINDOW`) only
 * through the system Settings screen that `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 * opens. This object centralises the *action string* as plain data so pure
 * layers ([OverlayPermissionRequest], [SettingsViewModel.overlayPermissionRequest],
 * and the Settings UI) can describe the request without reaching into Android
 * framework classes — the constant is inlined by the compiler, so it is also
 * safe to reference from JVM unit tests.
 */
object OverlayPermissionContract {

    /**
     * The bare string value of `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
     * (`"android.settings.action.MANAGE_OVERLAY_PERMISSION"`). The UI layer
     * constructs the actual intent from this action + `Uri.parse("package:$pkg")`.
     */
    const val ACTION_MANAGE_OVERLAY_PERMISSION: String = "android.settings.action.MANAGE_OVERLAY_PERMISSION"

    /**
     * Placeholder package used in the device-free [OverlayPermissionRequest]
     * descriptor. The live UI substitutes the real application package when it
     * builds the actual launch intent (the descriptor is pure metadata and never
     * launches anything).
     */
    const val REQUEST_PACKAGE_NAME_PLACEHOLDER: String = "com.clawmate.app"
}
