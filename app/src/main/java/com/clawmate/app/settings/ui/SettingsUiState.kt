package com.clawmate.app.settings.ui

import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.SettingsSectionId

/**
 * UI state for the Settings center.
 *
 * [draft] is the working copy the user edits; [applied] is the last value
 * confirmed and persisted. Section navigation uses [selectedSectionId]
 * (NONE = the section list, matching `SETTINGS_SECTION_NONE`). Sensitive
 * reveal/edit is tracked per key in [revealedSensitiveKeys] so only one
 * sensitive field is editable at a time (mirroring HarmonyOS
 * `activeSensitiveInputId`).
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val saveBusy: Boolean = false,
    val saved: Boolean = false,
    val draft: AppSettings = AppSettings.defaults(),
    val appSettings: AppSettings = AppSettings.defaults(), // last persisted
    val selectedSectionId: Int = SettingsSectionId.NONE,
    val revealedSensitiveKeys: Set<String> = emptySet(),
    val dirty: Boolean = false,
    val versionName: String = "1.0.0",
    val versionCode: Long = 1L,
    val storageSummary: StorageSummary = StorageSummary(),
    // Phase 8 — HDC bridge live health test (only ever runs on explicit click).
    val hdcTesting: Boolean = false,
    val hdcTestConnected: Boolean = false,
    val hdcTestMessage: String? = null,
)

/** Simple storage/about facts, derived at load time. */
data class StorageSummary(
    val cacheBytes: Long = 0L,
    val filesBytes: Long = 0L,
    val modelCount: Int = 0,
    val workflowCount: Int = 0,
)
