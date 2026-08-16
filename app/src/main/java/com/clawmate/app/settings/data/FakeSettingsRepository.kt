package com.clawmate.app.settings.data

import com.clawmate.app.settings.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory deterministic [SettingsRepository] used by unit tests and Compose
 * previews. It applies the same [SettingsCodec] normalization/sanitization
 * guarantees as the DataStore-backed store, producing round-trips identical to
 * production. Barely any platform dependency, so it runs on the JVM.
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings.defaults()) : SettingsRepository {

    private val backing = MutableStateFlow(initial)

    override val settings: Flow<AppSettings>
        get() = backing.map { SettingsCodec.decode(SettingsCodec.sanitizeForWrite(SettingsCodec.encode(it))) }

    override suspend fun snapshot(): AppSettings =
        SettingsCodec.decode(SettingsCodec.sanitizeForWrite(SettingsCodec.encode(backing.value)))

    override suspend fun save(settings: AppSettings): AppSettings {
        val clean = SettingsCodec.decode(SettingsCodec.sanitizeForWrite(SettingsCodec.encode(settings)))
        backing.value = clean
        return clean
    }
}
