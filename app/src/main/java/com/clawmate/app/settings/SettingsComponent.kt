package com.clawmate.app.settings

import android.content.Context
import com.clawmate.app.settings.config.EndpointBridge
import com.clawmate.app.settings.data.DataStoreSettingsRepository
import com.clawmate.app.settings.data.FakeSettingsRepository
import com.clawmate.app.settings.data.SettingsRepository

/**
 * Lightweight manual dependency container for the Settings slice (Phase 5).
 * Mirrors ChatComponent / GalleryComponent / TaskComponent: the repository is
 * created once and survives configuration changes.
 *
 * The Phase-5 requirements let tests inject a [FakeSettingsRepository]; the
 * production app always uses a [DataStoreSettingsRepository] plus the
 * git-ignored-endpoint [EndpointBridge] so editing the cloud base
 * URL/model/API key here also updates the runtime [com.clawmate.app.config
 * .EndpointConfig] that ChatServerConfig / the gallery read.
 */
object SettingsComponent {

    @Volatile private var repository: SettingsRepository? = null

    fun repository(context: Context): SettingsRepository =
        repository ?: DataStoreSettingsRepository(context.applicationContext).also { repository = it }

    fun fakeRepository(initial: com.clawmate.app.settings.domain.AppSettings = com.clawmate.app.settings.domain.AppSettings.defaults()): SettingsRepository =
        FakeSettingsRepository(initial)

    /** Reset the cached instance (used in tests). */
    fun reset() {
        synchronized(this) {
            repository = null
        }
    }
}
