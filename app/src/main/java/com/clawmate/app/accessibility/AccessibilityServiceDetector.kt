package com.clawmate.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Abstraction over "is the AutoAgent accessibility service enabled/connected
 * by the Android system?" so the [AccessibilityGate] is deterministically
 * testable without a device.
 *
 * The separation matters: a user can enable the setting toggle inside the app,
 * but only Android's *system* accessibility settings actually grant the
 * service. That grant is what [isServiceEnabledBySystem] reports, and it must
 * be observed (never assumed) before anything could be considered available.
 *
 * This is a **read-only detection** seam: it never performs accessibility
 * actions, never starts the service, and never flips system settings.
 */
interface AccessibilityServiceDetector {

    /**
     * True only when the Android system reports our accessibility service as
     * enabled (service connected/present in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`).
     */
    fun isServiceEnabledBySystem(): Boolean

    /** True when this build actually provides a safe action provider (none today). */
    fun isProviderConfigured(): Boolean
}

/**
 * Live Android implementation reading the system accessibility settings.
 *
 * Mirrors the canonical check: it inspects
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for `{package}/{serviceClass}`.
 * Read-only — it only *observes* the user's system-level grant.
 */
class AndroidAccessibilityServiceDetector(
    private val context: Context,
    private val serviceClass: Class<*> = AccessibilityAutoAgentService::class.java,
) : AccessibilityServiceDetector {

    override fun isServiceEnabledBySystem(): Boolean {
        val expected = ComponentName(context.packageName, serviceClass.name).flattenToString()
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull().orEmpty()
        if (enabled.isBlank()) return false
        // The string is colon-separated; look for our exact component name.
        return enabled.split(':').any { it.trim().equals(expected, ignoreCase = true) }
    }

    override fun isProviderConfigured(): Boolean {
        // This build deliberately ships no on-device action provider. Returning
        // false keeps the boundary BLOCKED (never a claimed-but-fake capability).
        return false
    }
}

/**
 * Device-free detector so unit tests can drive every [AccessibilityGate] state
 * without Android APIs. The test supplies the two facts directly.
 */
class FakeAccessibilityServiceDetector(
    private var serviceEnabled: Boolean = false,
    private var providerConfigured: Boolean = false,
) : AccessibilityServiceDetector {
    fun setServiceEnabled(value: Boolean) { serviceEnabled = value }
    fun setProviderConfigured(value: Boolean) { providerConfigured = value }
    override fun isServiceEnabledBySystem(): Boolean = serviceEnabled
    override fun isProviderConfigured(): Boolean = providerConfigured
}

/**
 * Detector backed by the live [AccessibilityStateRegistry] that the lifecycle
 * scaffold populates. Used by the Settings UI/ViewModel so the displayed state
 * always mirrors what the service actually reported — never an assumption.
 * The provider is deliberately absent in this build (false), keeping the
 * boundary BLOCKED rather than claiming a real capability.
 */
class RegistryAccessibilityServiceDetector : AccessibilityServiceDetector {
    override fun isServiceEnabledBySystem(): Boolean = AccessibilityStateRegistry.isServiceConnected()
    override fun isProviderConfigured(): Boolean = false
}
