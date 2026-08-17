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
    // Phase 36 — periodic local-consolidation scheduling state (explicit action only).
    val periodicSchedulingBusy: Boolean = false,
    val periodicScheduled: Boolean = false,
    val periodicScheduleMessage: String? = null,
    // Phase 36 — explicit model enrich results (per enrich seam; none persists
    // on its own; a distinct persist step runs only for an explicit success).
    val enrichBusy: Boolean = false,
    val enrichResultMessage: String? = null,
    val batchEnrichResultMessage: String? = null,
)
/** Simple storage/about facts, derived at load time. */
data class StorageSummary(
    val cacheBytes: Long = 0L,
    val filesBytes: Long = 0L,
    val modelCount: Int = 0,
    val workflowCount: Int = 0,
)
