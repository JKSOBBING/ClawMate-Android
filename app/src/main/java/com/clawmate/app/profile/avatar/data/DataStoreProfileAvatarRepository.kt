package com.clawmate.app.profile.avatar.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clawmate.app.profile.avatar.domain.AvatarDomainSnapshot
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.defaultProfileDomains
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.profileAvatarDataStore: DataStore<Preferences> by preferencesDataStore(name = "clawmate_profile_avatar")

/**
 * DataStore Preferences-backed [ProfileAvatarRepository].
 *
 * The whole profile is stored as one compact JSON payload (kotlinx.serialization)
 * under a single key. On read we tolerate malformed/empty payloads and fall back
 * to the honest empty profile — an unreadable profile can never block the UI and
 * never invents data. No secrets or generated images are ever stored here.
 */
class DataStoreProfileAvatarRepository(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProfileAvatarRepository {

    private val key = stringPreferencesKey(KEY_PROFILE)

    override val profile: Flow<LocalAvatarProfile> =
        context.profileAvatarDataStore.data.map { prefs -> prefs[key]?.let { decode(it) } ?: LocalAvatarProfile.empty }

    override suspend fun snapshot(): LocalAvatarProfile =
        context.profileAvatarDataStore.data.first()[key]?.let { decode(it) } ?: LocalAvatarProfile.empty

    override suspend fun save(profile: LocalAvatarProfile): LocalAvatarProfile {
        val persisted = profile.let(::sanitize)
        context.profileAvatarDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(PersistedProfile.from(persisted))
        }
        return persisted
    }

    private fun decode(payload: String): LocalAvatarProfile = runCatching {
        json.decodeFromString<PersistedProfile>(payload).toDomain()
    }.getOrElse { LocalAvatarProfile.empty }

    private fun sanitize(profile: LocalAvatarProfile): LocalAvatarProfile = profile.copy(
        displayName = profile.displayName.trim(),
        shortSummary = profile.shortSummary.trim(),
        gender = profile.gender.trim(),
        age = profile.age.trim(),
        domains = profile.domains.map { it.copy(domain = it.domain.trim(), shortSummary = it.shortSummary.trim()) },
    )

    companion object {
        const val KEY_PROFILE: String = "localAvatarProfile"

        /** Wire model for (de)serialization — private and stable. */
        @Serializable
        private data class PersistedDomain(
            val domain: String = "",
            val displayName: String = "",
            val shortSummary: String = "",
            val summary: String = "",
            val claims: List<String> = emptyList(),
            val eventCount: Int = 0,
        )

        @Serializable
        private data class PersistedProfile(
            val displayName: String = "",
            val shortSummary: String = "",
            val gender: String = "",
            val age: String = "",
            val domains: List<PersistedDomain> = emptyList(),
            val updatedAt: String = "",
        ) {
            fun toDomain(): LocalAvatarProfile {
                val provided = domains.map {
                    AvatarDomainSnapshot(
                        domain = it.domain,
                        displayName = it.displayName,
                        shortSummary = it.shortSummary,
                        summary = it.summary,
                        claims = it.claims,
                        eventCount = it.eventCount,
                    )
                }
                val defaults = defaultProfileDomains()
                val merged = defaults.map { def ->
                    provided.firstOrNull { it.domain == def.domain } ?: def
                }
                return LocalAvatarProfile(
                    displayName = displayName,
                    shortSummary = shortSummary,
                    gender = gender,
                    age = age,
                    domains = merged,
                    updatedAt = updatedAt,
                )
            }

            companion object {
                fun from(p: LocalAvatarProfile): PersistedProfile = PersistedProfile(
                    displayName = p.displayName,
                    shortSummary = p.shortSummary,
                    gender = p.gender,
                    age = p.age,
                    domains = p.domains.map {
                        PersistedDomain(
                            domain = it.domain,
                            displayName = it.displayName,
                            shortSummary = it.shortSummary,
                            summary = it.summary,
                            claims = it.claims,
                            eventCount = it.eventCount,
                        )
                    },
                    updatedAt = p.updatedAt,
                )
            }
        }
    }
}
