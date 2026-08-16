package com.clawmate.app.settings.data

import com.clawmate.app.settings.domain.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * Persistent store for Settings values.
 *
 * Backed by DataStore Preferences (or app-private deterministic storage in
 * tests). Writes are typed and normalized; values that are still masked
 * (contain the mask marker) are never persisted verbatim. See
 * [DataStoreSettingsRepository].
 */
interface SettingsRepository {

    /** Observe the full typed snapshot. Emits whenever any key changes. */
    val settings: Flow<AppSettings>

    /** Read the current snapshot once. */
    suspend fun snapshot(): AppSettings

    /** Replace the whole snapshot with a normalized version and persist it. */
    suspend fun save(settings: AppSettings): AppSettings
}
