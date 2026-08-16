package com.clawmate.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clawmate.app.home.data.HomeRecRepository
import com.clawmate.app.home.domain.HomeProfileViewModel
import com.clawmate.app.home.domain.HomeRecommendationViewModel
import com.clawmate.app.home.domain.HomeUiState
import com.clawmate.app.home.domain.RecommendationListLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the home tab's recommendation list.
 */
data class RecommendationListUi(
    val recommendations: List<HomeRecommendationViewModel> = emptyList(),
    val showAll: Boolean = false,
    val pageIndex: Int = 0,
) {
    val page: RecommendationListLogic.Page
        get() = RecommendationListLogic.pageItems(recommendations, showAll, pageIndex)
}

/**
 * ViewModel for the home tab: loads the (honest, possibly empty) home profile
 * and drives recommendation completion, filtering, pagination and expansion.
 *
 * Recommendation completion toggles are persisted through [HomeRecRepository];
 * the list itself never invents recommendations — it colours the ones supplied
 * by the profile source with the stored flags.
 */
class HomeViewModel(
    private val homeRecRepository: HomeRecRepository,
    profileLoader: suspend () -> HomeProfileViewModel,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _list = MutableStateFlow(RecommendationListUi())
    val list: StateFlow<RecommendationListUi> = _list.asStateFlow()

    init {
        load(profileLoader)
    }

    fun load(profileLoader: suspend () -> HomeProfileViewModel) {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val result = runCatching { profileLoader() }
            result.onSuccess { profile ->
                val flags = try {
                    homeRecRepository.snapshot()
                } catch (e: Exception) {
                    emptyMap()
                }
                val coloured = homeRecRepository.applyFlags(profile.recommendations, flags)
                _list.update { it.copy(recommendations = coloured, showAll = false, pageIndex = 0) }
                _state.value = HomeUiState.Ready(profile.copy(recommendations = coloured))
            }
            result.onFailure { e ->
                _state.value = HomeUiState.Error(e.message ?: "加载首页画像失败")
            }
        }
    }

    // ----------------------------------------------------------- list actions

    fun setShowAll(showAll: Boolean) {
        _list.update { it.copy(showAll = showAll, pageIndex = 0) }
    }

    fun nextPage() {
        _list.update { current ->
            val count = current.page.pageCount
            current.copy(pageIndex = RecommendationListLogic.nextPageIndex(current.pageIndex, count))
        }
    }

    fun previousPage() {
        _list.update { current ->
            val count = current.page.pageCount
            current.copy(pageIndex = RecommendationListLogic.previousPageIndex(current.pageIndex, count))
        }
    }

    /**
     * Persist a recommendation's completion flag and update the local list.
     * Returns the effective value (false when the id is rejected).
     */
    fun setRecommendationCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            val stored = homeRecRepository.setCompleted(id, completed)
            _list.update { current ->
                val updated = current.recommendations.map {
                    if (it.id == id) it.copy(completed = stored) else it
                }
                current.copy(recommendations = updated)
            }
        }
    }
}
