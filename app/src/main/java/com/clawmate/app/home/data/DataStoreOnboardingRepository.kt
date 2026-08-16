package com.clawmate.app.home.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_STORAGE_KEY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** App-private preferences DataStore for home/onboarding state. */
private val Context.homeDataStore: DataStore<Preferences> by preferencesDataStore(name = "clawmate_home")

/**
 * DataStore Preferences-backed [OnboardingRepository].
 *
 * A fresh (configuration-free) install reads an unset key as "not completed",
 * so the first-launch guide always appears once and requires no network.
 */
class DataStoreOnboardingRepository(
    private val context: Context,
) : OnboardingRepository {

    override val onboardingVersion: Flow<String> =
        context.homeDataStore.data.map { prefs ->
            prefs[onboardingVersionKey] ?: ""
        }

    override suspend fun storedVersion(): String =
        context.homeDataStore.data.first()[onboardingVersionKey] ?: ""

    override suspend fun saveVersion(version: String) {
        context.homeDataStore.edit { prefs ->
            if (version.isBlank()) {
                prefs.remove(onboardingVersionKey)
            } else {
                prefs[onboardingVersionKey] = version
            }
        }
    }

    private val onboardingVersionKey = stringPreferencesKey(FIRST_LAUNCH_ONBOARDING_STORAGE_KEY)
}
