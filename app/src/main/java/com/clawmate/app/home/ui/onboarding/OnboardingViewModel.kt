package com.clawmate.app.home.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.home.data.OnboardingRepository
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_VERSION
import com.clawmate.app.home.domain.FIRST_LAUNCH_ONBOARDING_SLIDES
import com.clawmate.app.home.domain.clampFirstLaunchOnboardingIndex
import com.clawmate.app.home.domain.firstLaunchOnboardingSkipTargetIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI surface for the first-launch onboarding. */
sealed interface OnboardingUiState {
    data object Loading : OnboardingUiState
    data class Active(val index: Int, val slideCount: Int) : OnboardingUiState {
        val isLast: Boolean get() = index == slideCount - 1
        val counterLabel: String get() = "${index + 1}/$slideCount"
        val buttonText: String get() = if (isLast) "进入首页" else "下一页"
    }
}

/**
 * ViewModel for the first-launch onboarding. Renders the slide flow
 * (three capability preview slides + one usage-guide page) and, on finish,
 * persists the completion version through [OnboardingRepository]. The caller
 * observes [completed] to swap out of the onboarding gate.
 */
class OnboardingViewModel(
    private val onboardingRepository: OnboardingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Loading)
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    init {
        _state.value = OnboardingUiState.Active(0, FIRST_LAUNCH_ONBOARDING_SLIDES.size)
    }

    fun next() {
        val active = currentActive() ?: return
        if (active.isLast) {
            finish()
        } else {
            _state.value = active.copy(index = clampFirstLaunchOnboardingIndex(active.index + 1))
        }
    }

    fun skip() {
        val active = currentActive() ?: return
        _state.value = active.copy(index = firstLaunchOnboardingSkipTargetIndex(active.index))
    }

    fun setIndex(index: Int) {
        val active = currentActive() ?: return
        _state.value = active.copy(index = clampFirstLaunchOnboardingIndex(index))
    }

    /** Complete the guide: persist the version and signal the shell. */
    fun finish() {
        viewModelScope.launch {
            onboardingRepository.saveVersion(FIRST_LAUNCH_ONBOARDING_VERSION)
            _completed.value = true
        }
    }

    private fun currentActive(): OnboardingUiState.Active? = _state.value as? OnboardingUiState.Active
}
