package com.clawmate.app.settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clawmate.app.settings.domain.AppSettings
import com.clawmate.app.settings.domain.SettingsKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "clawmate_settings")

/**
 * DataStore Preferences-backed [SettingsRepository].
 *
 * Every key is stored as a normalized string under [SettingsKeys]. On read we
 * decode (total); on write we sanitize any masked secret out and normalize via
 * [SettingsCodec.encode]. A one-time default-seed of empty/safe values is
 * applied on first launch through the DataStore initial value (not shown here).
 */
class DataStoreSettingsRepository(
    private val context: Context,
) : SettingsRepository {

    override val settings: Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs -> SettingsCodec.decode(flatFrom(prefs)) }

    override suspend fun snapshot(): AppSettings =
        SettingsCodec.decode(flatFrom(context.settingsDataStore.data.first()))

    override suspend fun save(settings: AppSettings): AppSettings {
        val clean = SettingsCodec.sanitizeForWrite(SettingsCodec.encode(settings))
        context.settingsDataStore.edit { prefs ->
            SettingsKeys.ALL_KEYS.forEach { key -> prefs.remove(stringPreferencesKey(key)) }
            clean.forEach { (key, value) ->
                prefs[stringPreferencesKey(key)] = value
            }
        }
        return SettingsCodec.decode(clean)
    }

    private fun flatFrom(prefs: Preferences): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        SettingsKeys.ALL_KEYS.forEach { key ->
            prefs[stringPreferencesKey(key)]?.let { out[key] = it }
        }
        return out
    }
}
