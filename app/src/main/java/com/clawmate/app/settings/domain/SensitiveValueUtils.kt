package com.clawmate.app.settings.domain

/**
 * Sensitive-value masking for the Settings center.
 *
 * Ported from HarmonyOS `entry/src/main/ets/utils/SensitiveValueUtils.ets`
 * (`isMaskedSensitiveValue`) and `SettingsCenterTypes.ets`
 * (`maskSensitiveSettingValue`, `restoreMaskedSensitiveSettingDefault`).
 *
 * Masking is only ever a *display* transform. A masked value is recognized by
 * the bullet separator marker and is never written back to persistent storage
 * as a literal replacement, nor ever forwarded to a backend. The repository
 * drops any value that still contains the mask marker on write (see
 * SettingsRepository), and the ViewModel exposes reveal/edit behaviour so the
 * real secret (typed by the user) is what persists.
 */
object SensitiveValueUtils {

    /** Mask marker, matching the HarmonyOS `SENSITIVE_MASK_MARKER`. */
    const val MASK_MARKER: Char = '•'

    /** Whether a trimmed string is (or still contains) a masked/mask-marker value. */
    fun isMaskedSensitiveValue(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isEmpty()) return false
        return normalized.indexOf(MASK_MARKER) >= 0
    }

    /**
     * Mask a plain value for display. Empty stays empty; truthfully copies the
     * HarmonyOS `maskSensitiveSettingValue` (<=4 chars shows '••••', otherwise
     * first 4 + bullets + last 4).
     */
    fun maskSensitiveValue(value: String): String {
        if (value.isEmpty()) return ""
        if (value.length <= 4) return "••••".substring(0, value.length)
        return value.substring(0, 4) + "••••••••" + value.substring(value.length - 4)
    }

    /**
     * Restore a masked value back to its known default only when the mask was
     * produced from exactly that default; otherwise return "". A non-masked
     * value passes through untouched.
     */
    fun restoreMaskedSensitiveSettingDefault(value: String, defaultValue: String): String {
        if (!isMaskedSensitiveValue(value)) return value
        return if (defaultValue.isNotEmpty() && value == maskSensitiveValue(defaultValue)) {
            defaultValue
        } else {
            ""
        }
    }
}
