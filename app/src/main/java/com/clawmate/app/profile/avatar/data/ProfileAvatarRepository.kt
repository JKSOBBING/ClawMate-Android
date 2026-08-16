package com.clawmate.app.profile.avatar.data

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import kotlinx.coroutines.flow.Flow

/**
 * Persistence boundary for the locally editable avatar profile. Implementations
 * must be deterministic, app-private, and never store secrets, endpoints, model
 * files, or generated avatar images.
 */
interface ProfileAvatarRepository {
    val profile: Flow<LocalAvatarProfile>

    suspend fun snapshot(): LocalAvatarProfile

    /** Persist the full profile; returns the sanitised, persisted value. */
    suspend fun save(profile: LocalAvatarProfile): LocalAvatarProfile
}
