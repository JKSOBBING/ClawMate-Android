package com.clawmate.app.profile.avatar.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clawmate.app.profile.avatar.data.ProfileAvatarRepository
import com.clawmate.app.profile.avatar.domain.AvatarGenerationGate
import com.clawmate.app.profile.avatar.domain.AvatarGenerationState
import com.clawmate.app.profile.avatar.domain.AvatarProviderSettings
import com.clawmate.app.profile.avatar.domain.LocalAvatarProfile
import com.clawmate.app.profile.avatar.domain.localProfileCompleteness
import com.clawmate.app.settings.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Profile / Avatar screen. */
data class ProfileAvatarUiState(
    val profile: LocalAvatarProfile = LocalAvatarProfile.empty,
    val draft: LocalAvatarProfile = LocalAvatarProfile.empty,
    val completeness: Int = 0,
    val generationState: AvatarGenerationState = AvatarGenerationState.NOT_CONFIGURED,
    val generationGuidance: String = "",
    val saveError: String? = null,
    val savedOk: Boolean = false,
)

/**
 * ViewModel for the real Profile / Avatar screen: exposes the locally editable
 * avatar profile, derives the honest avatar-generation gate from the configured
 * settings, and persists edits to an app-private DataStore. It never performs
 * network I/O and never fabricates a generated avatar.
 */
class ProfileAvatarViewModel(
    private val profileRepository: ProfileAvatarRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val saveError = MutableStateFlow<String?>(null)
    private val savedOk = MutableStateFlow(false)

    // Working draft (unsaved edits) separate from the persisted snapshot so
    // field edits never touch storage until the user taps save.
    private val draft = MutableStateFlow(LocalAvatarProfile.empty)

    private val settings = settingsRepository.settings

    val uiState: StateFlow<ProfileAvatarUiState> = combine(
        profileRepository.profile,
        settings,
        draft,
        saveError,
        savedOk,
    ) { profile, settings, draft, error, ok ->
        ProfileAvatarUiState(
            profile = profile,
            draft = draft,
            completeness = localProfileCompleteness(profile),
            generationState = generationState(settings),
            generationGuidance = generationGuidance(settings, profile),
            saveError = error,
            savedOk = ok,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileAvatarUiState())

    init {
        viewModelScope.launch {
            val current = profileRepository.snapshot()
            draft.value = current
        }
    }

    /** Called from the UI as the user types; updates the working draft only. */
    fun onFieldUpdate(transform: (LocalAvatarProfile) -> LocalAvatarProfile) {
        val updated = transform(draft.value)
        draft.value = updated
        savedOk.value = false
        saveError.value = null
    }

    fun setDisplayName(value: String) = onFieldUpdate { it.copy(displayName = value) }
    fun setShortSummary(value: String) = onFieldUpdate { it.copy(shortSummary = value) }
    fun setGender(value: String) = onFieldUpdate { it.copy(gender = value) }
    fun setAge(value: String) = onFieldUpdate { it.copy(age = value) }

    fun setDomainSummary(domain: String, value: String) = onFieldUpdate { profile ->
        profile.copy(
            domains = profile.domains.map {
                if (it.domain == domain) it.copy(shortSummary = value) else it
            },
        )
    }

    fun setDomainClaim(domain: String, claims: List<String>) = onFieldUpdate { profile ->
        profile.copy(
            domains = profile.domains.map {
                if (it.domain == domain) it.copy(claims = claims) else it
            },
        )
    }

    /** Persist the current draft (with title-line validation). */
    fun saveDraft() {
        viewModelScope.launch {
            saveError.value = null
            val draftValue = draft.value
            if (draftValue.displayName.isBlank()) {
                saveError.value = "展示名称不能为空"
                return@launch
            }
            val saved = profileRepository.save(draftValue)
            draft.value = saved
            savedOk.value = true
        }
    }

    fun consumeSavedOk() {
        savedOk.value = false
    }

    private fun generationState(settings: com.clawmate.app.settings.domain.AppSettings): AvatarGenerationState =
        AvatarGenerationGate.state(
            AvatarProviderSettings(
                imageBaseUrl = settings.avatarImageBaseUrl,
                apiKey = settings.avatarImageApiKey,
                textToImageModel = settings.avatarTextToImageModel,
                imageSize = settings.avatarImageSize,
            ),
        )

    private fun generationGuidance(
        settings: com.clawmate.app.settings.domain.AppSettings,
        profile: LocalAvatarProfile,
    ): String = AvatarGenerationGate.guidance(
        generationState(settings),
        hasCurrentProfile = localProfileCompleteness(profile) > 0,
    )

    class Factory(
        private val profileRepository: ProfileAvatarRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileAvatarViewModel(profileRepository, settingsRepository) as T
    }
}

