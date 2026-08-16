package com.clawmate.app.profile.avatar.data

import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [ProfileAvatarRepository] for deterministic unit tests. */
class FakeProfileAvatarRepository(
    initial: LocalAvatarProfile = LocalAvatarProfile.empty,
) : ProfileAvatarRepository {
    private val state = MutableStateFlow(initial)
    override val profile: Flow<LocalAvatarProfile> = state

    override suspend fun snapshot(): LocalAvatarProfile = state.value

    override suspend fun save(profile: LocalAvatarProfile): LocalAvatarProfile {
        state.value = profile
        return profile
    }
}
