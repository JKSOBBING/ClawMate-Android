package com.clawmate.app.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** App-private DataStore for home-recommendation completion flags. */
private val Context.homeRecDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "clawmate_home_recs")

/**
 * DataStore Preferences-backed [HomeRecRepository]. Completion flags are stored
 * under per-recommendation boolean keys (`home_rec_completed.<id>`). A fresh
 * app-private store starts empty (no flags), which is the honest no-data state.
 */
class DataStoreHomeRecRepository(
    private val context: Context,
) : HomeRecRepository {

    override val completionFlags: Flow<Map<String, Boolean>> =
        context.homeRecDataStore.data.map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val recId = key.name.removePrefix(KEY_PREFIX).takeIf { it.isNotEmpty() && it != key.name }
                val bool = (value as? Boolean)
                if (recId != null && bool != null) recId to bool else null
            }.toMap()
        }

    override suspend fun snapshot(): Map<String, Boolean> =
        context.homeRecDataStore.data.first().asMap().mapNotNull { (key, value) ->
            val recId = key.name.removePrefix(KEY_PREFIX).takeIf { it.isNotEmpty() && it != key.name }
            val bool = (value as? Boolean)
            if (recId != null && bool != null) recId to bool else null
        }.toMap()

    override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return false
        context.homeRecDataStore.edit { prefs ->
            prefs[completionKey(cleanId)] = completed
        }
        return completed
    }

    private fun completionKey(id: String) = booleanPreferencesKey(KEY_PREFIX + id)

    companion object {
        private const val KEY_PREFIX = "home_rec_completed."
    }
}
